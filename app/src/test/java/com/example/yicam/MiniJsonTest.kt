/**
 * Responsible for: unit tests of MiniJson (parsing, writing, escaping, errors).
 * Not responsible for: stream splitting or camera message meaning.
 */
package com.example.yicam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MiniJsonTest {
    @Test
    fun parsesRealSettingsReply() {
        @Suppress("UNCHECKED_CAST")
        val map = MiniJson.parse(Fixtures.SETTINGS_REPLY) as Map<String, Any?>
        assertEquals(0L, map["rval"])
        assertEquals(3L, map["msg_id"])
        val params = map["param"] as List<*>
        assertEquals(Fixtures.SETTINGS_PARAM_COUNT, params.size)
        val burst = params.map { it as Map<*, *> }.first { it.containsKey("burst_capture_number") }
        assertEquals("7 p / s", burst["burst_capture_number"])
    }

    @Test
    fun numbersAreLongOrDouble() {
        assertEquals(1L, MiniJson.parse("1"))
        assertEquals(-42L, MiniJson.parse("-42"))
        assertEquals(1.5, MiniJson.parse("1.5"))
        assertEquals(1000.0, MiniJson.parse("1e3"))
    }

    @Test
    fun literals() {
        assertEquals(true, MiniJson.parse("true"))
        assertEquals(false, MiniJson.parse("false"))
        assertNull(MiniJson.parse("null"))
    }

    @Test
    fun stringEscapes() {
        assertEquals("a\"b\\c/d\ne\tf", MiniJson.parse("\"a\\\"b\\\\c\\/d\\ne\\tf\""))
        assertEquals("Aé", MiniJson.parse("\"\\u0041\\u00e9\""))
    }

    @Test
    fun emptyContainers() {
        assertEquals(emptyMap<String, Any?>(), MiniJson.parse("{}"))
        assertEquals(emptyList<Any?>(), MiniJson.parse(" [ ] "))
    }

    @Test
    fun objectKeepsKeyOrder() {
        @Suppress("UNCHECKED_CAST")
        val map = MiniJson.parse("""{"z":1,"a":2,"m":3}""") as Map<String, Any?>
        assertEquals(listOf("z", "a", "m"), map.keys.toList())
    }

    @Test
    fun toJson_escapesSlashByDefault_likeTheCamera() {
        assertEquals("""{"param":"\/tmp\/fuse_d\/DCIM"}""", MiniJson.toJson(mapOf("param" to "/tmp/fuse_d/DCIM")))
        assertEquals("""{"param":"/tmp"}""", MiniJson.toJson(mapOf("param" to "/tmp"), escapeSlash = false))
    }

    @Test
    fun toJson_escapesQuotesBackslashAndControlCharacters() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", MiniJson.toJson("a\"b\\c\n\u0001"))
    }

    @Test
    fun toJson_writesNumbersBooleansNullAndLists() {
        assertEquals("""[1,2.5,true,null,"x"]""", MiniJson.toJson(listOf(1, 2.5, true, null, "x")))
    }

    @Test
    fun roundTrip_ofRealReplies() {
        for (text in listOf(Fixtures.SETTINGS_REPLY, Fixtures.LISTING_REPLY, Fixtures.CHDIR_REPLY)) {
            val first = MiniJson.parse(text)
            assertEquals(first, MiniJson.parse(MiniJson.toJson(first)))
        }
    }

    @Test
    fun badInput_throwsJsonException() {
        val bad = listOf("", "{", "{\"a\"}", "{\"a\":}", "[1,]", "{\"a\":1,}", "\"abc", "tru", "{\"a\":1} x", "{'a':1}", "\"\\q\"", "-")
        for (text in bad) {
            try {
                MiniJson.parse(text)
                fail("should fail: $text")
            } catch (_: JsonException) {
            }
        }
    }

    @Test
    fun veryDeepNesting_isRejectedNotStackOverflow() {
        val text = "[".repeat(10_000) + "]".repeat(10_000)
        try {
            MiniJson.parse(text)
            fail("should fail")
        } catch (e: JsonException) {
            assertTrue(e.message!!.contains("deep"))
        }
    }
}
