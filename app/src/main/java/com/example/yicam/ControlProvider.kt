/**
 * Responsible for: creating the one CameraController, the one CameraSettings and the one CameraFiles of the app, so they keep their state when the activity is recreated.
 * Not responsible for: the control logic (CameraController.kt), the settings logic (CameraSettings.kt), the files logic (CameraFiles.kt) or the screens (ControlPanel.kt, SettingsScreen.kt, FilesScreen.kt).
 */
package com.example.yicam

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object ControlProvider {
    @Volatile
    private var instance: CameraController? = null

    @Volatile
    private var settingsInstance: CameraSettings? = null

    @Volatile
    private var filesInstance: CameraFiles? = null

    fun files(context: Context): CameraFiles =
        filesInstance ?: synchronized(this) {
            filesInstance ?: CameraFiles(
                SessionProvider.get(context.applicationContext),
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also { filesInstance = it }
        }

    fun settings(context: Context): CameraSettings =
        settingsInstance ?: synchronized(this) {
            settingsInstance ?: CameraSettings(
                SessionProvider.get(context.applicationContext),
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also { settingsInstance = it }
        }

    fun get(context: Context): CameraController =
        instance ?: synchronized(this) {
            instance ?: CameraController(
                SessionProvider.get(context.applicationContext),
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also { instance = it }
        }
}
