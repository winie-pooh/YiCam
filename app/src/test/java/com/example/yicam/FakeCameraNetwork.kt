/**
 * Responsible for: a fake CameraNetwork for tests: it hands out the default socket factory, can be told to fail, and can announce that the network was lost.
 * Not responsible for: assertions or any real Android networking.
 */
package com.example.yicam

import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class FakeCameraNetwork : CameraNetwork {
    /** While set, [bind] throws it instead of returning. */
    @Volatile
    var bindFailure: CameraException? = null

    val bindCalls = AtomicInteger(0)
    val releaseCalls = AtomicInteger(0)

    private val lostFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val lost: SharedFlow<Unit> = lostFlow

    override suspend fun bind(timeoutMs: Long): SocketFactory {
        bindCalls.incrementAndGet()
        val failure = bindFailure
        if (failure != null) throw failure
        return SocketFactory.getDefault()
    }

    override fun release() {
        releaseCalls.incrementAndGet()
    }

    /** Announces that the camera Wi-Fi disappeared. Only reaches a session that is already connected. */
    fun emitLost() {
        lostFlow.tryEmit(Unit)
    }
}
