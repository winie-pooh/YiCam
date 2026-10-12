/**
 * Responsible for: the actions behind the probe screen (dev tool): canned probe commands, raw commands, log access, connect/disconnect forwarded to the session. Survives screen rotation.
 * Not responsible for: drawing the screen or asking for permissions (MainActivity.kt, ProbeScreen.kt), the link to the camera (CameraSession.kt) or the protocol (CameraApi.kt).
 */
package com.example.yicam

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    fun sendRaw(json: String) = launchCommand { it.sendRaw(json) }

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
        @Volatile
        private var instance: ProbeController? = null

        /** One controller for the whole process, so it survives screen rotation. */
        fun get(context: Context): ProbeController =
            instance ?: synchronized(this) {
                instance ?: ProbeController(SessionProvider.get(context)).also { instance = it }
            }
    }
}
