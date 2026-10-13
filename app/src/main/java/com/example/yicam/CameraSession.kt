/**
 * Responsible for: the life of the link to the camera: finding the Wi-Fi, opening the session, reading and refreshing the camera status and settings, noticing a lost link and reconnecting with growing delays, pausing in the background; it publishes SessionState, CameraStatus and SettingsSnapshot.
 * Not responsible for: sockets and the protocol (CameraConnection.kt, CameraApi.kt), Android networking (WifiBinder.kt), drawing anything (UI files) or creating the app-wide instance (SessionProvider.kt).
 */
package com.example.yicam

import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CameraSession(
    private val network: CameraNetwork,
    val log: TrafficLog,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
    private val apiFactory: (SocketFactory) -> CameraApi = { factory ->
        CameraApi(CameraConnection(config.host, config.port, factory, scope, log), log, autoReconnect = false)
    },
) {
    /** Times are in milliseconds. Tests use small values. */
    data class Config(
        val host: String = CameraConnection.DEFAULT_HOST,
        val port: Int = CameraConnection.DEFAULT_PORT,
        val pollBatteryMs: Long = 20_000L,
        val pollSdMs: Long = 60_000L,
        /** How often the SD card is read again while its size looks wrong or is unknown. */
        val sdRecheckMs: Long = 5_000L,
        /** After this many quick re-checks the normal interval is used again (a card slot may really be empty). */
        val sdQuickChecks: Int = 12,
        /** A card smaller than this (in KB) is not believed: the camera has been seen reporting 17490 KB for a card slot. */
        val minPlausibleSdKb: Long = 524_288L,
        /** How long one attempt waits for the camera Wi-Fi to appear. */
        val wifiTimeoutMs: Long = 5_000L,
        /** If the camera Wi-Fi is gone for this long after a loss, the state becomes CameraOff. */
        val wifiGraceMs: Long = 5_000L,
        /** While the camera is off, one attempt waits this long for the Wi-Fi (it reacts at once when the Wi-Fi returns). */
        val cameraOffRetryMs: Long = 30_000L,
        /** Smallest pause between two attempts while the camera is off, so a failing attempt can never spin. */
        val minAttemptGapMs: Long = 1_000L,
        /** After a camera event (record started, mode switched, ...) the settings are read again, so app_status is not stale. */
        val eventRefreshDelayMs: Long = 700L,
        val backoff: Backoff = Backoff(),
    )

    private val _state = MutableStateFlow<SessionState>(SessionState.Disconnected)
    private val _status = MutableStateFlow(CameraStatus())
    private val _settings = MutableStateFlow<SettingsSnapshot?>(null)
    private val foreground = MutableStateFlow(true)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val settingsRequests = Channel<Unit>(Channel.CONFLATED)
    private val consecutiveTimeouts = AtomicInteger(0)

    @Volatile
    private var runJob: Job? = null

    @Volatile
    private var api: CameraApi? = null

    private val _cameraEvents = MutableSharedFlow<CameraMessage.Event>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every message the camera sends on its own (msg_id 7), for screens that must react to them (the live view). */
    val cameraEvents: SharedFlow<CameraMessage.Event> = _cameraEvents.asSharedFlow()

    val state: StateFlow<SessionState> = _state.asStateFlow()
    val status: StateFlow<CameraStatus> = _status.asStateFlow()
    val settings: StateFlow<SettingsSnapshot?> = _settings.asStateFlow()

    /** The API of the current connection, or null when there is none. Used by the probe screen for single commands. */
    val currentApi: CameraApi? get() = api

    // ---- what the UI can ask for ---------------------------------------------------------------

    /** Starts (or restarts) the link. The caller has already made sure the local network permission is granted. */
    fun connect() {
        runJob?.cancel()
        closeApi()
        _state.value = SessionState.SearchingWifi
        runJob = scope.launch { runLoop() }
    }

    /** Stops on purpose: no more attempts, the camera Wi-Fi is released, the known status is cleared. */
    fun disconnect() {
        runJob?.cancel()
        runJob = null
        closeApi()
        network.release()
        _status.value = CameraStatus()
        _settings.value = null
        _state.value = SessionState.Disconnected
        log.sys("Disconnected; the app no longer uses the camera Wi-Fi")
    }

    /** The user refused the local network permission: stop and wait for the user to allow it. */
    fun onPermissionDenied() {
        runJob?.cancel()
        runJob = null
        closeApi()
        network.release()
        _state.value = SessionState.PermissionRequired
        log.sys("[ERR] Local network permission was denied; the camera cannot be reached without it")
    }

    /** Reads settings, battery and SD memory again now. Does nothing when not connected. */
    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    /** False while the app is in the background: polling and reconnect attempts wait until it is true again. */
    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    // ---- the connection loop ---------------------------------------------------------------------

    private suspend fun runLoop() {
        var attempt = 0
        var everConnected = false
        var lostAtMs: Long? = null
        while (currentCoroutineContext().isActive) {
            awaitForeground()
            var reason = FailReason.CONNECTION_LOST
            var detail = ""
            var retry = true
            val attemptStartedMs = nowMs()
            try {
                // Keep "camera off" on screen while retrying; other waiting states become "connecting".
                val shown = _state.value
                if (attempt == 0) {
                    _state.value = SessionState.SearchingWifi
                } else if (shown is SessionState.Reconnecting) {
                    _state.value = SessionState.Connecting
                }
                val bindTimeoutMs = if (shown == SessionState.CameraOff) config.cameraOffRetryMs else config.wifiTimeoutMs
                val factory = network.bind(bindTimeoutMs)
                if (_state.value != SessionState.CameraOff) _state.value = SessionState.Connecting
                val newApi = apiFactory(factory)
                api = newApi
                try {
                    newApi.connect()
                } catch (e: Exception) {
                    newApi.disconnect()
                    if (api === newApi) api = null
                    throw e
                }
                attempt = 0
                everConnected = true
                lostAtMs = null
                reason = monitor(newApi)
                newApi.disconnect()
                if (api === newApi) api = null
                lostAtMs = nowMs()
                detail = "The link to the camera was lost"
                log.sys("[SESSION] link lost: ${reason.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reason = classify(e)
                detail = e.message ?: e.javaClass.simpleName
                retry = isRetryable(reason, everConnected)
                log.sys("[SESSION] attempt failed: ${reason.name}: $detail")
            }

            if (!retry) {
                _state.value = SessionState.Error(reason, detail)
                return
            }
            attempt++
            val lostFor = lostAtMs?.let { nowMs() - it }
            val cameraOff = reason == FailReason.NO_WIFI && lostFor != null && lostFor >= config.wifiGraceMs
            val waitMs = if (cameraOff) {
                // The next attempt itself waits up to cameraOffRetryMs for the Wi-Fi and reacts at once when it appears,
                // so after a long attempt only a short pause is needed. A failure that returned at once must not spin.
                if (nowMs() - attemptStartedMs < config.minAttemptGapMs) config.cameraOffRetryMs else config.minAttemptGapMs
            } else {
                config.backoff.delayMs(attempt)
            }
            _state.value = if (cameraOff) SessionState.CameraOff else SessionState.Reconnecting(attempt, waitMs, reason)
            log.sys("[SESSION] next attempt ($attempt) in $waitMs ms${if (cameraOff) " (camera is off or out of range)" else ""}")
            delay(waitMs)
        }
    }

    /** Runs while the link is up. Returns the reason as soon as the link is lost. */
    private suspend fun monitor(connected: CameraApi): FailReason = coroutineScope {
        val lost = CompletableDeferred<FailReason>()
        consecutiveTimeouts.set(0)
        val jobs = ArrayList<Job>()
        jobs += launch(start = CoroutineStart.UNDISPATCHED) {
            connected.state.collect { s ->
                if (s is ConnectionState.Connected) {
                    _state.value = SessionState.Connected(s.token)
                } else if (s is ConnectionState.Failed || s == ConnectionState.Disconnected) {
                    lost.complete(FailReason.CONNECTION_LOST)
                }
            }
        }
        jobs += launch(start = CoroutineStart.UNDISPATCHED) {
            network.lost.first()
            log.sys("[SESSION] the camera Wi-Fi network was lost")
            lost.complete(FailReason.NO_WIFI)
        }
        jobs += launch(start = CoroutineStart.UNDISPATCHED) {
            connected.events.collect { event -> onEvent(event) }
        }
        jobs += launch {
            for (request in refreshRequests) {
                awaitForeground()
                guarded(lost, "refresh") { readAll(connected) }
            }
        }
        jobs += launch {
            for (request in settingsRequests) {
                delay(config.eventRefreshDelayMs)
                awaitForeground()
                guarded(lost, "settings refresh") { readSettings(connected) }
            }
        }
        jobs += launch { loopEvery(config.pollBatteryMs) { guarded(lost, "battery poll") { readBattery(connected) } } }
        jobs += launch {
            var quickChecks = 0
            while (true) {
                val unsure = _status.value.sdTotalKb == null
                quickChecks = if (unsure) quickChecks + 1 else 0
                delay(if (unsure && quickChecks <= config.sdQuickChecks) config.sdRecheckMs else config.pollSdMs)
                awaitForeground()
                guarded(lost, "SD poll") { readSd(connected) }
            }
        }
        refreshRequests.trySend(Unit) // the first full read

        val reason = lost.await()
        jobs.forEach { it.cancel() }
        reason
    }

    /** Runs [block]; a lost link or a second timeout in a row completes [lost], other problems are logged. */
    private suspend fun guarded(lost: CompletableDeferred<FailReason>, label: String, block: suspend () -> Unit) {
        try {
            block()
            consecutiveTimeouts.set(0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CameraException.Timeout) {
            if (consecutiveTimeouts.incrementAndGet() >= 2) {
                log.sys("[SESSION] $label: second timeout in a row, the link is treated as lost")
                lost.complete(FailReason.TIMEOUT)
            } else {
                log.sys("[WARN] $label: no reply in time, will try again")
            }
        } catch (e: CameraException) {
            log.sys("[SESSION] $label: ${e.message}")
            lost.complete(FailReason.CONNECTION_LOST)
        } catch (e: Exception) {
            log.sys("[ERR] $label: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun loopEvery(intervalMs: Long, action: suspend () -> Unit) {
        while (true) {
            delay(intervalMs)
            awaitForeground()
            action()
        }
    }

    // ---- reading the camera -------------------------------------------------------------------------

    private suspend fun readAll(connected: CameraApi) {
        readSettings(connected)
        readBattery(connected)
        readSd(connected)
    }

    private suspend fun readSettings(connected: CameraApi) {
        val reply = connected.send(Cmd.GET_SETTINGS)
        if (!reply.isOk) {
            log.sys("[WARN] settings: the camera answered rval ${reply.rval}")
            return
        }
        val values = StatusParser.settings(reply)
        _settings.value = SettingsSnapshot(values, System.currentTimeMillis())
        _status.update {
            it.copy(
                appStatus = values["app_status"] ?: it.appStatus,
                sdCardStatus = values["sd_card_status"] ?: it.sdCardStatus,
            )
        }
    }

    private suspend fun readBattery(connected: CameraApi) {
        val reply = connected.send(Cmd.BATTERY)
        if (!reply.isOk) {
            log.sys("[WARN] battery: the camera answered rval ${reply.rval}")
            return
        }
        val battery = StatusParser.battery(reply)
        _status.update {
            it.copy(batteryPercent = battery.percent ?: it.batteryPercent, charging = battery.charging ?: it.charging)
        }
    }

    /** Reads total and free size. A tiny total is not believed (see Config.minPlausibleSdKb): the values are dropped and the problem is remembered. */
    private suspend fun readSd(connected: CameraApi) {
        val total = readSdKb(connected, "total")
        val free = readSdKb(connected, "free")
        if (total != null && total < config.minPlausibleSdKb) {
            if (_status.value.sdUnusableReportedKb != total) {
                log.sys("[WARN] SD memory: the camera reports only $total KB in total (free ${free ?: "?"} KB); this is not a usable card, will check again")
            }
            _status.update { it.copy(sdTotalKb = null, sdFreeKb = null, sdUnusableReportedKb = total) }
        } else {
            _status.update {
                it.copy(sdTotalKb = total ?: it.sdTotalKb, sdFreeKb = free ?: it.sdFreeKb, sdUnusableReportedKb = null)
            }
        }
    }

    private suspend fun readSdKb(connected: CameraApi, type: String): Long? {
        val reply = connected.send(Cmd.SD_MEMORY, listOf("type" to type))
        if (!reply.isOk) {
            log.sys("[WARN] SD memory ($type): the camera answered rval ${reply.rval}")
            return null
        }
        return StatusParser.kilobytes(reply)
    }

    private fun onEvent(event: CameraMessage.Event) {
        _cameraEvents.tryEmit(event)
        if (event.type == "battery") {
            val percent = StatusParser.percentOf(event.param)
            if (percent != null) _status.update { it.copy(batteryPercent = percent) }
        } else {
            // Seen: start_video_record, vf_stop, video_record_complete, vf_start, switch_to_cap_mode, switch_to_rec_mode.
            settingsRequests.trySend(Unit)
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private suspend fun awaitForeground() {
        foreground.first { it }
    }

    private fun closeApi() {
        api?.disconnect()
        api = null
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private fun classify(e: Exception): FailReason = when {
        e is CameraException.WifiNotFound ->
            if (e.otherWifiSeen) FailReason.NOT_CAMERA_NETWORK else FailReason.NO_WIFI
        looksLikePermissionError(e) -> FailReason.PERMISSION_DENIED
        e is CameraException.Timeout -> FailReason.TIMEOUT
        e is CameraException.Rejected -> FailReason.PROTOCOL
        else -> FailReason.CONNECTION_LOST
    }

    /** Without the Android 17 local network permission a socket may fail with an "EPERM"-like error. The exact text is not yet seen on a phone. */
    private fun looksLikePermissionError(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any {
            val text = it.message.orEmpty()
            it is SecurityException ||
                text.contains("permission denied", ignoreCase = true) ||
                text.contains("EPERM") ||
                text.contains("EACCES")
        }

    private fun isRetryable(reason: FailReason, everConnected: Boolean): Boolean = when (reason) {
        FailReason.PERMISSION_DENIED -> false
        // The user pressed Connect without being on the camera Wi-Fi: tell them instead of retrying silently.
        // After a loss the phone often falls back to another Wi-Fi (seen on a phone): keep waiting for the camera.
        FailReason.NO_WIFI, FailReason.NOT_CAMERA_NETWORK -> everConnected
        else -> true
    }
}
