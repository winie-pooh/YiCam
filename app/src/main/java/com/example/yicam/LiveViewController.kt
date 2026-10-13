/**
 * Responsible for: the live view logic: checking that a preview is possible, starting it on the camera (msg_id 259), starting the player on the RTSP stream, waiting for the first picture, retrying, and restarting a stream that breaks.
 * Not responsible for: the video decoding itself (StreamPlayer.kt, ExoRtspPlayer.kt), drawing (LiveScreen.kt) or the link to the camera (CameraSession.kt).
 */
package com.example.yicam

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Use from the main thread: [scope] must run on it, because the player must be called from there. */
class LiveViewController(
    private val session: CameraSession,
    private val player: StreamPlayer,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
) {
    data class Config(
        val rtspUrl: String = "rtsp://192.168.42.1:554/live",
        /** Tries in a row before giving up (a stream that played and then broke starts counting again). */
        val maxStartAttempts: Int = 2,
        val retryDelayMs: Long = 1_500L,
        val firstFrameTimeoutMs: Long = 12_000L,
    )

    private sealed interface StreamEvent {
        data object FirstFrame : StreamEvent

        data class Failed(val message: String) : StreamEvent
    }

    private val _state = MutableStateFlow<LiveState>(LiveState.Idle)
    val state: StateFlow<LiveState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    /** Starts (or restarts) the live view. */
    fun start() {
        job?.cancel()
        player.stop()
        job = scope.launch { run() }
    }

    /** Stops the player and returns to Idle. The camera keeps its preview running (no command to stop it is known yet). */
    fun stop() {
        job?.cancel()
        job = null
        player.stop()
        _state.value = LiveState.Idle
    }

    private suspend fun run() {
        val blocked = blockingProblem()
        if (blocked != null) {
            log("not started: ${blocked.name}")
            _state.value = LiveState.Problem(blocked, "")
            return
        }
        var attempt = 0
        while (true) {
            attempt++
            val startedMs = nowMs()
            _state.value = LiveState.StartingPreview
            val api = session.currentApi
            if (api == null) {
                _state.value = LiveState.Problem(LiveProblem.NOT_CONNECTED, "")
                return
            }
            val reply = try {
                api.send(Cmd.START_PREVIEW, listOf("param" to "none_force"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(LiveProblem.PREVIEW_COMMAND_FAILED, e.message ?: e.javaClass.simpleName)
                return
            }
            if (!reply.isOk) {
                fail(LiveProblem.PREVIEW_COMMAND_FAILED, "the camera answered rval ${reply.rval}")
                return
            }

            _state.value = LiveState.ConnectingStream
            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            player.start(
                config.rtspUrl,
                object : StreamPlayer.Listener {
                    override fun onFirstFrame() {
                        events.trySend(StreamEvent.FirstFrame)
                    }

                    override fun onError(message: String) {
                        events.trySend(StreamEvent.Failed(message))
                    }
                },
            )

            val first = withTimeoutOrNull(config.firstFrameTimeoutMs) { events.receive() }
            var failure = ""
            var timedOut = false
            when (first) {
                StreamEvent.FirstFrame -> {
                    val tookMs = nowMs() - startedMs
                    log("first picture after $tookMs ms (preview command included)")
                    _state.value = LiveState.Playing(tookMs)
                    attempt = 0
                    // Wait here until the stream breaks (or the job is cancelled by stop()).
                    val next = events.receive()
                    failure = (next as? StreamEvent.Failed)?.message ?: "the stream ended"
                }
                is StreamEvent.Failed -> failure = first.message
                null -> {
                    timedOut = true
                    failure = "no picture within ${config.firstFrameTimeoutMs} ms"
                }
            }
            player.stop()
            log("stream stopped: $failure")
            if (attempt >= config.maxStartAttempts) {
                fail(if (timedOut) LiveProblem.STREAM_TIMEOUT else LiveProblem.STREAM_FAILED, failure)
                return
            }
            delay(config.retryDelayMs)
        }
    }

    private fun blockingProblem(): LiveProblem? {
        if (session.state.value !is SessionState.Connected || session.currentApi == null) return LiveProblem.NOT_CONNECTED
        if (session.status.value.appStatus == "recording") return LiveProblem.RECORDING
        if (session.settings.value?.values?.get("preview_status") == "off") return LiveProblem.PREVIEW_DISABLED
        return null
    }

    private fun fail(problem: LiveProblem, detail: String) {
        log("failed: ${problem.name}: $detail")
        _state.value = LiveState.Problem(problem, detail)
    }

    private fun log(text: String) = session.log.sys("[LIVE] $text")

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
