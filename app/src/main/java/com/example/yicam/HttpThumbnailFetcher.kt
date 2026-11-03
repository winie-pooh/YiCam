/**
 * Responsible for: fetching a limited number of bytes from the camera HTTP server (Range when maxBytes is set) for thumbnails.
 * Not responsible for: caching (ThumbnailCache.kt), decoding, concurrency limit or strategy (ThumbnailLoader.kt).
 */
package com.example.yicam

import java.net.HttpURLConnection
import java.net.URL

class HttpThumbnailFetcher(
    private val openConnection: (String) -> HttpURLConnection = { url ->
        (URL(url).openConnection() as HttpURLConnection)
    },
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val log: (String) -> Unit = {},
) : ThumbnailByteFetcher {
    override suspend fun fetch(url: String, maxBytes: Long?): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openConnection(url)
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.requestMethod = "GET"
            if (maxBytes != null && maxBytes > 0L) {
                conn.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
            }
            val code = conn.responseCode
            log("[THUMB-HTTP] $url -> $code (max=$maxBytes)")
            if (code !in 200..299) return null
            val stream = conn.inputStream
            val limit = maxBytes?.toInt()?.coerceAtLeast(0) ?: Int.MAX_VALUE
            val buffer = ByteArray(minOf(64 * 1024, limit))
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (total < limit) {
                val n = stream.read(buffer, 0, minOf(buffer.size, limit - total))
                if (n <= 0) break
                out.write(buffer, 0, n)
                total += n
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            log("[THUMB-HTTP] $url failed: ${e.message}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
