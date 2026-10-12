package com.example.yicam

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Finds the Wi-Fi network the phone is connected to, checks that it is the camera
 * (address 192.168.42.x) and binds the whole app process to it, so traffic does not
 * leak to mobile data when Android sees "Wi-Fi without internet".
 */
class WifiBinder(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Human readable info about the last found network (for the log). */
    @Volatile
    var lastInfo: String = ""
        private set

    fun bind(onResult: (Result<Network>) -> Unit) {
        release()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        var delivered = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (delivered) return
                val addrs = linkProperties.linkAddresses.mapNotNull { it.address.hostAddress }
                if (addrs.isEmpty()) return // wait until addresses are known
                delivered = true
                lastInfo = "Wi-Fi network found, phone addresses: $addrs"
                val isCamera = addrs.any { it.startsWith("192.168.42.") }
                if (!isCamera) {
                    onResult(Result.failure(IllegalStateException(
                        "Connected Wi-Fi is not the camera (expected 192.168.42.x)")))
                    return
                }
                if (cm.bindProcessToNetwork(network)) {
                    onResult(Result.success(network))
                } else {
                    onResult(Result.failure(IllegalStateException("bindProcessToNetwork() failed")))
                }
            }

            override fun onUnavailable() {
                if (delivered) return
                delivered = true
                lastInfo = "No Wi-Fi network"
                onResult(Result.failure(IllegalStateException(
                    "No Wi-Fi network (timeout). Connect the phone to the camera Wi-Fi first.")))
            }
        }
        callback = cb
        cm.requestNetwork(request, cb, 10_000)
    }

    fun release() {
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // already unregistered
            }
        }
        callback = null
    }

    fun unbindProcess() {
        cm.bindProcessToNetwork(null)
    }
}
