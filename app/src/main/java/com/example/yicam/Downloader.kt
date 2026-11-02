/**
 * Responsible for: downloading one HTTP file with Range resume, If-Range, a 64 KB buffer and a 3 s speed average; cancellation is coroutine cancellation; the conversation (URL, answer code, resume decisions, byte counts, errors) goes to the given log.
 * Not responsible for: choosing where to write (DownloadSink.kt), free space or live view (FileDownload.kt).
 */
package com.example.yicam

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class Downloader(
    private val config: Config = Config(),
    private val openConnection: (String) -> HttpURLConnection = { url ->
        (URL(url).openConnection() as HttpURLConnection)
    },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) {
    data class Config(
        val connectTimeoutMs: Int = 15_000,
        val readTimeoutMs: Int = 15_000,
        val bufferSize: Int = 64 * 1024,
        val speedWindowMs: Long = 3_000L,
    )

    fun download(
        url: String,
        sink: DownloadSink,
        file: FileEntry = FileEntry("download", "", 0L, "", FileKind.OTHER),
    ): Flow<DownloadProgress> = flow {
        var connection: HttpURLConnection? = null
        try {
            val offset = sink.length()
            val conn = openConnection(url)
            connection = conn
            currentCoroutineContext().job.invokeOnCompletion {
                try {
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
            conn.connectTimeout = config.connectTimeoutMs
            conn.readTimeout = config.readTimeoutMs
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.requestMethod = "GET"
            if (offset > 0L) {
                conn.setRequestProperty("Range", "bytes=$offset-")
                sink.etag()?.let { conn.setRequestProperty("If-Range", it) }
            }
            log("[HTTP] GET $url" + if (offset > 0L) " (resume from $offset bytes)" else "")
            val code = conn.responseCode
            currentCoroutineContext().ensureActive()
            log(
                "[HTTP] answer $code" +
                    (conn.getHeaderField("ETag")?.let { ", ETag $it" } ?: "") +
                    (conn.getHeaderField("Content-Range")?.let { ", Content-Range $it" } ?: ""),
            )
            if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_FORBIDDEN) {
                throw DownloadHttpException(code)
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw DownloadHttpException(code)
            }
            conn.getHeaderField("ETag")?.let { sink.setEtag(it) }
            val total: Long?
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val range = parseContentRange(conn.getHeaderField("Content-Range"))
                    ?: throw DownloadRangeException("missing Content-Range")
                if (range.start != offset) {
                    throw DownloadRangeException("Content-Range start ${range.start} != $offset")
                }
                total = range.total
            } else {
                if (offset > 0L) {
                    log("[HTTP] the server ignored Range (200), restarting from 0")
                    sink.truncate()
                }
                val length = conn.contentLengthLong
                total = if (length >= 0L) length else null
            }
            val meter = SpeedMeter(config.speedWindowMs, nowMs)
            fun snapshot(): DownloadProgress {
                val received = sink.length()
                val speed = meter.bytesPerSecond(received)
                val left = if (total != null && speed > 0L && received < total) {
                    ((total - received) * 1000L) / speed
                } else {
                    null
                }
                return DownloadProgress(file, received, total, speed, left)
            }
            emit(snapshot())
            val input = conn.inputStream
            val buffer = ByteArray(config.bufferSize)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = try {
                    input.read(buffer)
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw e
                }
                if (n < 0) break
                sink.write(buffer, 0, n)
                emit(snapshot())
            }
            currentCoroutineContext().ensureActive()
            val have = sink.length()
            if (total != null && have != total) {
                throw DownloadSizeException(have, total)
            }
            log("[HTTP] received $have bytes")
            emit(snapshot())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: DownloadHttpException) {
            throw e
        } catch (e: DownloadSizeException) {
            throw e
        } catch (e: DownloadRangeException) {
            throw e
        } catch (e: Exception) {
            log("[HTTP] error: ${e.message ?: e.javaClass.simpleName}")
            throw DownloadNetworkException(e)
        } finally {
            sink.close()
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    class DownloadHttpException(val code: Int) : Exception("HTTP $code")
    class DownloadSizeException(val have: Long, val expected: Long) : Exception("size $have != $expected")
    class DownloadRangeException(detail: String) : Exception(detail)
    class DownloadNetworkException(cause: Exception) : Exception(cause.message, cause)

    companion object {
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

        fun cameraFileUrl(folder: String, name: String, host: String = CameraConnection.DEFAULT_HOST): String =
            "http://$host/DCIM/${segment(folder)}/${segment(name)}"

        fun segment(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        fun parseContentRange(header: String?): ContentRange? {
            val match = CONTENT_RANGE.matchEntire(header?.trim().orEmpty()) ?: return null
            val totalText = match.groupValues[3]
            return ContentRange(
                start = match.groupValues[1].toLong(),
                end = match.groupValues[2].toLong(),
                total = if (totalText == "*") null else totalText.toLong(),
            )
        }
    }

    data class ContentRange(val start: Long, val end: Long, val total: Long?)

    internal class SpeedMeter(private val windowMs: Long, private val nowMs: () -> Long) {
        private val samples = ArrayDeque<Pair<Long, Long>>()

        fun bytesPerSecond(receivedBytes: Long): Long {
            val now = nowMs()
            samples.addLast(now to receivedBytes)
            while (samples.size > 1 && now - samples.first().first > windowMs) {
                samples.removeFirst()
            }
            if (samples.size < 2) return 0L
            val oldest = samples.first()
            val dt = now - oldest.first
            if (dt <= 0L) return 0L
            val db = receivedBytes - oldest.second
            if (db <= 0L) return 0L
            return (db * 1000L) / dt
        }
    }
}
