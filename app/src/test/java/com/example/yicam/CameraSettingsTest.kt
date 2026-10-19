/**
 * Responsible for: tests of the settings logic: reading the allowed values (StatusParser.options and CameraSettings against FakeCameraServer), hidden keys, unsaved choices, applying only the changed settings in the right order, refusals, the busy camera and the clock.
 * Not responsible for: the screen (SettingsScreen.kt) or the controls of the live screen (CameraControllerTest.kt).
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CameraSettingsTest {
    private lateinit var server: FakeCameraServer
    private lateinit var scope: CoroutineScope
    private lateinit var session: CameraSession
    private lateinit var settings: CameraSettings

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
        settings = CameraSettings(session, scope, clock = { "2026-10-19 23:30:00" })
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

    private suspend fun connectAndLoad() {
        session.connect()
        waitUntil("connected and settings read") {
            session.state.value is SessionState.Connected && session.settings.value != null
        }
        settings.load()
        waitUntil("loaded") { settings.state.value.rows.isNotEmpty() && !settings.state.value.loading }
    }

    private fun state() = settings.state.value
    private fun row(key: String) = state().rows.first { it.key == key }
    private fun sentSettings() = server.received.filter { it.contains("\"msg_id\":2,") }

    // ---- parser --------------------------------------------------------------------------------

    private fun reply(key: String, text: String) =
        CameraMessage.parse(Fixtures.optionsReply(key, text)) as CameraMessage.Response

    @Test
    fun parser_settableList() {
        val o = StatusParser.options(reply("video_standard", "settable:NTSC#PAL"), "video_standard")!!
        assertTrue(o.settable)
        assertEquals(listOf("NTSC", "PAL"), o.values)
    }

    @Test
    fun parser_readonlyList_readonlyEmpty_andFreeText() {
        val ro = StatusParser.options(reply("timelapse_photo", "readonly:off#2"), "timelapse_photo")!!
        assertFalse(ro.settable)
        assertEquals(listOf("off", "2"), ro.values)
        assertTrue(StatusParser.options(reply("sw_version", "readonly:"), "sw_version")!!.values.isEmpty())
        val free = StatusParser.options(reply("wifi_ssid", "settable:"), "wifi_ssid")!!
        assertTrue(free.settable)
        assertTrue(free.values.isEmpty())
    }

    @Test
    fun parser_slashInValuesIsKept() {
        val o = StatusParser.options(reply("burst_capture_number", "settable:3 p / s#7 p / 2s"), "burst_capture_number")!!
        assertEquals(listOf("3 p / s", "7 p / 2s"), o.values)
    }

    @Test
    fun parser_unknownShapes_giveNull() {
        assertNull(StatusParser.options(reply("x", "NTSC#PAL"), "x"))
        assertNull(StatusParser.options(reply("x", "settable:a#b"), "other"))
        val failed = CameraMessage.parse("""{"rval":-1,"msg_id":3}""") as CameraMessage.Response
        assertNull(StatusParser.options(failed, "x"))
    }

    // ---- loading -------------------------------------------------------------------------------

    @Test
    fun load_showsOnlySettableListsWithAChoice() = test {
        connectAndLoad()
        val keys = state().rows.map { it.key }.toSet()
        assertEquals(
            setOf("video_standard", "video_resolution", "video_stamp", "buzzer_volume", "buzzer_ring", "burst_capture_number"),
            keys,
        )
        assertEquals(SettingGroup.VIDEO, row("video_resolution").group)
        assertEquals(SettingGroup.SOUND_LED, row("buzzer_volume").group)
        assertEquals(SettingGroup.PHOTO, row("burst_capture_number").group)
        assertEquals("NTSC", row("video_standard").current)
        assertTrue(row("buzzer_ring").isSwitch)
        assertFalse(row("buzzer_volume").isSwitch)
    }

    @Test
    fun load_neverAsksForHiddenOrSecretKeys() = test {
        server.leakSecrets = true
        connectAndLoad()
        val asked = server.received.filter { it.contains("\"msg_id\":3,") && it.contains("\"param\"") }
        for (hidden in listOf("capture_mode", "system_mode", "camera_clock", "wifi_ssid", "wifi_password", "serial_number", "app_status")) {
            assertTrue("asked for $hidden", asked.none { it.contains("\"param\":\"$hidden\"") })
        }
    }

    @Test
    fun rows_areInCatalogOrder() = test {
        connectAndLoad()
        val keys = state().rows.map { it.key }
        assertEquals(listOf("video_standard", "video_resolution", "video_stamp", "burst_capture_number", "buzzer_volume", "buzzer_ring"), keys)
    }

    @Test
    fun load_whenNotConnected_saysSo() = test {
        settings.load()
        waitUntil("note") { state().note != null }
        assertEquals(SettingsNote.NOT_CONNECTED, state().note!!.kind)
    }

    @Test
    fun disconnect_clearsTheRows() = test {
        connectAndLoad()
        session.disconnect()
        waitUntil("cleared") { state().rows.isEmpty() && !state().available }
    }

    // ---- editing and applying -----------------------------------------------------------------

    @Test
    fun edit_marksChangedRows_andEditingBackClearsTheMark() = test {
        connectAndLoad()
        settings.edit("buzzer_volume", "mute")
        waitUntil("edited") { state().changedCount == 1 }
        assertEquals("mute", row("buzzer_volume").value)
        settings.edit("buzzer_volume", "low") // the camera's current value
        waitUntil("unmarked") { state().changedCount == 0 }
    }

    @Test
    fun discard_dropsAllChoices() = test {
        connectAndLoad()
        settings.edit("buzzer_volume", "mute")
        settings.edit("video_stamp", "date")
        waitUntil("two edits") { state().changedCount == 2 }
        settings.discard()
        waitUntil("none") { state().changedCount == 0 }
        assertEquals(0, sentSettings().size)
    }

    @Test
    fun apply_sendsOnlyChangedSettings_andUpdatesTheRows() = test {
        connectAndLoad()
        settings.edit("buzzer_volume", "mute")
        settings.edit("video_stamp", "date/time")
        waitUntil("two edits") { state().changedCount == 2 }
        settings.apply()
        waitUntil("applied") { state().note?.kind == SettingsNote.APPLIED }
        val sent = sentSettings()
        assertEquals(2, sent.size)
        assertTrue(sent.any { it == """{"msg_id":2,"token":1,"type":"buzzer_volume","param":"mute"}""" })
        assertTrue(sent.any { it == """{"msg_id":2,"token":1,"type":"video_stamp","param":"date\/time"}""" })
        assertEquals(0, state().changedCount)
        assertEquals("mute", row("buzzer_volume").current)
        assertEquals("mute", server.settingValues["buzzer_volume"])
    }

    @Test
    fun apply_sendsTheVideoStandardFirst_andReadsTheResolutionListAgain() = test {
        connectAndLoad()
        settings.edit("video_stamp", "date")
        settings.edit("video_standard", "PAL")
        waitUntil("two edits") { state().changedCount == 2 }
        settings.apply()
        waitUntil("applied") { state().note?.kind == SettingsNote.APPLIED }
        assertTrue(sentSettings().first().contains("\"type\":\"video_standard\""))
        assertTrue(row("video_resolution").options.any { it.contains("50P") })
        assertFalse(row("video_resolution").options.any { it.contains("60P") })
    }

    @Test
    fun apply_refusal_keepsTheChoiceMarked_andNamesTheKey() = test {
        connectAndLoad()
        settings.edit("buzzer_volume", "mute")
        waitUntil("edited") { state().changedCount == 1 }
        server.forcedRval[2] = -1
        settings.apply()
        waitUntil("failed") { state().note?.kind == SettingsNote.FAILED }
        assertTrue(state().note!!.detail.contains("buzzer_volume"))
        assertEquals(1, state().changedCount)
        assertEquals("low", row("buzzer_volume").current)
    }

    @Test
    fun apply_whileRecording_isBlocked() = test {
        connectAndLoad()
        server.appStatus = "record"
        session.refresh()
        waitUntil("busy") { state().cameraBusy }
        settings.edit("buzzer_volume", "mute")
        waitUntil("edited") { state().changedCount == 1 }
        settings.apply()
        waitUntil("note") { state().note?.kind == SettingsNote.CAMERA_BUSY }
        assertEquals(0, sentSettings().size)
    }

    @Test
    fun apply_withNothingChanged_sendsNothing() = test {
        connectAndLoad()
        settings.apply()
        delay(200)
        assertEquals(0, sentSettings().size)
        assertNull(state().note)
    }

    // ---- clock ---------------------------------------------------------------------------------

    @Test
    fun syncClock_sendsThePhoneTime() = test {
        connectAndLoad()
        settings.syncClock()
        waitUntil("synced") { state().note?.kind == SettingsNote.CLOCK_SYNCED }
        assertEquals(
            """{"msg_id":2,"token":1,"type":"camera_clock","param":"2026-10-19 23:30:00"}""",
            sentSettings().single(),
        )
    }

    @Test
    fun syncClock_refused_showsTheError() = test {
        connectAndLoad()
        server.forcedRval[2] = -9
        settings.syncClock()
        waitUntil("failed") { state().note?.kind == SettingsNote.FAILED }
        assertTrue(state().note!!.detail.contains("camera_clock"))
        assertNotNull(state().note)
    }
}
