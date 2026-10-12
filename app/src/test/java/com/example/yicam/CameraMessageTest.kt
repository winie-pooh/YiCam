/**
 * Responsible for: unit tests of CameraMessage (reply/event classification, tolerant param types, request encoding) on real camera messages.
 * Not responsible for: sockets or sessions.
 */
package com.example.yicam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMessageTest {
    private fun response(text: String) = CameraMessage.parse(text) as CameraMessage.Response

    @Test
    fun sessionReply_paramIsANumber() {
        val r = response(Fixtures.SESSION_REPLY_1)
        assertEquals(257, r.msgId)
        assertTrue(r.isOk)
        assertEquals(1L, r.paramLong())
        assertEquals("1", r.paramString())
    }

    @Test
    fun batteryReply_paramIsAString_butReadsAsNumberToo() {
        val r = response(Fixtures.BATTERY_REPLY)
        assertEquals("82", r.paramString())
        assertEquals(82L, r.paramLong())
    }

    @Test
    fun settingsReply_paramIsAList() {
        val r = response(Fixtures.SETTINGS_REPLY)
        assertEquals(Fixtures.SETTINGS_PARAM_COUNT, r.paramList()!!.size)
        assertNull(r.paramLong())
    }

    @Test
    fun invalidTokenReply_hasRvalMinus4() {
        val r = response("""{"rval":-4,"msg_id":13}""")
        assertEquals(-4, r.rval)
        assertFalse(r.isOk)
    }

    @Test
    fun chdirReply_hasPwd() {
        assertEquals("/tmp/fuse_d/DCIM", response(Fixtures.CHDIR_REPLY).fields["pwd"])
    }

    @Test
    fun msgId7_isAnEvent_evenWithOddSpacing() {
        val e = CameraMessage.parse(Fixtures.BATTERY_EVENT) as CameraMessage.Event
        assertEquals("battery", e.type)
        assertEquals("82", e.param)
        val vf = CameraMessage.parse(Fixtures.VF_START_EVENT) as CameraMessage.Event
        assertEquals("vf_start", vf.type)
        assertNull(vf.param)
    }

    @Test
    fun unknownEventType_isStillAnEvent() {
        val e = CameraMessage.parse("""{"msg_id":7,"type":"something_new","param":"x"}""") as CameraMessage.Event
        assertEquals("something_new", e.type)
    }

    @Test
    fun notJson_notObject_orNoMsgId_isMalformed() {
        for (text in listOf("hello", "[1,2]", """{"rval":0}""", "{")) {
            assertTrue(text, CameraMessage.parse(text) is CameraMessage.Malformed)
        }
    }

    @Test
    fun encodeRequest_matchesTheFormatThatWorkedOnTheCamera() {
        assertEquals("""{"msg_id":257,"token":0}""", CameraMessage.encodeRequest(257, 0))
        assertEquals("""{"msg_id":3,"token":2}""", CameraMessage.encodeRequest(3, 2))
        assertEquals(
            """{"msg_id":1283,"token":1,"param":"\/tmp\/fuse_d\/DCIM"}""",
            CameraMessage.encodeRequest(1283, 1, listOf("param" to "/tmp/fuse_d/DCIM")),
        )
        assertEquals(
            """{"msg_id":2,"token":1,"type":"buzzer_volume","param":"low"}""",
            CameraMessage.encodeRequest(2, 1, listOf("type" to "buzzer_volume", "param" to "low")),
        )
    }

    @Test
    fun cmdTable_hasNoDuplicates_andKnowsTimeouts() {
        assertEquals(Cmd.entries.size, Cmd.entries.map { it.msgId }.toSet().size)
        assertEquals(Cmd.BATTERY, Cmd.byMsgId(13))
        assertNull(Cmd.byMsgId(99999))
        assertEquals(15_000L, Cmd.timeoutFor(1282))
        assertEquals(5_000L, Cmd.timeoutFor(99999))
        assertFalse(Cmd.entries.any { it.msgId == Cmd.EVENT_MSG_ID })
    }
}
