/**
 * Responsible for: creating the one CameraController, the one CameraSettings, the one CameraFiles, the one FileDownload and the one ThumbnailLoader of the app, so they keep their state when the activity is recreated.
 * Not responsible for: the control logic (CameraController.kt), the settings logic (CameraSettings.kt), the files logic (CameraFiles.kt), the download logic (FileDownload.kt), thumbnail strategy details (ThumbnailLoader.kt) or the screens.
 */
package com.example.yicam

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

object ControlProvider {
    @Volatile
    private var instance: CameraController? = null

    @Volatile
    private var settingsInstance: CameraSettings? = null

    @Volatile
    private var filesInstance: CameraFiles? = null

    @Volatile
    private var downloadInstance: FileDownload? = null

    @Volatile
    private var thumbLoaderInstance: ThumbnailLoader? = null

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

    fun thumbnailLoader(context: Context): ThumbnailLoader =
        thumbLoaderInstance ?: synchronized(this) {
            thumbLoaderInstance ?: createThumbLoader(context.applicationContext).also { thumbLoaderInstance = it }
        }

    fun badge(context: Context): DownloadedBadge =
        DownloadedBadge { MediaStoreBadgeQuery.rows(context.applicationContext) }

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

    private fun createThumbLoader(app: Context): ThumbnailLoader {
        val session = SessionProvider.get(app)
        val cacheDir = File(app.cacheDir, "thumbs")
        val cache = ThumbnailCache(maxMemoryEntries = 64, diskDir = cacheDir)
        val fetcher = HttpThumbnailFetcher(log = { session.log.sys(it) })
        val decoder = AndroidThumbnailDecoder()
        return ThumbnailLoader(
            cache = cache,
            fetcher = fetcher,
            decoder = decoder,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            maxConcurrent = 2,
            log = { session.log.sys(it) },
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
