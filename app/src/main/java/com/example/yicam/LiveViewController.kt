/**
 * Responsible for: the live view logic: checking that a preview is possible, starting it on the camera (msg_id 259), starting the player on the RTSP stream (UDP: the camera refuses TCP), waiting for the first picture, noticing a frozen or ended stream or a lost link, pausing when the camera starts recording and resuming after it, retrying, and restarting a stream that breaks.
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
import kotlinx.coroutines.flow.first
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
        /** The camera answers "SETUP 461" to RTP over TCP (seen on a phone), so UDP is the default. */
        val useTcp: Boolean = false,
        /** Tries in a row before giving up (a stream that played and then broke starts counting again). */
        val maxStartAttempts: Int = 2,
        val retryDelayMs: Long = 1_500L,
        val firstFrameTimeoutMs: Long = 12_000L,
        /** The picture counts as frozen when the player buffers this long without data. */
        val stallTimeoutMs: Long = 5_000L,
        /** After the camera stopped its preview (recording), how long to wait for it to come back before giving up. */
        val resumeWaitMs: Long = 300_000L,
    )

    private sealed interface StreamEvent {
        data object FirstFrame : StreamEvent

        data object Buffering : StreamEvent

        data object Ready : StreamEvent

        data object Ended : StreamEvent

        data object LinkLost : StreamEvent

        /** The camera ended its preview (vf_stop) or started recording: the RTSP session is dead and must be closed. */
        data object PreviewStopped : StreamEvent

        data class Failed(val message: String) : StreamEvent
    }

    private class Outcome(
        val message: String,
        val timedOut: Boolean,
        val linkLost: Boolean,
        val played: Boolean,
        val previewStopped: Boolean = false,
    )

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
                if (reply.rval == RVAL_BUSY) {
                    // Seen on a phone: -21 came every time the camera was recording a video.
                    fail(LiveProblem.RECORDING, "the camera answered rval $RVAL_BUSY (busy; it was recording when this was seen)")
                } else {
                    fail(LiveProblem.PREVIEW_COMMAND_FAILED, "the camera answered rval ${reply.rval}")
                }
                return
            }

            _state.value = LiveState.ConnectingStream
            val events = Channel<StreamEvent>(Channel.UNLIMITED)
            val linkWatcher = scope.launch {
                session.state.first { it !is SessionState.Connected }
                events.trySend(StreamEvent.LinkLost)
            }
            val eventWatcher = scope.launch {
                session.cameraEvents.collect { event ->
                    if (event.type == "vf_stop" || event.type == "start_video_record") {
                        events.trySend(StreamEvent.PreviewStopped)
                    }
                }
            }
            val outcome = try {
                log("starting the stream (attempt $attempt, ${if (config.useTcp) "TCP" else "UDP"})")
                player.start(
                    config.rtspUrl,
                    object : StreamPlayer.Listener {
                        override fun onFirstFrame() {
                            events.trySend(StreamEvent.FirstFrame)
                        }

                        override fun onBuffering(buffering: Boolean) {
                            events.trySend(if (buffering) StreamEvent.Buffering else StreamEvent.Ready)
                        }

                        override fun onEnded() {
                            events.trySend(StreamEvent.Ended)
                        }

                        override fun onError(message: String) {
                            events.trySend(StreamEvent.Failed(message))
                        }
                    },
                    config.useTcp,
                )
                awaitOutcome(events, startedMs)
            } finally {
                linkWatcher.cancel()
                eventWatcher.cancel()
            }

            player.stop()
            log("stream stopped: ${outcome.message}")
            if (outcome.linkLost) {
                fail(LiveProblem.NOT_CONNECTED, outcome.message)
                return
            }
            if (outcome.previewStopped) {
                // Seen on a phone: when a recording ended while the player was still connected, the camera Wi-Fi vanished
                // within about 20-40 s. So the RTSP session is closed now (player.stop() above) and the stream is
                // started again only after the camera announces vf_start.
                fail(LiveProblem.RECORDING, outcome.message)
                val resumed = withTimeoutOrNull(config.resumeWaitMs) {
                    session.cameraEvents.first { it.type == "vf_start" }
                }
                if (resumed == null) return
                log("the camera announced vf_start, resuming")
                attempt = 0
                delay(config.retryDelayMs)
                continue
            }
            if (outcome.played) attempt = 0
            if (attempt >= config.maxStartAttempts) {
                fail(if (outcome.timedOut) LiveProblem.STREAM_TIMEOUT else LiveProblem.STREAM_FAILED, outcome.message)
                return
            }
            delay(config.retryDelayMs)
        }
    }

    /** Waits for the first picture, then watches the stream until it fails, ends, freezes or the link is lost. */
    private suspend fun awaitOutcome(events: Channel<StreamEvent>, startedMs: Long): Outcome {
        val firstFrameDeadlineMs = nowMs() + config.firstFrameTimeoutMs
        var playing = false
        var buffering = false
        while (true) {
            val waitMs = when {
                !playing -> firstFrameDeadlineMs - nowMs()
                buffering -> config.stallTimeoutMs
                else -> -1L
            }
            val event: StreamEvent? = if (waitMs < 0L) {
                events.receive()
            } else {
                withTimeoutOrNull(waitMs) { events.receive() }
            }
            when (event) {
                null -> return if (playing) {
                    Outcome("the picture froze: no data for ${config.stallTimeoutMs} ms", timedOut = false, linkLost = false, played = true)
                } else {
                    Outcome("no picture within ${config.firstFrameTimeoutMs} ms", timedOut = true, linkLost = false, played = false)
                }
                StreamEvent.FirstFrame -> {
                    playing = true
                    buffering = false
                    val tookMs = nowMs() - startedMs
                    log("first picture after $tookMs ms (preview command included)")
                    _state.value = LiveState.Playing(tookMs)
                }
                StreamEvent.Buffering -> if (playing) buffering = true
                StreamEvent.Ready -> buffering = false
                StreamEvent.Ended -> return Outcome("the stream ended", timedOut = false, linkLost = false, played = playing)
                StreamEvent.LinkLost -> return Outcome("the link to the camera was lost", timedOut = false, linkLost = true, played = playing)
                StreamEvent.PreviewStopped -> return Outcome(
                    "the camera stopped the preview (a recording started)",
                    timedOut = false,
                    linkLost = false,
                    played = playing,
                    previewStopped = true,
                )
                is StreamEvent.Failed -> return Outcome(event.message, timedOut = false, linkLost = false, played = playing)
            }
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

    private companion object {
        /** The answer of msg_id 259 while the camera is recording (seen twice). */
        const val RVAL_BUSY = -21
    }
}
