/**
 * Responsible for: the camera controls: photo, video start/stop, time-lapse stop, photo/video mode, capture mode and zoom (with a minimum gap between zoom commands); it publishes ControlState derived from the session status, settings and events.
 * Not responsible for: the link and polling (CameraSession.kt), the protocol (CameraApi.kt), drawing (ControlPanel.kt) or the settings list (CameraSettings.kt).
 */
package com.example.yicam

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CameraController(
    private val session: CameraSession,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
) {
    data class Config(
        /** Smallest pause between two zoom commands. The camera's limit is not known yet (plan: VERIFY). */
        val zoomMinGapMs: Long = 150L,
    )

    private class Local(
        val busy: Boolean = false,
        val note: ControlMessage? = null,
        val zoom: Int? = null,
    )

    private val local = MutableStateFlow(Local())
    private val zoomRequests = Channel<Int>(Channel.CONFLATED)

    val state: StateFlow<ControlState> = combine(session.state, session.status, session.settings, local) { link, status, snapshot, own ->
        val values = snapshot?.values.orEmpty()
        val appStatus = status.appStatus
        ControlState(
            available = link is SessionState.Connected,
            mode = CameraMode.fromValue(values["system_mode"]),
            captureMode = CaptureMode.fromValue(values["capture_mode"]),
            recording = appStatus == APP_RECORDING,
            timelapseRunning = values["precise_cont_capturing"] == "on" || appStatus in CAPTURING_STATUSES,
            zoom = own.zoom,
            busy = own.busy,
            note = own.note,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ControlState())

    init {
        scope.launch {
            session.state.map { it is SessionState.Connected }.distinctUntilChanged().collect { connected ->
                if (connected) readZoom() else local.update { Local(zoom = null) }
            }
        }
        scope.launch {
            session.cameraEvents.collect { event -> onEvent(event) }
        }
        scope.launch {
            for (level in zoomRequests) {
                sendZoom(level)
                delay(config.zoomMinGapMs)
            }
        }
    }

    // ---- what the screen can ask for ---------------------------------------------------------

    fun takePhoto() = command(ControlNote.PHOTO_REQUESTED) { it.send(Cmd.TAKE_PHOTO) }

    fun startRecording() = command(ControlNote.RECORDING_STARTED) { it.send(Cmd.START_RECORD) }

    fun stopRecording() = command(ControlNote.RECORDING_STOPPED) { it.send(Cmd.STOP_RECORD) }

    fun stopTimelapse() = command(ControlNote.TIMELAPSE_STOPPED) { it.send(Cmd.STOP_TIMELAPSE) }

    fun setMode(mode: CameraMode) = command(ControlNote.MODE_CHANGED) {
        it.send(Cmd.SET_SETTING, listOf("type" to "system_mode", "param" to mode.value))
    }

    fun setCaptureMode(mode: CaptureMode) = command(ControlNote.MODE_CHANGED) {
        it.send(Cmd.SET_SETTING, listOf("type" to "capture_mode", "param" to mode.value))
    }

    /** Remembers the wanted zoom (0..100) at once; the camera gets the latest value, at most one command per zoomMinGapMs. */
    fun setZoom(level: Int) {
        val value = level.coerceIn(0, 100)
        local.update { Local(it.busy, it.note, value) }
        zoomRequests.trySend(value)
    }

    fun dismissNote() {
        local.update { Local(it.busy, null, it.zoom) }
    }

    // ---- internals -------------------------------------------------------------------------------

    /** Runs one command unless another one is waiting; puts the outcome into the note. */
    private fun command(success: ControlNote, block: suspend (CameraApi) -> CameraMessage.Response) {
        if (local.value.busy) return
        local.update { Local(true, null, it.zoom) }
        scope.launch {
            var note: ControlMessage
            try {
                val api = session.currentApi
                note = if (api == null) {
                    ControlMessage(ControlNote.NOT_CONNECTED)
                } else {
                    val reply = block(api)
                    if (reply.isOk) {
                        session.requestSettingsRefresh()
                        ControlMessage(success)
                    } else {
                        ControlMessage(ControlNote.FAILED, "rval ${reply.rval}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = ControlMessage(ControlNote.FAILED, e.message ?: e.javaClass.simpleName)
            }
            local.update { Local(false, note, it.zoom) }
        }
    }

    private suspend fun readZoom() {
        try {
            val api = session.currentApi ?: return
            val reply = api.send(Cmd.GET_ZOOM, listOf("type" to "current"))
            val level = reply.paramLong()?.toInt()
            // A value the user already chose is newer than the camera's answer.
            if (reply.isOk && level != null) local.update { Local(it.busy, it.note, it.zoom ?: level.coerceIn(0, 100)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The zoom position is only informative; the log already has the reason.
        }
    }

    private suspend fun sendZoom(level: Int) {
        try {
            val api = session.currentApi ?: return
            val reply = api.send(Cmd.ZOOM, listOf("type" to "fast", "param" to level.toString()))
            if (!reply.isOk) local.update { Local(it.busy, ControlMessage(ControlNote.FAILED, "zoom: rval ${reply.rval}"), it.zoom) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            local.update { Local(it.busy, ControlMessage(ControlNote.FAILED, e.message ?: e.javaClass.simpleName), it.zoom) }
        }
    }

    private fun onEvent(event: CameraMessage.Event) {
        when (event.type) {
            // param is the file path of the finished video, e.g. /tmp/fuse_d/DCIM/101MEDIA/YDXJ0001.mp4
            "video_record_complete" -> {
                val name = (event.param as? String)?.substringAfterLast('/').orEmpty()
                local.update { Local(it.busy, ControlMessage(ControlNote.SAVED, name), it.zoom) }
            }
            "precise_cont_complete" ->
                local.update { Local(it.busy, ControlMessage(ControlNote.TIMELAPSE_DONE), it.zoom) }
        }
    }

    private companion object {
        const val APP_RECORDING = "recording"
        val CAPTURING_STATUSES = setOf("precise_cont_capturing", "burst_capturing", "precise_capturing")
    }
}
