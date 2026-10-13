/**
 * Responsible for: integration tests of CameraController with a real CameraSession against FakeCameraServer (photo, recording, time-lapse stop, modes, zoom rate and clamping, refusals, events, state derived from the settings).
 * Not responsible for: the screen (ControlPanel.kt) or the session itself (CameraSessionTest.kt).
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CameraControllerTest {
    private lateinit var server: FakeCameraServer
    private lateinit var scope: CoroutineScope
    private lateinit var session: CameraSession
    private lateinit var controller: CameraController

    @Before
    fun setUp() {
        server = FakeCameraServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        session = CameraSession(
            FakeCameraNetwork(),
            TrafficLog(),
            scope,
            CameraSession.Config(
                host = "127.0.0.1",
                port = server.port,
                pollBatteryMs = 60_000L,
                pollSdMs = 60_000L,
                eventRefreshDelayMs = 20L,
            ),
        )
        controller = CameraController(session, scope, CameraController.Config(zoomMinGapMs = 80L))
    }

    @After
    fun tearDown() {
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

    private suspend fun connect() {
        session.connect()
        waitUntil("connected and settings read") {
            session.state.value is SessionState.Connected && session.settings.value != null
        }
    }

    private fun state() = controller.state.value

    private fun sent(msgId: Int) = server.received.filter { it.contains("\"msg_id\":$msgId,") }

    // ---- state -----------------------------------------------------------------------------

    @Test
    fun notConnected_nothingIsAvailable_andACommandSaysSo() = test {
        assertFalse(state().available)
        controller.takePhoto()
        waitUntil("note") { state().note != null }
        assertEquals(ControlNote.NOT_CONNECTED, state().note!!.kind)
        assertEquals(0, sent(769).size)
    }

    @Test
    fun connected_showsModeCaptureModeAndZoomFromTheCamera() = test {
        server.zoom = 35
        connect()
        waitUntil("zoom read") { state().zoom == 35 }
        val s = state()
        assertTrue(s.available)
        assertEquals(CameraMode.VIDEO, s.mode) // fixture: system_mode record
        assertEquals(CaptureMode.SINGLE, s.captureMode) // fixture: capture_mode "precise quality"
        assertFalse(s.recording)
        assertFalse(s.timelapseRunning)
        assertEquals("""{"msg_id":15,"token":1,"type":"current"}""", sent(15).first())
    }

    @Test
    fun disconnect_clearsTheControls() = test {
        connect()
        session.disconnect()
        waitUntil("unavailable") { !state().available && state().zoom == null }
    }

    // ---- photo and video ---------------------------------------------------------------------

    @Test
    fun takePhoto_sendsCommand769_andReportsIt() = test {
        connect()
        controller.takePhoto()
        waitUntil("note") { state().note?.kind == ControlNote.PHOTO_REQUESTED }
        assertEquals("""{"msg_id":769,"token":1}""", sent(769).single())
        assertFalse(state().busy)
    }

    @Test
    fun recording_startsAndStops_andTheStateFollows() = test {
        connect()
        controller.startRecording()
        waitUntil("recording") { state().recording }
        assertEquals("""{"msg_id":513,"token":1}""", sent(513).single())

        controller.stopRecording()
        waitUntil("not recording") { !state().recording }
        assertEquals("""{"msg_id":514,"token":1}""", sent(514).single())
    }

    @Test
    fun videoRecordComplete_showsTheFileName() = test {
        connect()
        controller.startRecording()
        waitUntil("recording") { state().recording }
        controller.stopRecording()
        waitUntil("saved note") { state().note?.kind == ControlNote.SAVED }
        assertEquals("YDXJ0042.mp4", state().note!!.detail)
    }

    @Test
    fun refusal_showsTheRval_andDoesNotChangeTheState() = test {
        connect()
        server.forcedRval[513] = -21
        controller.startRecording()
        waitUntil("failure note") { state().note?.kind == ControlNote.FAILED }
        assertEquals("rval -21", state().note!!.detail)
        assertFalse(state().recording)
    }

    @Test
    fun aSecondCommand_whileOneIsWaiting_isIgnored() = test {
        connect()
        server.replyDelayMs = 200
        controller.takePhoto()
        controller.takePhoto()
        waitUntil("note") { state().note?.kind == ControlNote.PHOTO_REQUESTED }
        assertEquals(1, sent(769).size)
    }

    @Test
    fun timelapseRunning_isShown_andCanBeStopped() = test {
        server.settingValues["precise_cont_capturing"] = "on"
        connect()
        waitUntil("running") { state().timelapseRunning }
        controller.stopTimelapse()
        waitUntil("stopped") { !state().timelapseRunning }
        assertEquals("""{"msg_id":770,"token":1}""", sent(770).single())
    }

    // ---- modes -------------------------------------------------------------------------------

    @Test
    fun setMode_sendsSystemMode_andTheSettingsFollow() = test {
        connect()
        controller.setMode(CameraMode.PHOTO)
        waitUntil("photo mode") { state().mode == CameraMode.PHOTO }
        assertEquals("""{"msg_id":2,"token":1,"type":"system_mode","param":"capture"}""", sent(2).single())
    }

    @Test
    fun setCaptureMode_sendsTheCamerasOwnValue() = test {
        connect()
        controller.setCaptureMode(CaptureMode.TIMELAPSE)
        waitUntil("timelapse") { state().captureMode == CaptureMode.TIMELAPSE }
        assertEquals(
            """{"msg_id":2,"token":1,"type":"capture_mode","param":"precise quality cont."}""",
            sent(2).single(),
        )
    }

    // ---- zoom --------------------------------------------------------------------------------

    @Test
    fun zoom_isShownAtOnce_andSentAsAString() = test {
        connect()
        controller.setZoom(40)
        waitUntil("zoom shown") { state().zoom == 40 }
        waitUntil("zoom sent") { sent(14).isNotEmpty() }
        assertEquals("""{"msg_id":14,"token":1,"type":"fast","param":"40"}""", sent(14).first())
    }

    @Test
    fun zoom_isClampedTo0To100() = test {
        connect()
        controller.setZoom(250)
        waitUntil("zoom 100") { state().zoom == 100 }
        waitUntil("first zoom") { sent(14).isNotEmpty() }
        controller.setZoom(-5)
        waitUntil("zoom 0") { state().zoom == 0 }
        waitUntil("second zoom") { sent(14).size >= 2 }
        assertTrue(sent(14).last().contains("\"param\":\"0\""))
    }

    @Test
    fun zoomBurst_sendsFewCommands_andTheLastValueWins() = test {
        connect()
        for (level in 1..40) controller.setZoom(level)
        waitUntil("last value reached the camera") { server.zoom == 40 }
        delay(300)
        val count = sent(14).size
        assertTrue("sent $count zoom commands for 40 changes", count <= 6)
        assertEquals(40, server.zoom)
    }

    @Test
    fun zoomRefused_showsANote() = test {
        connect()
        server.forcedRval[14] = -1
        controller.setZoom(10)
        waitUntil("note") { state().note?.kind == ControlNote.FAILED }
        assertTrue(state().note!!.detail.contains("zoom"))
    }

    @Test
    fun dismissNote_clearsIt() = test {
        connect()
        controller.takePhoto()
        waitUntil("note") { state().note != null }
        controller.dismissNote()
        waitUntil("note cleared") { state().note == null }
        assertNull(state().note)
    }
}
