/**
 * Responsible for: the actions behind the probe screen (dev tool): canned probe commands (including the settings options dump), the RTSP trace (RtspProbe.kt), the guarded delete probe (candidate msg_id 1281), raw commands, log access, connect/disconnect forwarded to the session. Survives screen rotation.
 * Not responsible for: drawing the screen or asking for permissions (MainActivity.kt, ProbeScreen.kt), the link to the camera (CameraSession.kt) or the protocol (CameraApi.kt).
 */
package com.example.yicam

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProbeController private constructor(private val session: CameraSession) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val log: TrafficLog get() = session.log
    val state: StateFlow<SessionState> get() = session.state

    fun connect() = session.connect()

    fun disconnect() = session.disconnect()

    fun clearLog() = log.clear()

    // ---- the probe buttons -------------------------------------------------------------------

    fun settings() = launchCommand { it.send(Cmd.GET_SETTINGS) }

    fun battery() = launchCommand { it.send(Cmd.BATTERY) }

    fun startPreview() = launchCommand { it.send(Cmd.START_PREVIEW, listOf("param" to "none_force")) }

    fun changeToDcim() = launchCommand { it.send(Cmd.CHANGE_DIR, listOf("param" to "/tmp/fuse_d/DCIM")) }

    fun listFiles() = launchCommand { it.send(Cmd.LIST_FILES, listOf("param" to "-D -S")) }

    /** Asks the camera for the possible values of every setting (msg_id 3 + param=key); the replies go to the log. Used to learn the reply format. */
    fun dumpOptions() {
        val keys = session.settings.value?.values?.keys?.toList().orEmpty()
        launchCommand { api ->
            if (keys.isEmpty()) log.sys("[ERR] No settings yet. Press Settings first.")
            for (key in keys) {
                if (key in SECRET_OPTION_KEYS) continue
                api.send(Cmd.GET_SETTINGS, listOf("param" to key))
            }
        }
    }

    fun sendRaw(json: String) = launchCommand { it.sendRaw(json) }

    /**
     * Tries the unconfirmed delete candidate (msg_id 1281, absolute path) then lists the parent folder.
     * [VERIFY] on a sacrificial photo; the full reply is written to the log.
     */
    fun probeDelete(absolutePath: String) {
        val path = absolutePath.trim()
        if (path.isEmpty()) {
            log.sys("[DELETE-PROBE] empty path")
            return
        }
        launchCommand { api ->
            log.sys("[DELETE-PROBE] candidate msg_id $DELETE_CANDIDATE_MSG_ID (not confirmed). path=$path")
            val reply = api.send(DELETE_CANDIDATE_MSG_ID, listOf("param" to path))
            log.sys("[DELETE-PROBE] rval=${reply.rval} raw=${reply.raw}")
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) {
                api.send(Cmd.CHANGE_DIR, listOf("param" to parent))
            }
            api.send(Cmd.LIST_FILES, listOf("param" to ListingParser.LIST_PARAM))
            log.sys("[DELETE-PROBE] listed the parent folder after the attempt; check whether the file is gone")
        }
    }

    /** Starts the preview, then writes a full RTSP conversation with the camera to the log (see RtspProbe). */
    fun rtspTrace() {
        val current = session.currentApi
        scope.launch(Dispatchers.IO) {
            if (current != null) {
                try {
                    current.send(Cmd.START_PREVIEW, listOf("param" to "none_force"))
                    delay(1_500L)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.sys("[ERR] preview command: ${e.message ?: e.javaClass.simpleName}")
                }
            } else {
                log.sys("[RTSP] not connected: the preview was not started, the trace will probably fail")
            }
            RtspProbe.run(CameraConnection.DEFAULT_HOST, RTSP_PORT, "/live", log)
        }
    }

    /** Runs one command in the background on the current connection; the reply and any error end up in the log. */
    private fun launchCommand(action: suspend (CameraApi) -> Unit) {
        val current = session.currentApi
        if (current == null) {
            log.sys("[ERR] Not connected. Press Connect first.")
            return
        }
        scope.launch {
            try {
                action(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.sys("[ERR] ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val RTSP_PORT = 554
        /** Guess from the original API; not seen on this camera yet. */
        const val DELETE_CANDIDATE_MSG_ID = 1281
        private val SECRET_OPTION_KEYS = setOf("wifi_password", "serial_number")

        @Volatile
        private var instance: ProbeController? = null

        /** One controller for the whole process, so it survives screen rotation. */
        fun get(context: Context): ProbeController =
            instance ?: synchronized(this) {
                instance ?: ProbeController(SessionProvider.get(context)).also { instance = it }
            }
    }
}
