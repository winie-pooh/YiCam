/**
 * Responsible for: creating the one CameraController of the app, so the controls keep their state when the activity is recreated.
 * Not responsible for: the control logic (CameraController.kt) or the screen (ControlPanel.kt).
 */
package com.example.yicam

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object ControlProvider {
    @Volatile
    private var instance: CameraController? = null

    fun get(context: Context): CameraController =
        instance ?: synchronized(this) {
            instance ?: CameraController(
                SessionProvider.get(context.applicationContext),
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also { instance = it }
        }
}
