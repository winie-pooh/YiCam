/**
 * Responsible for: playing the camera's RTSP stream with Media3 ExoPlayer (RTP over TCP, small buffers) into a SurfaceView, and logging the player's states and errors.
 * Not responsible for: starting the preview on the camera or retrying (LiveViewController.kt) or the screen layout (LiveScreen.kt).
 */
package com.example.yicam

import android.content.Context
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class ExoRtspPlayer(private val context: Context, private val log: TrafficLog) : StreamPlayer {
    private var player: ExoPlayer? = null
    private var surface: SurfaceView? = null
    private val _videoAspect = MutableStateFlow(DEFAULT_ASPECT)

    /** Width divided by height of the stream, 16:9 until the player knows the real size. */
    val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    /** Gives the player the view to draw on (null detaches). Call on the main thread. */
    fun attachSurface(view: SurfaceView?) {
        surface = view
        val current = player ?: return
        if (view != null) current.setVideoSurfaceView(view) else current.clearVideoSurface()
    }

    /** Call on the main thread. */
    override fun start(url: String, listener: StreamPlayer.Listener, useTcp: Boolean) {
        stop()
        log.sys("[LIVE] player start: $url (${if (useTcp) "RTP over TCP" else "transport chosen by the player, UDP first"})")
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
            .build()
        val exo = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                log.sys("[LIVE] player state ${stateName(playbackState)}")
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                log.sys("[LIVE] video size ${videoSize.width}x${videoSize.height}")
                if (videoSize.width > 0 && videoSize.height > 0) {
                    _videoAspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onRenderedFirstFrame() {
                log.sys("[LIVE] first frame drawn")
                listener.onFirstFrame()
            }

            override fun onPlayerError(error: PlaybackException) {
                val chain = causeChain(error)
                log.sys("[LIVE] player error ${error.errorCodeName}: $chain")
                listener.onError("${error.errorCodeName}: $chain")
            }
        })
        surface?.let { exo.setVideoSurfaceView(it) }
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(useTcp)
            .setTimeoutMs(RTSP_TIMEOUT_MS)
            .createMediaSource(MediaItem.fromUri(url))
        exo.setMediaSource(source)
        exo.playWhenReady = true
        exo.prepare()
        player = exo
    }

    /** Call on the main thread. */
    override fun stop() {
        val current = player ?: return
        player = null
        current.release()
        log.sys("[LIVE] player released")
    }

    /** "RtspPlaybackException: ... <- SocketException: ..." so the real reason is visible, not only "Source error". */
    private fun causeChain(error: Throwable): String =
        generateSequence(error) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    private companion object {
        const val DEFAULT_ASPECT = 16f / 9f
        const val RTSP_TIMEOUT_MS = 8_000L
        // Small buffers keep the delay low: start after 0.1 s of video, never hold more than 1 s.
        const val MIN_BUFFER_MS = 300
        const val MAX_BUFFER_MS = 1_000
        const val PLAYBACK_BUFFER_MS = 100
        const val REBUFFER_MS = 200
    }
}
