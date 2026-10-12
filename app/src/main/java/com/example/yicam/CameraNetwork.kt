/**
 * Responsible for: the contract "find the camera's Wi-Fi, bind to it, tell when it is lost", so the session can be tested without Android.
 * Not responsible for: how Android does it (WifiBinder.kt) or what is done with the network (CameraSession.kt).
 */
package com.example.yicam

import javax.net.SocketFactory
import kotlinx.coroutines.flow.SharedFlow

interface CameraNetwork {
    /** Returns a socket factory of the camera network. Throws [CameraException.WifiNotFound] if none appears within [timeoutMs]. */
    suspend fun bind(timeoutMs: Long): SocketFactory

    /** Stops using the camera network. Safe to call at any time. */
    fun release()

    /** Emits when the network returned by [bind] disappears (the camera was switched off or is out of range). */
    val lost: SharedFlow<Unit>
}
