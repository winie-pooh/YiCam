/**
 * Responsible for: integration tests of LiveViewController with a real CameraSession against FakeCameraServer and a FakeStreamPlayer (preview command, blocked cases, retries, timeouts, restart after a break, stop).
 * Not responsible for: tests of the link (CameraSessionTest.kt) or of real video playback (done by hand on a phone).
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

class LiveViewControllerTest {
    private lateinit var server: FakeCameraServer
    private lateinit var network: FakeCameraNetwork
    private lateinit var scope: CoroutineScope
    private lateinit var session: CameraSession
    private lateinit var player: FakeStreamPlayer
    private lateinit var controller: LiveViewController

    @Before
    fun setUp() {
        server = FakeCameraServer()
        network = FakeCameraNetwork()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        session = CameraSession(
            network,
            TrafficLog(),
            scope,
            CameraSession.Config(host = "127.0.0.1", port = server.port, pollBatteryMs = 60_000L, pollSdMs = 60_000L),
        )
        player = FakeStreamPlayer()
        controller = LiveViewController(
            session,
            player,
            scope,
            LiveViewController.Config(
                rtspUrl = "rtsp://test/live",
                maxStartAttempts = 2,
                retryDelayMs = 20L,
                firstFrameTimeoutMs = 300L,
            ),
        )
    }

    @After
    fun tearDown() {
        controller.stop()
        session.disconnect()
        scope.cancel()
        server.close()
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        repeat(300) {
            if (condition()) return
            delay(25)
        }
        fail("timed out waiting for: $what")
    }

    private suspend fun connectSession() {
        session.connect()
        waitUntil("connected and settings read") {
            session.state.value is SessionState.Connected && session.settings.value != null
        }
    }

    private fun problem(): LiveProblem? = (controller.state.value as? LiveState.Problem)?.problem

    private fun previewCommandsSent(): Int = server.received.count { it.contains("\"msg_id\":259,") }

    @Test
    fun notConnected_isAProblem_andNothingIsStarted() = test {
        controller.start()
        waitUntil("problem") { problem() == LiveProblem.NOT_CONNECTED }
        assertEquals(0, player.startedUrls.size)
    }

    @Test
    fun start_sendsThePreviewCommand_thenPlaysTheStream() = test {
        connectSession()
        controller.start()
        waitUntil("player started") { player.startedUrls.size == 1 }
        assertEquals("rtsp://test/live", player.startedUrls[0])
        assertTrue(server.received.any { it.contains("\"msg_id\":259,") && it.contains("none_force") })
        player.emitFirstFrame()
        waitUntil("playing") { controller.state.value is LiveState.Playing }
    }

    @Test
    fun recording_blocksTheStart_andNoCommandIsSent() = test {
        server.appStatus = "recording"
        connectSession()
        controller.start()
        waitUntil("problem") { problem() == LiveProblem.RECORDING }
        assertEquals(0, previewCommandsSent())
        assertEquals(0, player.startedUrls.size)
    }

    @Test
    fun cameraRefusesThePreview_isAProblem_andThePlayerIsNotStarted() = test {
        server.previewRval = -9
        connectSession()
        controller.start()
        waitUntil("problem") { problem() == LiveProblem.PREVIEW_COMMAND_FAILED }
        assertEquals(0, player.startedUrls.size)
    }

    @Test
    fun playerErrorBeforeThePicture_isRetriedOnce_thenFails() = test {
        connectSession()
        controller.start()
        waitUntil("first start") { player.startedUrls.size == 1 }
        player.emitError("boom one")
        waitUntil("second start") { player.startedUrls.size == 2 }
        player.emitError("boom two")
        waitUntil("failed") { problem() == LiveProblem.STREAM_FAILED }
        assertTrue((controller.state.value as LiveState.Problem).detail.contains("boom two"))
    }

    @Test
    fun noPictureInTime_endsAsATimeout() = test {
        connectSession()
        controller.start()
        waitUntil("timeout") { problem() == LiveProblem.STREAM_TIMEOUT }
        assertEquals(2, player.startedUrls.size)
    }

    @Test
    fun streamThatBreaksWhilePlaying_isStartedAgain() = test {
        connectSession()
        controller.start()
        waitUntil("first start") { player.startedUrls.size == 1 }
        player.emitFirstFrame()
        waitUntil("playing") { controller.state.value is LiveState.Playing }
        player.emitError("lost")
        waitUntil("second start") { player.startedUrls.size == 2 }
        player.emitFirstFrame()
        waitUntil("playing again") { controller.state.value is LiveState.Playing }
    }

    @Test
    fun stop_stopsThePlayer_andGoesIdle() = test {
        connectSession()
        controller.start()
        waitUntil("player started") { player.startedUrls.size == 1 }
        val stopsBefore = player.stopCalls.get()
        controller.stop()
        assertEquals(LiveState.Idle, controller.state.value)
        assertTrue(player.stopCalls.get() > stopsBefore)
    }
}
