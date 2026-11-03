/**
 * Responsible for: the filter and selection models used by the gallery (kind filter, folder filter, multi-select set).
 * Not responsible for: loading listings (CameraFiles.kt), thumbnails (ThumbnailLoader.kt) or drawing (GalleryScreen.kt).
 */
package com.example.yicam

enum class GalleryKindFilter {
    ALL,
    PHOTOS,
    VIDEOS,
}

data class GallerySelection(
    val selected: Set<String> = emptySet(),
) {
    fun toggle(file: FileEntry): GallerySelection {
        val key = keyOf(file)
        return if (key in selected) copy(selected = selected - key) else copy(selected = selected + key)
    }

    fun clear(): GallerySelection = copy(selected = emptySet())

    fun contains(file: FileEntry): Boolean = keyOf(file) in selected

    fun filesFrom(all: List<FileEntry>): List<FileEntry> = all.filter { keyOf(it) in selected }

    companion object {
        fun keyOf(file: FileEntry): String = "${file.folder}/${file.name}"
    }
}

fun List<FileEntry>.filtered(
    kind: GalleryKindFilter,
    folder: String?,
): List<FileEntry> {
    var list = this
    if (folder != null) list = list.filter { it.folder == folder }
    return when (kind) {
        GalleryKindFilter.ALL -> list
        GalleryKindFilter.PHOTOS -> list.filter { it.kind == FileKind.PHOTO }
        GalleryKindFilter.VIDEOS -> list.filter { it.kind == FileKind.VIDEO }
    }
}
