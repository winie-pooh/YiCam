/**
 * Responsible for: writing a download into MediaStore (videos Movies/YiCam, photos Pictures/YiCam), including a pending row for resume; on API 26-28 a folder on shared storage with a .part file.
 * Not responsible for: HTTP (Downloader.kt), a user-picked folder (TreeDownloadSink.kt) or starting a download (FileDownload.kt).
 */
package com.example.yicam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MediaStoreTarget(private val context: Context) : DownloadTarget {
    private val resolver = context.contentResolver
    private val etags = context.getSharedPreferences(ETAG_PREFS, Context.MODE_PRIVATE)

    override fun availableBytes(): Long = freeBytes(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
    )

    override fun existingComplete(file: FileEntry): String? {
        if (Build.VERSION.SDK_INT < 29) {
            val done = fileDir(file).let { File(it, file.name) }
            return if (done.isFile && done.length() == file.sizeBytes && file.sizeBytes > 0L) {
                Uri.fromFile(done).toString()
            } else {
                null
            }
        }
        return findRow(file, pending = false)?.takeIf { it.size == file.sizeBytes && file.sizeBytes > 0L }?.uri?.toString()
    }

    override fun open(file: FileEntry): DownloadSink {
        if (Build.VERSION.SDK_INT < 29) return openLegacy(file)
        val pending = findRow(file, pending = true)
        val uri = pending?.uri ?: insertPending(file)
        val already = querySize(uri)
        return StreamSink(
            length = already,
            tag = etags.getString(uri.toString(), null),
            openOut = { append -> resolver.openOutputStream(uri, if (append && already > 0L) "wa" else "wt")!! },
            onEtag = { etags.edit().putString(uri.toString(), it).apply() },
            onComplete = {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, values, null, null)
                uri.toString()
            },
        )
    }

    override fun removeComplete(file: FileEntry) {
        if (Build.VERSION.SDK_INT < 29) {
            File(fileDir(file), file.name).delete()
            return
        }
        findRow(file, pending = false)?.let { resolver.delete(it.uri, null, null) }
    }

    private fun openLegacy(file: FileEntry): DownloadSink {
        val dir = fileDir(file)
        dir.mkdirs()
        val part = File(dir, file.name + PART_SUFFIX)
        val done = File(dir, file.name)
        if (!part.exists() && done.exists() && done.length() != file.sizeBytes) {
            done.delete()
        }
        val already = if (part.exists()) part.length() else 0L
        return StreamSink(
            length = already,
            tag = etags.getString(part.absolutePath, null),
            openOut = { append -> FileOutputStream(part, append && already > 0L) },
            onEtag = { etags.edit().putString(part.absolutePath, it).apply() },
            onComplete = {
                if (done.exists()) done.delete()
                if (!part.renameTo(done)) throw IllegalStateException("rename failed")
                Uri.fromFile(done).toString()
            },
        )
    }

    private fun insertPending(file: FileEntry): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, file.mimeType())
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(file))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection(file), values) ?: throw IllegalStateException("MediaStore insert failed")
    }

    private fun findRow(file: FileEntry, pending: Boolean): Row? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(file.name, relativePath(file))
        resolver.query(collection(file), projection, selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            while (cursor.moveToNext()) {
                val isPending = cursor.getInt(pendingCol) != 0
                if (isPending != pending) continue
                val id = cursor.getLong(idCol)
                return Row(Uri.withAppendedPath(collection(file), id.toString()), cursor.getLong(sizeCol))
            }
        }
        return null
    }

    private fun querySize(uri: Uri): Long {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 0L
    }

    private fun collection(file: FileEntry): Uri =
        if (file.kind == FileKind.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun relativePath(file: FileEntry): String =
        if (file.kind == FileKind.VIDEO) VIDEO_PATH else PHOTO_PATH

    private fun fileDir(file: FileEntry): File {
        val root = if (file.kind == FileKind.VIDEO) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        }
        return File(root, "YiCam")
    }

    private data class Row(val uri: Uri, val size: Long)

    companion object {
        const val VIDEO_PATH = "Movies/YiCam/"
        const val PHOTO_PATH = "Pictures/YiCam/"
        const val PART_SUFFIX = ".part"
        const val ETAG_PREFS = "yicam_etags"

        fun freeBytes(dir: File): Long {
            val path = if (dir.exists() || dir.mkdirs()) dir else dir.parentFile ?: dir
            return try {
                StatFs(path.absolutePath).availableBytes
            } catch (_: Exception) {
                0L
            }
        }
    }
}

internal class StreamSink(
    private var length: Long,
    private var tag: String?,
    private val openOut: (append: Boolean) -> OutputStream,
    private val onEtag: (String?) -> Unit,
    private val onComplete: () -> String,
) : DownloadSink {
    private var out: OutputStream? = openOut(length > 0L)

    override fun length(): Long = length
    override fun etag(): String? = tag
    override fun setEtag(value: String?) {
        tag = value
        onEtag(value)
    }
    override fun truncate() {
        close()
        length = 0L
        out = openOut(false)
    }
    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        val stream = out ?: throw IllegalStateException("closed")
        stream.write(buffer, offset, count)
        length += count.toLong()
    }
    override fun close() {
        try {
            out?.flush()
        } catch (_: Exception) {
        }
        try {
            out?.close()
        } catch (_: Exception) {
        }
        out = null
    }
    override fun markComplete(): String {
        close()
        return onComplete()
    }
}
