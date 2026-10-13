/**
 * Responsible for: integration tests of CameraSession against FakeCameraServer and FakeCameraNetwork (status reading, polling, events, reconnect, camera off, errors that stop retrying, background pause, disconnect).
 * Not responsible for: tests of the protocol layer (CameraApiTest.kt) or of the pure helpers (BackoffTest.kt, StatusParserTest.kt).
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

class CameraSessionTest {
    private lateinit var server: FakeCameraServer
    private lateinit var network: FakeCameraNetwork
    private lateinit var scope: CoroutineScope
    private lateinit var log: TrafficLog
    private lateinit var session: CameraSession

    @Before
    fun setUp() {
        server = FakeCameraServer()
        network = FakeCameraNetwork()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        log = TrafficLog()
        session = newSession()
    }

    @After
    fun tearDown() {
        session.disconnect()
        scope.cancel()
        server.close()
    }

    private fun newSession(pollBatteryMs: Long = 60_000L, pollSdMs: Long = 60_000L): CameraSession =
        CameraSession(
            network,
            log,
            scope,
            CameraSession.Config(
                host = "127.0.0.1",
                port = server.port,
                pollBatteryMs = pollBatteryMs,
                pollSdMs = pollSdMs,
                sdRecheckMs = 50L,
                eventRefreshDelayMs = 50L,
                wifiTimeoutMs = 200L,
                wifiGraceMs = 80L,
                cameraOffRetryMs = 150L,
                minAttemptGapMs = 10L,
                backoff = Backoff(stepsMs = listOf(20L, 20L, 40L), spread = 0.0),
            ),
        )

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        repeat(300) {
            if (condition()) return
            delay(25)
        }
        fail("timed out waiting for: $what")
    }

    private fun connectedToken(): Int? = (session.state.value as? SessionState.Connected)?.token

    private fun countOfMsgId(id: Int): Int = server.received.count { it.contains("\"msg_id\":$id,") }

    /** Connects and waits until the first full read has finished. */
    private suspend fun connectAndWaitForStatus() {
        session.connect()
        waitUntil("connected") { session.state.value is SessionState.Connected }
        waitUntil("first status") {
            val s = session.status.value
            s.batteryPercent != null && s.sdTotalKb != null && s.sdFreeKb != null && s.appStatus != null
        }
        delay(100) // let the monitor finish subscribing to every flow
    }

    // ---- status ------------------------------------------------------------------------------

    @Test
    fun connect_publishesConnected_andReadsBatterySdAndSettings() = test {
        connectAndWaitForStatus()
        val status = session.status.value
        assertEquals(82, status.batteryPercent)
        assertEquals(Fixtures.SD_TOTAL_KB, status.sdTotalKb)
        assertEquals(Fixtures.SD_FREE_KB, status.sdFreeKb)
        assertEquals("idle", status.appStatus)
        assertEquals("insert", status.sdCardStatus)
        assertEquals(1, connectedToken())
    }

    @Test
    fun settingsSnapshot_hasNoSecrets_evenWhenTheCameraSendsThem() = test {
        server.leakSecrets = true
        connectAndWaitForStatus()
        val values = session.settings.value!!.values
        assertEquals(Fixtures.SETTINGS_PARAM_COUNT - 2, values.size)
        assertFalse(values.containsKey("wifi_password"))
        assertFalse(values.containsKey("serial_number"))
        assertFalse(log.asText().contains("hunter2"))
    }

    @Test
    fun batteryEvent_updatesTheLevelAtOnce() = test {
        connectAndWaitForStatus()
        server.pushEvent(Fixtures.batteryEvent(55))
        waitUntil("level 55") { session.status.value.batteryPercent == 55 }
    }

    @Test
    fun aCameraEvent_makesTheSessionReadTheModeAgain() = test {
        connectAndWaitForStatus()
        assertEquals("idle", session.status.value.appStatus)
        server.appStatus = "recording"
        server.pushEvent("""{ "msg_id": 7, "type": "start_video_record" }""")
        waitUntil("mode updated") { session.status.value.appStatus == "recording" }
    }

    @Test
    fun refresh_readsEverythingAgain() = test {
        connectAndWaitForStatus()
        val before = countOfMsgId(3)
        session.refresh()
        waitUntil("second settings read") { countOfMsgId(3) > before }
    }

    @Test
    fun tinyCardSize_isNotShownAsACard_andIsCheckedAgainUntilItIsReal() = test {
        server.tinySd = true
        session.connect()
        waitUntil("connected") { session.state.value is SessionState.Connected }
        waitUntil("problem noticed") { session.status.value.sdUnusableReportedKb == Fixtures.SMALL_SD_TOTAL_KB }
        assertNull(session.status.value.sdTotalKb)
        assertNull(session.status.value.sdFreeKb)
        server.tinySd = false
        waitUntil("real card seen") { session.status.value.sdTotalKb == Fixtures.SD_TOTAL_KB }
        assertEquals(Fixtures.SD_FREE_KB, session.status.value.sdFreeKb)
        assertNull(session.status.value.sdUnusableReportedKb)
    }

    // ---- polling and background ---------------------------------------------------------------

    @Test
    fun batteryIsPolledAtTheConfiguredInterval() = test {
        session = newSession(pollBatteryMs = 50L)
        connectAndWaitForStatus()
        waitUntil("three battery polls") { countOfMsgId(13) >= 3 }
    }

    @Test
    fun polling_pausesInTheBackground_andResumesInTheForeground() = test {
        session = newSession(pollBatteryMs = 50L)
        connectAndWaitForStatus()
        session.setForeground(false)
        delay(200) // an in-flight poll may still finish
        val paused = countOfMsgId(13)
        delay(400)
        assertEquals(paused, countOfMsgId(13))
        session.setForeground(true)
        waitUntil("polling resumed") { countOfMsgId(13) > paused }
    }

    // ---- losing and regaining the link --------------------------------------------------------

    @Test
    fun droppedConnection_reconnectsByItself_withANewToken() = test {
        connectAndWaitForStatus()
        server.dropClient()
        waitUntil("connected again with token 2") { connectedToken() == 2 }
        assertEquals(2, server.acceptedConnections.get())
    }

    @Test
    fun reconnect_readsTheStatusAgain() = test {
        connectAndWaitForStatus()
        val settingsReadsBefore = countOfMsgId(3)
        server.dropClient()
        waitUntil("connected again") { connectedToken() == 2 }
        waitUntil("settings read again") { countOfMsgId(3) > settingsReadsBefore }
    }

    @Test
    fun wifiLost_thenGone_endsInCameraOff_andRecoversWhenTheWifiReturns() = test {
        connectAndWaitForStatus()
        network.bindFailure = CameraException.WifiNotFound(false, "gone")
        network.emitLost()
        waitUntil("camera off") { session.state.value == SessionState.CameraOff }
        network.bindFailure = null
        waitUntil("connected again") { session.state.value is SessionState.Connected }
        assertEquals(2, server.acceptedConnections.get())
    }

    @Test
    fun reconnectingState_carriesTheAttemptNumberAndTheReason() = test {
        connectAndWaitForStatus()
        network.bindFailure = CameraException.WifiNotFound(false, "gone")
        network.emitLost()
        waitUntil("reconnecting state") {
            val s = session.state.value
            s is SessionState.Reconnecting && s.attempt >= 1 && s.reason == FailReason.NO_WIFI
        }
    }

    // ---- errors that stop retrying ------------------------------------------------------------

    @Test
    fun firstConnect_withoutCameraWifi_isAnError_andDoesNotRetry() = test {
        network.bindFailure = CameraException.WifiNotFound(false, "no camera wifi")
        session.connect()
        waitUntil("error") { session.state.value is SessionState.Error }
        assertEquals(FailReason.NO_WIFI, (session.state.value as SessionState.Error).reason)
        delay(300)
        assertEquals(1, network.bindCalls.get())
    }

    @Test
    fun afterALoss_anotherWifi_isNotFinal_theSessionKeepsWaiting() = test {
        connectAndWaitForStatus()
        network.bindFailure = CameraException.WifiNotFound(true, "home wifi")
        network.emitLost()
        waitUntil("several attempts") { network.bindCalls.get() >= 4 }
        assertTrue(session.state.value !is SessionState.Error)
        network.bindFailure = null
        waitUntil("connected again") { session.state.value is SessionState.Connected }
    }

    @Test
    fun firstConnect_onAnotherWifi_isAnError_andDoesNotRetry() = test {
        network.bindFailure = CameraException.WifiNotFound(true, "other wifi")
        session.connect()
        waitUntil("error") { session.state.value is SessionState.Error }
        assertEquals(FailReason.NOT_CAMERA_NETWORK, (session.state.value as SessionState.Error).reason)
        delay(300)
        assertEquals(1, network.bindCalls.get())
    }

    @Test
    fun permissionDenied_isShown_andNothingIsTried() = test {
        session.onPermissionDenied()
        assertEquals(SessionState.PermissionRequired, session.state.value)
        delay(100)
        assertEquals(0, network.bindCalls.get())
        assertEquals(0, server.acceptedConnections.get())
    }

    @Test
    fun cameraThatDoesNotAnswerTheSocket_isRetriedWithBackoff() = test {
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        session = CameraSession(
            network,
            log,
            scope,
            CameraSession.Config(
                host = "127.0.0.1",
                port = closedPort,
                wifiTimeoutMs = 200L,
                wifiGraceMs = 80L,
                cameraOffRetryMs = 150L,
                minAttemptGapMs = 10L,
                backoff = Backoff(stepsMs = listOf(20L, 20L, 40L), spread = 0.0),
            ),
        )
        session.connect()
        waitUntil("several attempts") { network.bindCalls.get() >= 3 }
        val state = session.state.value
        assertTrue("state was $state", state is SessionState.Reconnecting || state == SessionState.Connecting)
    }

    // ---- user disconnect ----------------------------------------------------------------------

    @Test
    fun disconnect_stopsEverything_andClearsTheKnownStatus() = test {
        connectAndWaitForStatus()
        session.disconnect()
        assertEquals(SessionState.Disconnected, session.state.value)
        assertNull(session.settings.value)
        assertNull(session.status.value.batteryPercent)
        val accepted = server.acceptedConnections.get()
        delay(400)
        assertEquals(accepted, server.acceptedConnections.get())
        assertTrue(network.releaseCalls.get() >= 1)
        assertNull(session.currentApi)
    }

    @Test
    fun connectAgainAfterDisconnect_worksAndTakesANewToken() = test {
        connectAndWaitForStatus()
        session.disconnect()
        session.connect()
        waitUntil("connected with token 2") { connectedToken() == 2 }
    }
}
