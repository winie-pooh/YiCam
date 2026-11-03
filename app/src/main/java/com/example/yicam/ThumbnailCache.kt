/**
 * Responsible for: in-memory and on-disk cache of thumbnail JPEG bytes, keyed by name+size.
 * Not responsible for: fetching bytes from the camera (ThumbnailLoader.kt) or decoding to Bitmap (GalleryScreen.kt).
 */
package com.example.yicam

import java.io.File
import java.util.LinkedHashMap

data class ThumbnailKey(val name: String, val sizeBytes: Long) {
    fun diskName(): String = "${name}_${sizeBytes}.jpg"
}

class ThumbnailCache(
    private val maxMemoryEntries: Int = 64,
    private val diskDir: File? = null,
) {
    private val memory = object : LinkedHashMap<ThumbnailKey, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ThumbnailKey, ByteArray>?): Boolean =
            size > maxMemoryEntries
    }
    private val lock = Any()

    fun get(key: ThumbnailKey): ByteArray? {
        synchronized(lock) {
            memory[key]?.let { return it }
        }
        val dir = diskDir ?: return null
        val file = File(dir, key.diskName())
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.isNotEmpty()) {
                synchronized(lock) { memory[key] = bytes }
                bytes
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun put(key: ThumbnailKey, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(lock) {
            memory[key] = bytes
        }
        val dir = diskDir ?: return
        try {
            dir.mkdirs()
            File(dir, key.diskName()).writeBytes(bytes)
        } catch (_: Exception) {
            // Disk full or permission: memory still holds it.
        }
    }

    fun clearMemory() {
        synchronized(lock) { memory.clear() }
    }

    /** Test helper: how many entries are currently in the memory map. */
    fun memorySize(): Int = synchronized(lock) { memory.size }
}
