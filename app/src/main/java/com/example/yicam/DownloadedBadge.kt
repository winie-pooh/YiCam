/**
 * Responsible for: deciding whether a camera file is already present in MediaStore (Movies/YiCam or Pictures/YiCam) by matching DISPLAY_NAME and size.
 * Not responsible for: writing downloads (MediaStoreSink.kt), the gallery UI or thumbnail loading.
 */
package com.example.yicam

data class LocalMediaRow(val name: String, val size: Long, val uri: String)

class DownloadedBadge(
    private val query: () -> List<LocalMediaRow>,
) {
    fun isDownloaded(file: FileEntry): Boolean {
        if (file.sizeBytes <= 0L) return false
        return query().any { it.name == file.name && it.size == file.sizeBytes }
    }

    fun uriOf(file: FileEntry): String? {
        if (file.sizeBytes <= 0L) return null
        return query().firstOrNull { it.name == file.name && it.size == file.sizeBytes }?.uri
    }
}
