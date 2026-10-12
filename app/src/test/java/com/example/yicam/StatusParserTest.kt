/**
 * Responsible for: unit tests of StatusParser on real camera replies (battery, SD memory, settings, secrets left out).
 * Not responsible for: tests of sockets or the session (CameraApiTest.kt, CameraSessionTest.kt).
 */
package com.example.yicam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusParserTest {
    private fun reply(json: String): CameraMessage.Response = CameraMessage.parse(json) as CameraMessage.Response

    @Test
    fun battery_realReply_givesPercent_andNotCharging() {
        val battery = StatusParser.battery(reply(Fixtures.BATTERY_REPLY))
        assertEquals(82, battery.percent)
        assertEquals(false, battery.charging)
    }

    @Test
    fun battery_adapterType_meansCharging() {
        val battery = StatusParser.battery(reply("""{"rval":0,"msg_id":13,"type":"adapter","param":"100"}"""))
        assertEquals(100, battery.percent)
        assertEquals(true, battery.charging)
    }

    @Test
    fun battery_unknownType_leavesChargingUnknown() {
        val battery = StatusParser.battery(reply("""{"rval":0,"msg_id":13,"param":"40"}"""))
        assertEquals(40, battery.percent)
        assertNull(battery.charging)
    }

    @Test
    fun percentOf_acceptsTextAndNumbers_andRejectsNonsense() {
        assertEquals(82, StatusParser.percentOf("82"))
        assertEquals(82, StatusParser.percentOf(82L))
        assertEquals(82, StatusParser.percentOf(82.0))
        assertEquals(0, StatusParser.percentOf(" 0 "))
        assertNull(StatusParser.percentOf("101"))
        assertNull(StatusParser.percentOf("-1"))
        assertNull(StatusParser.percentOf("abc"))
        assertNull(StatusParser.percentOf(null))
    }

    @Test
    fun kilobytes_realReplies() {
        assertEquals(Fixtures.SD_TOTAL_KB, StatusParser.kilobytes(reply(Fixtures.SD_TOTAL_REPLY)))
        assertEquals(Fixtures.SD_FREE_KB, StatusParser.kilobytes(reply(Fixtures.SD_FREE_REPLY)))
    }

    @Test
    fun kilobytes_missingOrNegative_isNull() {
        assertNull(StatusParser.kilobytes(reply("""{"rval":0,"msg_id":5}""")))
        assertNull(StatusParser.kilobytes(reply("""{"rval":0,"msg_id":5,"param":-5}""")))
    }

    @Test
    fun settings_realReply_hasAllKeysExceptSecrets() {
        val values = StatusParser.settings(reply(Fixtures.SETTINGS_REPLY))
        assertEquals(Fixtures.SETTINGS_PARAM_COUNT - 2, values.size)
        assertFalse(values.containsKey("wifi_password"))
        assertFalse(values.containsKey("serial_number"))
        assertEquals("NTSC", values["video_standard"])
        assertEquals("idle", values["app_status"])
        assertEquals("insert", values["sd_card_status"])
        assertEquals("3 minutes", values["auto_power_off"])
    }

    @Test
    fun settings_keepsTheCamerasOrder_andReadsEscapedSlashes() {
        val values = StatusParser.settings(reply(Fixtures.SETTINGS_REPLY))
        assertEquals("camera_clock", values.keys.first())
        assertEquals("7 p / s", values["burst_capture_number"])
    }

    @Test
    fun settings_numbersAndBooleansBecomeText_andOddItemsAreSkipped() {
        val values = StatusParser.settings(
            reply("""{"rval":0,"msg_id":3,"param":[{"a":5},{"b":2.5},{"c":true},{"d":null},"junk",{"wifi_password":"x"}]}"""),
        )
        assertEquals(mapOf("a" to "5", "b" to "2.5", "c" to "true"), values)
    }

    @Test
    fun settings_withoutParam_isEmpty() {
        assertTrue(StatusParser.settings(reply("""{"rval":0,"msg_id":3}""")).isEmpty())
    }
}
