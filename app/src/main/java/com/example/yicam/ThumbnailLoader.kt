/**
 * Responsible for: loading thumbnail JPEG bytes with at most two concurrent fetches, memory+disk cache, and the strategy (video .THM, photo first-64KB EXIF, video frame at 1 s, else null). Visible items are preferred; a cancelled request stops when the caller cancels the coroutine.
 * Not responsible for: drawing the grid (GalleryScreen.kt), the full-size viewer, MediaStore badge, or waking the HTTP server (FileDownload.kt / session).
 */
package com.example.yicam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

fun interface ThumbnailByteFetcher {
    suspend fun fetch(url: String, maxBytes: Long?): ByteArray?
}

interface ThumbnailDecoder {
    fun exifThumbnail(jpegPrefix: ByteArray): ByteArray?
    fun downscaleJpeg(fullJpeg: ByteArray, maxEdge: Int = 256): ByteArray?
    suspend fun videoFrameAt(url: String, timeMs: Long = 1000L): ByteArray?
}

class ThumbnailLoader(
    private val cache: ThumbnailCache,
    private val fetcher: ThumbnailByteFetcher,
    private val decoder: ThumbnailDecoder,
    private val scope: CoroutineScope,
    private val maxConcurrent: Int = 2,
    private val log: (String) -> Unit = {},
) {
    private val semaphore = Semaphore(maxConcurrent)
    private val inflight = ConcurrentHashMap<ThumbnailKey, Job>()
    private val results = ConcurrentHashMap<ThumbnailKey, ByteArray?>()
    private val listeners = ConcurrentHashMap<ThumbnailKey, MutableList<(ByteArray?) -> Unit>>()
    private val listenersLock = Mutex()

    fun request(file: FileEntry, onResult: (ByteArray?) -> Unit): Job {
        val key = ThumbnailKey(file.name, file.sizeBytes)
        val cached = cache.get(key)
        if (cached != null) {
            onResult(cached)
            return scope.launch { }
        }
        results[key]?.let { already ->
            onResult(already)
            return scope.launch { }
        }

        return scope.launch {
            listenersLock.withLock {
                val list = listeners.getOrPut(key) { mutableListOf() }
                list.add(onResult)
                if (list.size > 1) return@launch
            }

            val job = scope.launch {
                val bytes = load(file, key)
                results[key] = bytes
                if (bytes != null) cache.put(key, bytes)
                val waiters = listenersLock.withLock {
                    listeners.remove(key) ?: emptyList()
                }
                waiters.forEach { it(bytes) }
            }
            inflight[key] = job
            try {
                job.join()
            } finally {
                inflight.remove(key, job)
            }
        }
    }

    fun cancelAll() {
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        scope.launch {
            listenersLock.withLock { listeners.clear() }
        }
    }

    private suspend fun load(file: FileEntry, key: ThumbnailKey): ByteArray? {
        return semaphore.withPermit {
            try {
                when (file.kind) {
                    FileKind.VIDEO -> loadVideo(file)
                    FileKind.PHOTO -> loadPhoto(file)
                    else -> null
                }
            } catch (e: Exception) {
                log("[THUMB] ${file.name}: failed ${e.message}")
                null
            }
        }
    }

    private suspend fun loadVideo(file: FileEntry): ByteArray? {
        val thmName = file.thumbnailName
        if (thmName != null) {
            val url = Downloader.cameraFileUrl(file.folder, thmName)
            log("[THUMB] ${file.name}: try .THM $thmName")
            val bytes = fetcher.fetch(url, maxBytes = 200_000L)
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        val url = Downloader.cameraFileUrl(file.folder, file.name)
        log("[THUMB] ${file.name}: try video frame at 1 s")
        return decoder.videoFrameAt(url, 1000L)
    }

    private suspend fun loadPhoto(file: FileEntry): ByteArray? {
        val url = Downloader.cameraFileUrl(file.folder, file.name)
        log("[THUMB] ${file.name}: try EXIF from first 64 KB")
        val prefix = fetcher.fetch(url, maxBytes = 64 * 1024L)
        if (prefix != null) {
            val exif = decoder.exifThumbnail(prefix)
            if (exif != null && exif.isNotEmpty()) return exif
        }
        if (file.sizeBytes > 0L && file.sizeBytes <= 4L * 1024 * 1024) {
            log("[THUMB] ${file.name}: EXIF miss, fetch full JPEG and downscale")
            val full = fetcher.fetch(url, maxBytes = file.sizeBytes)
            if (full != null) {
                return decoder.downscaleJpeg(full, maxEdge = 256)
            }
        } else {
            log("[THUMB] ${file.name}: no EXIF and file too large for full fetch")
        }
        return null
    }

    fun activeCount(): Int = maxConcurrent - semaphore.availablePermits

    fun clearResults() {
        results.clear()
        cache.clearMemory()
    }
}
