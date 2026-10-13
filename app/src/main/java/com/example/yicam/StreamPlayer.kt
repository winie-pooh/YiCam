/**
 * Responsible for: the contract of a video player that plays one RTSP stream and reports the first picture or an error, so the live view logic can be tested without Android.
 * Not responsible for: any player implementation (ExoRtspPlayer.kt) or the live view logic (LiveViewController.kt).
 */
package com.example.yicam

interface StreamPlayer {
    interface Listener {
        /** The first video frame was drawn. */
        fun onFirstFrame()

        /** The player started or stopped waiting for data. Many short periods are normal; a long one means a frozen picture. */
        fun onBuffering(buffering: Boolean) {}

        /** The stream ended (for example the camera stopped sending). */
        fun onEnded() {}

        /** The stream failed or ended; [message] is English text for the log. */
        fun onError(message: String)
    }

    /** Starts playing [url]; replaces anything that was playing. [useTcp] true forces RTP over TCP, false lets the player choose (it tries UDP first). */
    fun start(url: String, listener: Listener, useTcp: Boolean = true)

    /** Stops playing and frees the decoder. Safe to call at any time. */
    fun stop()
}
