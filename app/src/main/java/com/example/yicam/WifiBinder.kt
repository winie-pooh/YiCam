/**
 * Responsible for: finding the camera's Wi-Fi network (phone address 192.168.42.x), binding the app process to it, and reporting when that network is lost (Android implementation of CameraNetwork).
 * Not responsible for: opening sockets (CameraConnection.kt), reconnect decisions (CameraSession.kt) or asking for permissions (MainActivity.kt).
 */
package com.example.yicam

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class WifiBinder(context: Context, private val log: TrafficLog) : CameraNetwork {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var boundNetwork: Network? = null

    private val _lost = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val lost: SharedFlow<Unit> = _lost.asSharedFlow()

    /**
     * Finds the Wi-Fi network whose phone address starts with 192.168.42., binds the process to it and returns its socket factory.
     * The network stays requested (and bound) until [release] is called; [lost] emits if it disappears.
     * @throws CameraException.WifiNotFound if no such network appears within [timeoutMs].
     */
    override suspend fun bind(timeoutMs: Long): SocketFactory {
        release()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val otherWifiSeen = AtomicBoolean(false)
        val found = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Network> { cont ->
                val done = AtomicBoolean(false)
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        inspect(network, connectivity.getLinkProperties(network))
                    }

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                        inspect(network, linkProperties)
                    }

                    override fun onLost(network: Network) {
                        if (network == boundNetwork) {
                            boundNetwork = null
                            log.sys("[CONN] the camera Wi-Fi network was lost")
                            _lost.tryEmit(Unit)
                        }
                    }

                    private fun inspect(network: Network, properties: LinkProperties?) {
                        if (properties == null || done.get()) return
                        val addresses = properties.linkAddresses.map { it.address }
                        val isCamera = addresses.any {
                            it is Inet4Address && it.hostAddress?.startsWith(CAMERA_ADDRESS_PREFIX) == true
                        }
                        if (!isCamera) {
                            otherWifiSeen.set(true)
                            log.sys("[WARN] Wi-Fi network is not the camera's, phone addresses: ${addresses.map { it.hostAddress }}")
                            return
                        }
                        if (!done.compareAndSet(false, true)) return
                        log.sys("Wi-Fi network found, phone addresses: ${addresses.map { it.hostAddress }}")
                        boundNetwork = network
                        connectivity.bindProcessToNetwork(network)
                        cont.resume(network)
                    }
                }
                callback = cb
                cont.invokeOnCancellation { release() }
                connectivity.requestNetwork(request, cb)
            }
        }
        if (found == null) {
            throw CameraException.WifiNotFound(
                otherWifiSeen.get(),
                if (otherWifiSeen.get()) {
                    "The phone is connected to a Wi-Fi network that is not the camera's."
                } else {
                    "Camera Wi-Fi was not found. Connect the phone to the camera's Wi-Fi (YDXJ_...) first, " +
                        "and if Android asks about a network without internet, choose to stay connected."
                },
            )
        }
        return found.socketFactory
    }

    /** Stops using the camera network: the process goes back to Android's normal routing. */
    override fun release() {
        val cb = callback
        callback = null
        boundNetwork = null
        try {
            connectivity.bindProcessToNetwork(null)
        } catch (_: RuntimeException) {
        }
        if (cb != null) {
            try {
                connectivity.unregisterNetworkCallback(cb)
            } catch (_: RuntimeException) {
            }
        }
    }

    private companion object {
        const val CAMERA_ADDRESS_PREFIX = "192.168.42."
    }
}
