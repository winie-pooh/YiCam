/**
 * Responsible for: unit tests of Downloader against a local Range HTTP server (206 resume, ignored Range, If-Range, Content-Range start mismatch, cancel, size mismatch, speed).
 * Not responsible for: FileDownload (FileDownloadTest.kt) or Android storage.
 */
package com.example.yicam

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class DownloaderTest {
    private val file = FileEntry("YDXJ0001.jpg", "101MEDIA", 0L, "", FileKind.PHOTO)

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    @Test
    fun parseContentRange_readsStartEndAndTotal() {
        val range = Downloader.parseContentRange("bytes 100-199/1500")!!
        assertEquals(100L, range.start)
        assertEquals(199L, range.end)
        assertEquals(1500L, range.total)
        assertEquals(null, Downloader.parseContentRange("bytes 0-10/*")!!.total)
        assertEquals(null, Downloader.parseContentRange("garbage"))
    }

    @Test
    fun cameraFileUrl_encodesThePath() {
        assertEquals(
            "http://192.168.42.1/DCIM/101MEDIA/YDXJ0001.jpg",
            Downloader.cameraFileUrl("101MEDIA", "YDXJ0001.jpg"),
        )
        assertEquals("a%20b", Downloader.segment("a b"))
    }

    @Test
    fun download_fullFile_isBitIdentical() = test {
        val body = ByteArray(200_000) { it.toByte() }
        RangeHttpServer(body).use { server ->
            val sink = MemoryDownloadSink()
            val last = Downloader().download(server.url, sink, file.copy(sizeBytes = body.size.toLong())).toList().last()
            assertArrayEquals(body, sink.content)
            assertEquals(body.size.toLong(), last.receivedBytes)
            assertEquals(body.size.toLong(), last.totalBytes)
        }
    }

    @Test
    fun download_resumeAfterCut_usesRange206() = test {
        val body = ByteArray(80_000) { it.toByte() }
        val sink = MemoryDownloadSink()
        RangeHttpServer(body, cutAfter = 20_000).use { server ->
            try {
                Downloader().download(server.url, sink, file).toList()
                fail("expected size mismatch")
            } catch (_: Downloader.DownloadSizeException) {
            }
            assertEquals(20_000L, sink.length())
        }
        RangeHttpServer(body).use { server ->
            Downloader().download(server.url, sink, file).toList()
            assertArrayEquals(body, sink.content)
        }
    }

    @Test
    fun download_serverIgnoresRange_restartsFromZero() = test {
        val body = ByteArray(12_000) { it.toByte() }
        val sink = MemoryDownloadSink()
        sink.write(body, 0, 1000)
        RangeHttpServer(body, ignoreRange = true).use { server ->
            Downloader().download(server.url, sink, file).toList()
            assertArrayEquals(body, sink.content)
        }
    }

    @Test
    fun download_contentRangeStartMismatch_throws() = test {
        val body = ByteArray(8_000) { it.toByte() }
        val sink = MemoryDownloadSink()
        sink.write(body, 0, 500)
        RangeHttpServer(body, forceContentRangeStart = 0L).use { server ->
            try {
                Downloader().download(server.url, sink, file).toList()
                fail("expected range exception")
            } catch (_: Downloader.DownloadRangeException) {
            }
            assertEquals(500L, sink.length())
        }
    }

    @Test
    fun download_ifRangeMismatch_restartsFromZero() = test {
        val body = ByteArray(8_000) { it.toByte() }
        val sink = MemoryDownloadSink()
        sink.write(body, 0, 500)
        sink.setEtag("\"old\"")
        RangeHttpServer(body, etag = "\"new\"").use { server ->
            Downloader().download(server.url, sink, file).toList()
            assertArrayEquals(body, sink.content)
        }
    }

    @Test
    fun download_cancel_stopsWithoutFinishing() = test {
        val body = ByteArray(500_000) { 1 }
        RangeHttpServer(body).use { server ->
            val sink = MemoryDownloadSink()
            val job = launch {
                try {
                    Downloader().download(server.url, sink, file).collect { progress ->
                        if (progress.receivedBytes > 8_000L) coroutineContext.job.cancel()
                    }
                    fail("expected cancel")
                } catch (_: CancellationException) {
                }
            }
            job.join()
            assertTrue(sink.length() in 1L until body.size.toLong())
        }
    }

    @Test
    fun download_httpForbidden_isCameraRefused() = test {
        RangeHttpServer(byteArrayOf(1), statusOverride = 403).use { server ->
            try {
                Downloader().download(server.url, MemoryDownloadSink(), file).toList()
                fail("expected http exception")
            } catch (e: Downloader.DownloadHttpException) {
                assertEquals(403, e.code)
            }
        }
    }

    @Test
    fun speedMeter_usesAThreeSecondWindow() {
        val now = AtomicLong(0L)
        val meter = Downloader.SpeedMeter(3_000L) { now.get() }
        assertEquals(0L, meter.bytesPerSecond(0L))
        now.set(1_000L)
        assertEquals(1_000L, meter.bytesPerSecond(1_000L))
        now.set(4_000L)
        assertEquals(1_000L, meter.bytesPerSecond(4_000L))
        now.set(8_000L)
        assertEquals(0L, meter.bytesPerSecond(4_000L))
    }
}
