/**
 * Responsible for: choosing the DownloadTarget from the app setting (MediaStore, a picked folder, or MediaStore as fallback).
 * Not responsible for: writing bytes (MediaStoreSink.kt, TreeDownloadSink.kt) or starting a download (FileDownload.kt).
 */
package com.example.yicam

import android.content.Context
import android.net.Uri

object DownloadTargets {
    fun from(context: Context, prefs: AppPrefs): DownloadTarget {
        if (prefs.downloadFolderMode == DownloadFolderMode.TREE) {
            val uri = prefs.downloadTreeUri?.let(Uri::parse)
            if (uri != null) return TreeDownloadTarget(context, uri)
        }
        return MediaStoreTarget(context)
    }
}
