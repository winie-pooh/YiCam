/**
 * Responsible for: creating the one CameraSession of the app, so the link and the status survive screen rotation.
 * Not responsible for: the behaviour of the session (CameraSession.kt).
 */
package com.example.yicam

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object SessionProvider {
    @Volatile
    private var instance: CameraSession? = null

    fun get(context: Context): CameraSession =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): CameraSession {
        val log = TrafficLog()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return CameraSession(WifiBinder(context, log), log, scope)
    }
}
