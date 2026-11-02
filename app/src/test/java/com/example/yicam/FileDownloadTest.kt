/**
 * Responsible for: tests of FileDownload (skip if already there, free space, resume after a cut, cancel, size mismatch, camera refused, link lost).
 * Not responsible for: HTTP details (DownloaderTest.kt) or Android storage.
 */
package com.example.yicam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch

class FileDownloadTest {
    private lateinit var camera: FakeCameraServer
    private lateinit var scope: CoroutineScope
    private lateinit var session: CameraSession
    private lateinit var target: MemoryDownloadTarget
    private var liveStopped = 0

    @Before
    fun setUp() {
        camera = FakeCameraServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        session = CameraSession(
            FakeCameraNetwork(),
            TrafficLog(),
            scope,
            CameraSession.Config(
                host = "127.0.0.1",
                port = camera.port,
                pollBatteryMs = 60_000L,
                pollSdMs = 60_000L,
                eventRefreshDelayMs = 20L,
            ),
        )
        target = MemoryDownloadTarget()
        liveStopped = 0
    }

    @After
    fun tearDown() {
        session.disconnect()
        scope.cancel()
        camera.close()
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        repeat(400) {
            if (condition()) return
            delay(25)
        }
        fail("timed out waiting for: $what")
    }

    private suspend fun connect() {
        session.connect()
        waitUntil("connected") { session.state.value is SessionState.Connected }
    }

    private fun downloader(http: RangeHttpServer): FileDownload = FileDownload(
        session,
        scope,
        targetOf = { target },
        onStopLive = { liveStopped++ },
        urlOf = { http.url },
    )

    private fun sample(size: Int = 40_000) = FileEntry(
        "YDXJ0001.jpg",
        "101MEDIA",
        size.toLong(),
        "",
        FileKind.PHOTO,
    )

    @Test
    fun start_whenAlreadyDownloaded_asksToSkip() = test {
        connect()
        target.completeUri = "memory:done"
        val http = RangeHttpServer(ByteArray(10))
        http.use {
            val download = downloader(http)
            download.start(sample(10))
            val state = download.state.value as DownloadState.ConfirmSkip
            assertEquals("memory:done", state.uri)
            assertEquals(0, liveStopped)
            download.skipExisting()
            assertTrue(download.state.value is DownloadState.Done)
        }
    }

    @Test
    fun start_noSpace_failsWithoutWriting() = test {
        connect()
        target.freeBytes = 10
        RangeHttpServer(ByteArray(5_000)).use { http ->
            val download = downloader(http)
            download.start(sample(5_000))
            waitUntil("no space") {
                (download.state.value as? DownloadState.Failed)?.reason == DownloadFailReason.NO_SPACE
            }
        }
    }

    @Test
    fun start_notConnected_isLinkLost() = test {
        RangeHttpServer(ByteArray(10)).use { http ->
            val download = downloader(http)
            download.start(sample(10))
            val failed = download.state.value as DownloadState.Failed
            assertEquals(DownloadFailReason.LINK_LOST, failed.reason)
            assertTrue(failed.resumable)
        }
    }

    @Test
    fun start_downloadsAndStopsLiveView() = test {
        connect()
        val body = ByteArray(30_000) { it.toByte() }
        RangeHttpServer(body).use { http ->
            val download = downloader(http)
            download.start(sample(body.size))
            waitUntil("done") { download.state.value is DownloadState.Done }
            assertArrayEquals(body, target.sink.content)
            assertEquals(1, liveStopped)
        }
    }

    @Test
    fun start_resumeAfterCut_continues() = test {
        connect()
        val body = ByteArray(60_000) { it.toByte() }
        RangeHttpServer(body, cutAfter = 12_000).use { first ->
            val download = downloader(first)
            download.start(sample(body.size))
            waitUntil("mismatch") {
                (download.state.value as? DownloadState.Failed)?.reason == DownloadFailReason.SIZE_MISMATCH
            }
            assertEquals(12_000L, target.sink.length())
        }
        RangeHttpServer(body).use { second ->
            val download = downloader(second)
            download.start(sample(body.size))
            waitUntil("done") { download.state.value is DownloadState.Done }
            assertArrayEquals(body, target.sink.content)
        }
    }

    @Test
    fun cancel_thenStateIsCancelled() = test {
        connect()
        val stall = CountDownLatch(1)
        RangeHttpServer(ByteArray(200_000), beforeBody = stall).use { http ->
            val download = downloader(http)
            download.start(sample(200_000))
            waitUntil("running") { download.state.value is DownloadState.Running }
            download.cancel()
            stall.countDown()
            waitUntil("cancelled") { download.state.value is DownloadState.Cancelled }
        }
    }

    @Test
    fun forbidden_isCameraRefused() = test {
        connect()
        RangeHttpServer(byteArrayOf(1), statusOverride = 403).use { http ->
            val download = downloader(http)
            download.start(sample(1))
            waitUntil("refused") {
                (download.state.value as? DownloadState.Failed)?.reason == DownloadFailReason.CAMERA_REFUSED
            }
        }
    }

    @Test
    fun linkLost_whileRunning_isResumableFailed() = test {
        connect()
        val stall = CountDownLatch(1)
        RangeHttpServer(ByteArray(100_000), beforeBody = stall).use { http ->
            val download = downloader(http)
            download.start(sample(100_000))
            waitUntil("running") { download.state.value is DownloadState.Running }
            session.disconnect()
            stall.countDown()
            waitUntil("link lost") {
                val failed = download.state.value as? DownloadState.Failed
                failed?.reason == DownloadFailReason.LINK_LOST && failed.resumable
            }
        }
    }

    @Test
    fun oneAtATime_secondStartIsIgnored() = test {
        connect()
        val stall = CountDownLatch(1)
        RangeHttpServer(ByteArray(50_000), beforeBody = stall).use { http ->
            val download = downloader(http)
            val first = sample(50_000)
            download.start(first)
            waitUntil("running") { download.state.value is DownloadState.Running }
            download.start(first.copy(name = "YDXJ0002.jpg"))
            assertEquals(first.name, (download.state.value as DownloadState.Running).progress.file.name)
            stall.countDown()
            waitUntil("done") { download.state.value is DownloadState.Done }
        }
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }

    @Test
    fun replaceExisting_clearsTheOldFile() = test {
        connect()
        target.completeUri = "memory:old"
        target.sink.write(byteArrayOf(1, 2, 3), 0, 3)
        val body = ByteArray(8_000) { 9 }
        RangeHttpServer(body).use { http ->
            val download = downloader(http)
            download.start(sample(body.size))
            download.replaceExisting()
            waitUntil("done") { download.state.value is DownloadState.Done }
            assertArrayEquals(body, target.sink.content)
        }
    }
}
