/**
 * Responsible for: creating the one CameraController, the one CameraSettings, the one CameraFiles and the one FileDownload of the app, so they keep their state when the activity is recreated.
 * Not responsible for: the control logic (CameraController.kt), the settings logic (CameraSettings.kt), the files logic (CameraFiles.kt), the download logic (FileDownload.kt) or the screens (ControlPanel.kt, SettingsScreen.kt, FilesScreen.kt).
 */
package com.example.yicam

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    @Volatile
    private var downloadInstance: FileDownload? = null

    fun files(context: Context): CameraFiles =
        filesInstance ?: synchronized(this) {
            filesInstance ?: CameraFiles(
                SessionProvider.get(context.applicationContext),
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also { filesInstance = it }
        }

    fun download(context: Context): FileDownload =
        downloadInstance ?: synchronized(this) {
            downloadInstance ?: createDownload(context.applicationContext).also { downloadInstance = it }
        }

    private fun createDownload(app: Context): FileDownload {
        val prefs = AppPrefs(app)
        val main = Handler(Looper.getMainLooper())
        return FileDownload(
            SessionProvider.get(app),
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            targetOf = { DownloadTargets.from(app, prefs) },
            onStopLive = { main.post { LiveProvider.get(app).controller.stop() } },
        )
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
