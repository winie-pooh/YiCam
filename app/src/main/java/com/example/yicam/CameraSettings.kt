/**
 * Responsible for: the camera settings screen logic: asking the camera for the allowed values of each setting (msg_id 3 with param=key), keeping the user's unsaved choices, applying only the changed ones (video standard first, then re-reading the lists that depend on it) and setting the camera clock from the phone.
 * Not responsible for: drawing (SettingsScreen.kt), the camera mode, photo, record and zoom controls (CameraController.kt), or the link to the camera (CameraSession.kt).
 */
package com.example.yicam

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One setting the camera lets us change. [edited] is the user's unsaved choice. */
data class SettingRow(
    val key: String,
    val group: SettingGroup,
    val options: List<String>,
    val current: String,
    val edited: String? = null,
) {
    val value: String get() = edited ?: current
    val isSwitch: Boolean get() = SettingsCatalog.isSwitch(options)
}

enum class SettingsNote { APPLIED, PARTIAL, FAILED, CLOCK_SYNCED, CAMERA_BUSY, NOT_CONNECTED }

data class SettingsMessage(val kind: SettingsNote, val detail: String = "")

data class SettingsUiState(
    val available: Boolean = false,
    /** Allowed values are being read from the camera. */
    val loading: Boolean = false,
    val applying: Boolean = false,
    /** The camera is recording or taking pictures, so it will not accept changes. */
    val cameraBusy: Boolean = false,
    val rows: List<SettingRow> = emptyList(),
    val note: SettingsMessage? = null,
) {
    val changedCount: Int get() = rows.count { it.edited != null }
}

