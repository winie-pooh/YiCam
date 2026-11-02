/**
 * Responsible for: the data models of one file download (progress, state, failure reasons, destination mode).
 * Not responsible for: HTTP (Downloader.kt), writing bytes (DownloadSink.kt) or starting a download (FileDownload.kt).
 */
package com.example.yicam

data class DownloadProgress(
    val file: FileEntry,
    val receivedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long,
    val remainingMs: Long?,
) {
    val fraction: Float
        get() {
            val total = totalBytes ?: return 0f
            if (total <= 0L) return 0f
            return (receivedBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

enum class DownloadFailReason { NO_SPACE, LINK_LOST, SIZE_MISMATCH, CAMERA_REFUSED, NETWORK }

enum class DownloadFolderMode { MEDIA_STORE, TREE, ASK }

sealed class DownloadState {
    data object Idle : DownloadState()
    data class ConfirmSkip(val file: FileEntry, val uri: String) : DownloadState()
    data class Running(val progress: DownloadProgress) : DownloadState()
    data class Cancelled(val file: FileEntry) : DownloadState()
    data class Done(val file: FileEntry, val uri: String) : DownloadState()
    data class Failed(val file: FileEntry, val reason: DownloadFailReason, val resumable: Boolean) : DownloadState()
}

fun FileEntry.mimeType(): String = when (kind) {
    FileKind.VIDEO -> "video/mp4"
    FileKind.PHOTO -> "image/jpeg"
    FileKind.THUMBNAIL, FileKind.OTHER -> "application/octet-stream"
}
