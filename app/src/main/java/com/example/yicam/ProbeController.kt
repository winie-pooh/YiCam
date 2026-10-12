/**
 * Responsible for: the state and actions behind the v0.0.1/0.0.2 probe screen: connect via Wi-Fi binding, canned probe commands, raw commands, log access. Survives screen rotation.
 * Not responsible for: drawing the screen or asking for permissions (MainActivity.kt) or the protocol itself (CameraApi.kt).
 */
package com.example.yicam

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProbeController private constructor(context: Context) {
    val log = TrafficLog()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wifi = WifiBinder(context, log)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _localNetworkDenied = MutableStateFlow(false)

    @Volatile
    private var api: CameraApi? = null
    private var connectJob: Job? = null
    private var stateJob: Job? = null

    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** True after the user refused the local network permission (Android 17+). */
    val localNetworkDenied: StateFlow<Boolean> = _localNetworkDenied.asStateFlow()

    /** Finds the camera Wi-Fi, opens the TCP connection and the session. Pressing it again reconnects from scratch. */
    fun connect() {
        _localNetworkDenied.value = false
        connectJob?.cancel()
        connectJob = scope.launch {
            _state.value = ConnectionState.Connecting
            try {
                api?.disconnect()
                log.sys("Looking for the camera Wi-Fi (phone must already be connected to it)...")
                val network = wifi.bind()
                val connection = CameraConnection(
                    CameraConnection.DEFAULT_HOST,
                    CameraConnection.DEFAULT_PORT,
                    network.socketFactory,
                    scope,
                    log,
                )
                val newApi = CameraApi(connection, log)
                api = newApi
                stateJob?.cancel()
                stateJob = scope.launch { newApi.state.collect { _state.value = it } }
                newApi.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                log.sys("[ERR] $reason")
                if (_state.value !is ConnectionState.Failed) _state.value = ConnectionState.Failed(reason)
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        api?.disconnect()
        wifi.release()
        _state.value = ConnectionState.Disconnected
        log.sys("Disconnected; the app no longer uses the camera Wi-Fi")
    }

    fun onLocalNetworkDenied() {
        _localNetworkDenied.value = true
        log.sys("[ERR] Local network permission was denied; the camera cannot be reached without it")
    }

    fun clearLog() = log.clear()

    // ---- the probe buttons -------------------------------------------------------------------

    fun settings() = launchCommand { it.send(Cmd.GET_SETTINGS) }

    fun battery() = launchCommand { it.send(Cmd.BATTERY) }

    fun startPreview() = launchCommand { it.send(Cmd.START_PREVIEW, listOf("param" to "none_force")) }

    fun changeToDcim() = launchCommand { it.send(Cmd.CHANGE_DIR, listOf("param" to "/tmp/fuse_d/DCIM")) }

    fun listFiles() = launchCommand { it.send(Cmd.LIST_FILES, listOf("param" to "-D -S")) }

    fun sendRaw(json: String) = launchCommand { it.sendRaw(json) }

    /** Runs one command in the background; the reply and any error end up in the log. */
    private fun launchCommand(action: suspend (CameraApi) -> Unit) {
        val current = api
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

        /** One controller for the whole process, so the log and the connection survive screen rotation. */
        fun get(context: Context): ProbeController =
            instance ?: synchronized(this) {
                instance ?: ProbeController(context.applicationContext).also { instance = it }
            }
    }
}
