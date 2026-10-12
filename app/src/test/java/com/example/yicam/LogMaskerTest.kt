/**
 * Responsible for: unit tests of LogMasker and TrafficLog (secret masking, line format, ring buffer).
 * Not responsible for: sockets or camera sessions.
 */
package com.example.yicam

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogMaskerTest {
    @Test
    fun masksPasswordAndSerialInSettingsReply() {
        val reply = Fixtures.SETTINGS_REPLY
            .replace("\"wifi_password\": \"*\"", "\"wifi_password\": \"hunter2\"")
            .replace("\"serial_number\": \"*\"", "\"serial_number\": \"ABC123456\"")
        val masked = LogMasker.mask(reply)
        assertFalse(masked.contains("hunter2"))
        assertFalse(masked.contains("ABC123456"))
        assertTrue(masked.contains("\"wifi_password\": \"*\""))
        assertTrue(masked.contains("\"serial_number\": \"*\""))
        assertTrue(masked.contains("YDXJ_3098638")) // ssid is not secret
    }

    @Test
    fun masksCompactStyleAndEscapedQuotesInsideSecret() {
        val masked = LogMasker.mask("""{"wifi_password":"pa\"ss\\word","x":"keep"}""")
        assertEquals("""{"wifi_password":"*","x":"keep"}""", masked)
    }

    @Test
    fun masksSetCommand_inBothFieldOrders() {
        assertEquals(
            """{"msg_id":2,"token":1,"type":"wifi_password","param":"*"}""",
            LogMasker.mask("""{"msg_id":2,"token":1,"type":"wifi_password","param":"secret"}"""),
        )
        assertEquals(
            """{"msg_id":2,"param":"*","type":"wifi_password"}""",
            LogMasker.mask("""{"msg_id":2,"param":"secret","type":"wifi_password"}"""),
        )
    }

    @Test
    fun leavesOtherTextUntouched() {
        val text = """{"msg_id":2,"token":1,"type":"buzzer_volume","param":"low"}"""
        assertEquals(text, LogMasker.mask(text))
    }

    @Test
    fun trafficLog_formatsLinesLikeThePhoneLog_andMasks() {
        val log = TrafficLog(now = { LocalTime.of(14, 25, 3, 407_000_000) })
        log.tx("""{"msg_id":3,"token":2}""")
        log.rx("""{ "wifi_password": "secret" }""")
        log.sys("TCP connected")
        assertEquals(
            listOf(
                """[14:25:03.407] >> {"msg_id":3,"token":2}""",
                """[14:25:03.407] << { "wifi_password": "*" }""",
                "[14:25:03.407] TCP connected",
            ),
            log.lines.value,
        )
    }

    @Test
    fun trafficLog_keepsOnlyTheLastLines() {
        val log = TrafficLog(capacity = 3)
        for (i in 1..10) log.sys("line $i")
        assertEquals(3, log.lines.value.size)
        assertTrue(log.lines.value.first().endsWith("line 8"))
        assertTrue(log.asText().endsWith("line 10"))
        log.clear()
        assertTrue(log.lines.value.isEmpty())
    }
}
