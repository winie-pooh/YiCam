/**
 * Responsible for: the camera settings screen logic: opening the screen (leaving the preview state by itself with one new session, waiting while the camera records), asking the camera for the allowed values of each setting (msg_id 3 with param=key), marking the ones the camera locks at the moment, unlocking them by starting a new session (the camera leaves the preview then), keeping the user's unsaved choices, applying only the changed ones (video standard first, then re-reading the lists that depend on it) and setting the camera clock from the phone.
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One setting the camera lets us change. [edited] is the user's unsaved choice. */
data class SettingRow(
    val key: String,
    val group: SettingGroup,
    val options: List<String>,
    val current: String,
    val edited: String? = null,
    /** The camera lists values but does not allow a change now (it answers "readonly:" while the preview is on). */
    val locked: Boolean = false,
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
    /** The busy state is a video recording (the screen offers "Stop video recording"). */
    val recording: Boolean = false,
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
            recording = status.appStatus in CameraController.RECORDING_STATUSES,
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

    /** One automatic new session per visit: if the camera still reports the preview after it, the rows stay locked (the Unlock button remains). */
    @Volatile
    private var unlockTried = false

    /** Call once when the screen opens. */
    fun screenOpened() {
        unlockTried = false
        open()
    }

    /**
     * Gets the list ready without the user doing anything: while the camera records nothing is read (the screen offers to stop the
     * recording; when it stops this is called again); if the camera is in the preview state a new session ends it first; then the list is read.
     * Safe to call again at any time.
     */
    fun open() {
        if (local.value.loading || local.value.applying) return
        if (session.state.value !is SessionState.Connected) return
        if (state.value.cameraBusy) return
        if (state.value.changedCount > 0) return
        if (session.status.value.appStatus == PREVIEW_STATUS && !unlockTried) {
            unlockTried = true
            unlock()
        } else {
            load()
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
                    rows.add(SettingRow(key, SettingsCatalog.groupOf(key), options.values, value, locked = !options.settable))
                    local.update { it.copy(rows = sorted(rows)) }
                }
                for ((key, values) in SettingsCatalog.BUILT_IN_OPTIONS) {
                    val current = snapshot.values[key] ?: continue
                    if (rows.any { it.key == key }) continue
                    rows.add(SettingRow(key, SettingsCatalog.groupOf(key), values, current, locked = false))
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
                if (row.key != key || row.locked) row else row.copy(edited = value.takeIf { it != row.current })
            })
        }
    }

    fun discard() {
        local.update { own -> own.copy(rows = own.rows.map { it.copy(edited = null) }) }
    }

    /**
     * The camera locks some settings while the preview is on and leaves that state when a new session starts
     * (seen in the phone logs: every new session begins with app_status "idle"). Starts a new session and reads the list again.
     */
    fun unlock() {
        if (local.value.loading || local.value.applying) return
        val oldRead = session.settings.value?.readAtMs
        local.update { it.copy(applying = true, note = null) }
        scope.launch {
            try {
                session.connect()
                withTimeoutOrNull(UNLOCK_WAIT_MS) {
                    session.state.first { it is SessionState.Connected }
                    session.settings.first { it != null && it.readAtMs != oldRead }
                }
            } finally {
                local.update { it.copy(applying = false) }
            }
            load()
        }
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
        val changed = local.value.rows.filter { it.edited != null && !it.locked }
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

    /** The allowed values (settable or locked), or null if the setting is free text or has nothing to choose from. */
    private suspend fun readOptions(api: CameraApi, key: String): StatusParser.Options? {
        val reply = api.send(Cmd.GET_SETTINGS, listOf("param" to key))
        val options = StatusParser.options(reply, key) ?: return null
        return options.takeIf { it.values.size >= 2 }
    }

    /** After a video standard change the resolution lists are different, so they are read again. */
    private suspend fun rereadDependentLists(api: CameraApi) {
        for (key in SettingsCatalog.DEPENDS_ON_VIDEO_STANDARD) {
            val options = readOptions(api, key) ?: continue
            local.update { own ->
                own.copy(rows = own.rows.map { if (it.key == key) it.copy(options = options.values, locked = !options.settable) else it })
            }
        }
    }

    private fun sorted(rows: List<SettingRow>): List<SettingRow> =
        rows.sortedWith(compareBy({ SettingsCatalog.sortIndex(it.key) }, { it.key }))

    private fun message(e: Exception): String = e.message ?: e.javaClass.simpleName

    private companion object {
        /** app_status while the preview is on (the camera then locks some settings). */
        const val PREVIEW_STATUS = "vf"
        const val UNLOCK_WAIT_MS = 15_000L
    }
}