class CameraSettings(
    private val session: CameraSession,
    private val scope: CoroutineScope,
    /** The phone time as "yyyy-MM-dd HH:mm:ss" (the camera's own format); replaced in tests. */
    private val clock: () -> String = { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) },
) {
    private data class Local(
        val loading: Boolean = false,
        val applying: Boolean = false,
        val rows: List<SettingRow> = emptyList(),
        val note: SettingsMessage? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<SettingsUiState> = combine(session.state, session.status, local) { link, status, own ->
        SettingsUiState(
            available = link is SessionState.Connected,
            loading = own.loading,
            applying = own.applying,
            cameraBusy = status.appStatus in CameraController.RECORDING_STATUSES ||
                status.appStatus in CameraController.CAPTURING_STATUSES,
            rows = own.rows,
            note = own.note,
        )
    }.stateIn(scope, SharingStarted.Eagerly, SettingsUiState())

    init {
        scope.launch {
            var wasConnected = false
            session.state.map { it is SessionState.Connected }.distinctUntilChanged().collect { connected ->
                // Only a lost link clears the rows; the first "not connected" must not wipe a note.
                if (!connected && wasConnected) local.update { Local() }
                wasConnected = connected
            }
        }
    }

    /** Reads the allowed values of every visible setting, one after the other. Call when the screen opens. */
    fun load() {
        if (local.value.loading || local.value.applying) return
        val api = session.currentApi
        val snapshot = session.settings.value
        if (api == null || snapshot == null) {
            local.update { it.copy(note = SettingsMessage(SettingsNote.NOT_CONNECTED)) }
            return
        }
        local.update { Local(loading = true) }
        scope.launch {
            val rows = ArrayList<SettingRow>()
            try {
                for ((key, value) in snapshot.values) {
                    if (!SettingsCatalog.isVisible(key)) continue
                    val options = readOptions(api, key) ?: continue
                    rows.add(SettingRow(key, SettingsCatalog.groupOf(key), options, value))
                    local.update { it.copy(rows = sorted(rows)) }
                }
                local.update { it.copy(loading = false, rows = sorted(rows)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(loading = false, note = SettingsMessage(SettingsNote.FAILED, message(e))) }
            }
        }
    }

    fun edit(key: String, value: String) {
        local.update { own ->
            own.copy(rows = own.rows.map { row ->
                if (row.key != key) row else row.copy(edited = value.takeIf { it != row.current })
            })
        }
    }

    fun discard() {
        local.update { own -> own.copy(rows = own.rows.map { it.copy(edited = null) }) }
    }

    fun dismissNote() {
        local.update { it.copy(note = null) }
    }

    /** Sends the changed settings, the video standard first; the ones the camera refuses stay marked as changed. */
    fun apply() {
        if (local.value.loading || local.value.applying) return
        val api = session.currentApi
        if (api == null) {
            local.update { it.copy(note = SettingsMessage(SettingsNote.NOT_CONNECTED)) }
            return
        }
        if (state.value.cameraBusy) {
            local.update { it.copy(note = SettingsMessage(SettingsNote.CAMERA_BUSY)) }
            return
        }
        val changed = local.value.rows.filter { it.edited != null }
            .sortedBy { if (it.key == SettingsCatalog.VIDEO_STANDARD) 0 else 1 }
        if (changed.isEmpty()) return
        local.update { it.copy(applying = true, note = null) }
        scope.launch {
            val failed = ArrayList<String>()
            val done = HashMap<String, String>()
            try {
                for (row in changed) {
                    val wanted = row.edited ?: continue
                    val reply = api.send(Cmd.SET_SETTING, listOf("type" to row.key, "param" to wanted))
                    if (reply.isOk) done[row.key] = wanted else failed.add("${row.key} (rval ${reply.rval})")
                }
                local.update { own ->
                    own.copy(rows = own.rows.map { row ->
                        val saved = done[row.key]
                        if (saved != null) row.copy(current = saved, edited = null) else row
                    })
                }
                if (SettingsCatalog.VIDEO_STANDARD in done) rereadDependentLists(api)
                session.requestSettingsRefresh()
                val note = when {
                    failed.isEmpty() -> SettingsMessage(SettingsNote.APPLIED)
                    done.isEmpty() -> SettingsMessage(SettingsNote.FAILED, failed.joinToString(", "))
                    else -> SettingsMessage(SettingsNote.PARTIAL, failed.joinToString(", "))
                }
                local.update { it.copy(applying = false, note = note) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(applying = false, note = SettingsMessage(SettingsNote.FAILED, message(e))) }
            }
        }
    }

    /** Sets the camera clock to the phone time (msg_id 2, type camera_clock; the camera's reply is not verified yet). */
    fun syncClock() {
        if (local.value.loading || local.value.applying) return
        val api = session.currentApi
        if (api == null) {
            local.update { it.copy(note = SettingsMessage(SettingsNote.NOT_CONNECTED)) }
            return
        }
        local.update { it.copy(applying = true, note = null) }
        scope.launch {
            val note = try {
                val reply = api.send(Cmd.SET_SETTING, listOf("type" to SettingsCatalog.CAMERA_CLOCK, "param" to clock()))
                if (reply.isOk) {
                    session.requestSettingsRefresh()
                    SettingsMessage(SettingsNote.CLOCK_SYNCED)
                } else {
                    SettingsMessage(SettingsNote.FAILED, "camera_clock (rval ${reply.rval})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SettingsMessage(SettingsNote.FAILED, message(e))
            }
            local.update { it.copy(applying = false, note = note) }
        }
    }

    // ---- internals ---------------------------------------------------------------------------

    /** The allowed values, or null if the setting is read only, free text or has nothing to choose from. */
    private suspend fun readOptions(api: CameraApi, key: String): List<String>? {
        val reply = api.send(Cmd.GET_SETTINGS, listOf("param" to key))
        val options = StatusParser.options(reply, key) ?: return null
        return options.values.takeIf { options.settable && it.size >= 2 }
    }

    /** After a video standard change the resolution lists are different, so they are read again. */
    private suspend fun rereadDependentLists(api: CameraApi) {
        for (key in SettingsCatalog.DEPENDS_ON_VIDEO_STANDARD) {
            val options = readOptions(api, key) ?: continue
            local.update { own ->
                own.copy(rows = own.rows.map { if (it.key == key) it.copy(options = options) else it })
            }
        }
    }

    private fun sorted(rows: List<SettingRow>): List<SettingRow> =
        rows.sortedWith(compareBy({ SettingsCatalog.sortIndex(it.key) }, { it.key }))

    private fun message(e: Exception): String = e.message ?: e.javaClass.simpleName
}
