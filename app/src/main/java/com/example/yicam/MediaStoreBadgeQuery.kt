/**
 * Responsible for: querying MediaStore for completed files under Movies/YiCam and Pictures/YiCam so the gallery can show a "Downloaded" badge.
 * Not responsible for: the badge decision itself (DownloadedBadge.kt) or writing new downloads (MediaStoreSink.kt).
 */
package com.example.yicam

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object MediaStoreBadgeQuery {
    fun rows(context: Context): List<LocalMediaRow> {
        val out = ArrayList<LocalMediaRow>()
        if (Build.VERSION.SDK_INT < 29) {
            collectLegacy(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "YiCam"), out)
            collectLegacy(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "YiCam"), out)
            return out
        }
        collectApi29(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/YiCam", out)
        collectApi29(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/YiCam", out)
        return out
    }

    private fun collectLegacy(dir: File, out: MutableList<LocalMediaRow>) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.length() > 0L) {
                out.add(LocalMediaRow(f.name, f.length(), android.net.Uri.fromFile(f).toString()))
            }
        }
    }

    private fun collectApi29(
        context: Context,
        collection: android.net.Uri,
        relativePath: String,
        out: MutableList<LocalMediaRow>,
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.IS_PENDING,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        val args = arrayOf("%$relativePath%")
        try {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val size = c.getLong(sizeIdx)
                    if (size <= 0L) continue
                    val id = c.getLong(idIdx)
                    val uri = android.content.ContentUris.withAppendedId(collection, id).toString()
                    out.add(LocalMediaRow(name, size, uri))
                }
            }
        } catch (_: Exception) {}
    }
}
