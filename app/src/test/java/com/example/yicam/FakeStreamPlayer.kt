/**
 * Responsible for: a fake StreamPlayer for tests: it records starts and stops and lets the test announce a first picture or an error.
 * Not responsible for: assertions or any real video playback.
 */
package com.example.yicam

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FakeStreamPlayer : StreamPlayer {
    val startedUrls = CopyOnWriteArrayList<String>()
    val startedWithTcp = CopyOnWriteArrayList<Boolean>()
    val stopCalls = AtomicInteger(0)

    @Volatile
    private var listener: StreamPlayer.Listener? = null

    override fun start(url: String, listener: StreamPlayer.Listener, useTcp: Boolean) {
        startedUrls.add(url)
        startedWithTcp.add(useTcp)
        this.listener = listener
    }

    override fun stop() {
        stopCalls.incrementAndGet()
        listener = null
    }

    /** Acts as the player of the most recent start. */
    fun emitFirstFrame() {
        listener?.onFirstFrame()
    }

    fun emitBuffering(buffering: Boolean) {
        listener?.onBuffering(buffering)
    }

    fun emitEnded() {
        listener?.onEnded()
    }

    fun emitError(message: String) {
        listener?.onError(message)
    }
}
