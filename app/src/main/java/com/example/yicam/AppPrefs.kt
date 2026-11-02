/**
 * Responsible for: the small app preferences kept on the phone: the orientation of the live picture, the grid mode and the download folder (SharedPreferences, no camera data).
 * Not responsible for: the calculations (LiveOrientation.kt, GridGeometry.kt) or any screen (LiveScreen.kt, FilesScreen.kt).
 */
package com.example.yicam

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("yicam_prefs", Context.MODE_PRIVATE)

    var liveOrientation: LiveOrientation
        get() = LiveOrientation.fromName(prefs.getString(KEY_ORIENTATION, null))
        set(value) {
            prefs.edit().putString(KEY_ORIENTATION, value.name).apply()
        }

    var gridMode: GridMode
        get() = GridMode.fromName(prefs.getString(KEY_GRID, null))
        set(value) {
            prefs.edit().putString(KEY_GRID, value.name).apply()
        }

    var downloadFolderMode: DownloadFolderMode
        get() = when (prefs.getString(KEY_DOWNLOAD_MODE, null)) {
            DownloadFolderMode.TREE.name -> DownloadFolderMode.TREE
            DownloadFolderMode.ASK.name -> DownloadFolderMode.ASK
            else -> DownloadFolderMode.MEDIA_STORE
        }
        set(value) {
            prefs.edit().putString(KEY_DOWNLOAD_MODE, value.name).apply()
        }

    var downloadTreeUri: String?
        get() = prefs.getString(KEY_DOWNLOAD_TREE, null)
        set(value) {
            prefs.edit().putString(KEY_DOWNLOAD_TREE, value).apply()
        }

    private companion object {
        const val KEY_ORIENTATION = "live_orientation"
        const val KEY_GRID = "grid_mode"
        const val KEY_DOWNLOAD_MODE = "download_folder_mode"
        const val KEY_DOWNLOAD_TREE = "download_tree_uri"
    }
}
