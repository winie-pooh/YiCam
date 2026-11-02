/**
 * Responsible for: the data models of camera folders and files (kind, size as Long, date as text, optional thumbnail name).
 * Not responsible for: parsing listings (ListingParser.kt), reading them from the camera (CameraFiles.kt) or drawing them (FilesScreen.kt).
 */
package com.example.yicam

enum class FileKind { VIDEO, PHOTO, THUMBNAIL, OTHER }

data class FolderEntry(
    val name: String,
    val dateText: String,
)

data class FileEntry(
    val name: String,
    val folder: String,
    val sizeBytes: Long,
    val dateText: String,
    val kind: FileKind,
    val thumbnailName: String? = null,
) {
    val absolutePath: String get() = "${ListingParser.MEDIA_ROOT}/$folder/$name"
}
