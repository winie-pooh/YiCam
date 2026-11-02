/**
 * Responsible for: turning a msg_id 1282 listing reply into folders and files: sizes as Long, dates as text, hidden folders dropped, .THM attached to its video, newest YDXJ number first.
 * Not responsible for: sending 1283/1282 (CameraFiles.kt), the models themselves (FileModels.kt) or drawing (FilesScreen.kt).
 */
package com.example.yicam

object ListingParser {
    const val MEDIA_ROOT = "/tmp/fuse_d/DCIM"
    const val LIST_PARAM = "-D -S"

    private val YDXJ = Regex("""YDXJ(\d+)""", RegexOption.IGNORE_CASE)
    private val FOLDER_NUMBER = Regex("""(\d+)""")
    private val SIZE_AND_DATE = Regex("""^(\d+)\s*bytes\|(.*)$""", RegexOption.IGNORE_CASE)
    private val SIZE_ONLY = Regex("""^(\d+)\s*bytes$""", RegexOption.IGNORE_CASE)
    private val HIDDEN_FOLDER_NAMES = setOf("eosmisc")

    fun folders(reply: CameraMessage.Response): List<FolderEntry> =
        rawEntries(reply)
            .filter { (name, _) -> name.endsWith("/") && !isHiddenFolder(name.trimEnd('/')) }
            .map { (name, meta) -> FolderEntry(name.trimEnd('/'), parseMeta(meta).second) }
            .sortedWith { a, b -> compareNewestFirst(a.name, b.name, folder = true) }

    fun files(reply: CameraMessage.Response, folder: String): List<FileEntry> {
        val parsed = ArrayList<FileEntry>()
        for ((name, meta) in rawEntries(reply)) {
            if (name.endsWith("/")) continue
            val (size, date) = parseMeta(meta)
            parsed.add(FileEntry(name, folder, size, date, kindOf(name)))
        }
        val thumbs = parsed.filter { it.kind == FileKind.THUMBNAIL }.associateBy { baseName(it.name).lowercase() }
        val rows = ArrayList<FileEntry>()
        val usedThumbs = HashSet<String>()
        for (entry in parsed) {
            if (entry.kind == FileKind.THUMBNAIL) continue
            val thumb = if (entry.kind == FileKind.VIDEO) thumbs[baseName(entry.name).lowercase()] else null
            if (thumb != null) usedThumbs.add(thumb.name.lowercase())
            rows.add(if (thumb != null) entry.copy(thumbnailName = thumb.name) else entry)
        }
        for (thumb in parsed) {
            if (thumb.kind == FileKind.THUMBNAIL && thumb.name.lowercase() !in usedThumbs) {
                rows.add(thumb.copy(kind = FileKind.OTHER))
            }
        }
        return rows.sortedWith { a, b -> compareNewestFirst(a.name, b.name, folder = false) }
    }

    fun kindOf(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> FileKind.VIDEO
        "jpg", "jpeg" -> FileKind.PHOTO
        "thm" -> FileKind.THUMBNAIL
        else -> FileKind.OTHER
    }

    /** Number in YDXJ#### (files) or any digits (folders). Null if there is none. */
    fun sortNumber(name: String, folder: Boolean): Long? {
        val match = if (folder) FOLDER_NUMBER.find(name) else YDXJ.find(name)
        return match?.groupValues?.get(1)?.toLongOrNull()
    }

    fun compareNewestFirst(a: String, b: String, folder: Boolean): Int {
        val na = sortNumber(a, folder)
        val nb = sortNumber(b, folder)
        return when {
            na != null && nb != null && na != nb -> nb.compareTo(na)
            na != null && nb == null -> -1
            na == null && nb != null -> 1
            else -> a.compareTo(b, ignoreCase = true)
        }
    }

    internal fun rawEntries(reply: CameraMessage.Response): List<Pair<String, String>> {
        val listing = reply.fields["listing"] as? List<*> ?: return emptyList()
        val result = ArrayList<Pair<String, String>>()
        for (item in listing) {
            val map = item as? Map<*, *> ?: continue
            for ((rawKey, rawValue) in map) {
                val name = rawKey as? String ?: continue
                val text = when (rawValue) {
                    is String -> rawValue
                    is Number -> rawValue.toString()
                    else -> continue
                }
                result.add(name to text)
            }
        }
        return result
    }

    internal fun parseMeta(text: String): Pair<Long, String> {
        val trimmed = text.trim()
        SIZE_AND_DATE.matchEntire(trimmed)?.let { match ->
            return (match.groupValues[1].toLongOrNull() ?: 0L) to match.groupValues[2]
        }
        SIZE_ONLY.matchEntire(trimmed)?.let { match ->
            return (match.groupValues[1].toLongOrNull() ?: 0L) to ""
        }
        return 0L to trimmed
    }

    private fun isHiddenFolder(name: String): Boolean =
        name.startsWith(".") || name.lowercase() in HIDDEN_FOLDER_NAMES

    private fun baseName(name: String): String = name.substringBeforeLast('.')
}
