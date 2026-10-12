/**
 * Responsible for: unit tests of JsonStreamParser (chunking, glued objects, braces and escapes inside strings, UTF-8, garbage, size limit, fuzz).
 * Not responsible for: tests of JSON meaning or the camera session.
 */
package com.example.yicam

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonStreamParserTest {
    private fun parseAll(chunks: List<ByteArray>): List<String> {
        val p = JsonStreamParser()
        return chunks.flatMap { p.feed(it) }
    }

    @Test
    fun oneObject_inOneChunk() {
        assertEquals(listOf(Fixtures.BATTERY_REPLY), JsonStreamParser().feed(Fixtures.BATTERY_REPLY))
    }

    @Test
    fun oneObject_splitInTwo_isReturnedOnlyWhenComplete() {
        val p = JsonStreamParser()
        val text = Fixtures.BATTERY_REPLY
        assertTrue(p.feed(text.substring(0, 20)).isEmpty())
        assertEquals(listOf(text), p.feed(text.substring(20)))
    }

    @Test
    fun realSettingsReply_fedByteByByte() {
        val p = JsonStreamParser()
        val out = mutableListOf<String>()
        for (b in Fixtures.SETTINGS_REPLY.toByteArray()) out += p.feed(byteArrayOf(b))
        assertEquals(listOf(Fixtures.SETTINGS_REPLY), out)
    }

    @Test
    fun twoObjectsGluedTogether_areSplit() {
        val glued = Fixtures.BATTERY_REPLY + Fixtures.BATTERY_EVENT
        assertEquals(listOf(Fixtures.BATTERY_REPLY, Fixtures.BATTERY_EVENT), JsonStreamParser().feed(glued))
    }

    @Test
    fun threeObjects_withSpacesAndNewlinesBetween() {
        val text = Fixtures.SESSION_REPLY_1 + "\r\n  " + Fixtures.VF_START_EVENT + "\n" + Fixtures.START_PREVIEW_REPLY
        assertEquals(
            listOf(Fixtures.SESSION_REPLY_1, Fixtures.VF_START_EVENT, Fixtures.START_PREVIEW_REPLY),
            JsonStreamParser().feed(text),
        )
    }

    @Test
    fun nestedObjectsAndArrays_areOneObject() {
        val text = """{"a":{"b":{"c":[{"d":1},{"e":{}}]}}}"""
        assertEquals(listOf(text), JsonStreamParser().feed(text))
    }

    @Test
    fun bracesInsideStrings_areIgnored() {
        val text = """{"msg_id":1,"param":"} { }}} {{{"}"""
        assertEquals(listOf(text), JsonStreamParser().feed(text))
    }

    @Test
    fun escapedQuoteInsideString_doesNotEndTheString() {
        val text = """{"param":"say \"}\" now"}"""
        assertEquals(listOf(text), JsonStreamParser().feed(text))
    }

    @Test
    fun escapedBackslashBeforeQuote_endsTheString() {
        // the string is: back\ (an escaped backslash), then the quote closes it, so the final } is real
        val text = """{"param":"back\\"}"""
        val other = """{"x":1}"""
        assertEquals(listOf(text, other), JsonStreamParser().feed(text + other))
    }

    @Test
    fun escapedSlash_isKept() {
        val text = """{"param":"\/tmp\/fuse_d"}"""
        assertEquals(listOf(text), JsonStreamParser().feed(text))
    }

    @Test
    fun garbageOutsideObjects_isSkipped() {
        val text = "xx}}]] " + Fixtures.BATTERY_REPLY + " ,, junk " + Fixtures.VF_START_EVENT
        assertEquals(listOf(Fixtures.BATTERY_REPLY, Fixtures.VF_START_EVENT), JsonStreamParser().feed(text))
    }

    @Test
    fun utf8MultiByteCharacter_splitAcrossChunks_isDecodedCorrectly() {
        val text = """{"name":"Привіт, світе ✓"}"""
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (cut in 1 until bytes.size) {
            val p = JsonStreamParser()
            val out = p.feed(bytes.copyOfRange(0, cut)) + p.feed(bytes.copyOfRange(cut, bytes.size))
            assertEquals("cut at $cut", listOf(text), out)
        }
    }

    @Test
    fun offsetAndLength_areRespected() {
        val bytes = ("ignored" + Fixtures.BATTERY_REPLY + "tail").toByteArray()
        val out = JsonStreamParser().feed(bytes, 7, Fixtures.BATTERY_REPLY.length)
        assertEquals(listOf(Fixtures.BATTERY_REPLY), out)
    }

    @Test
    fun oversizedObject_isDropped_andParsingContinues() {
        val p = JsonStreamParser(maxObjectBytes = 50)
        val huge = """{"param":"${"x".repeat(200)}"}"""
        val out = p.feed(huge + Fixtures.START_PREVIEW_REPLY)
        assertEquals(1, p.droppedObjects)
        assertEquals(listOf(Fixtures.START_PREVIEW_REPLY), out)
    }

    @Test
    fun reset_forgetsHalfReceivedObject() {
        val p = JsonStreamParser()
        p.feed("""{"half":"obj""")
        p.reset()
        assertEquals(listOf(Fixtures.START_PREVIEW_REPLY), p.feed(Fixtures.START_PREVIEW_REPLY))
    }

    @Test
    fun fuzz_randomObjectsCutAtRandomPlaces_comeOutIdentical() {
        val rnd = Random(20261012)
        val pieces = listOf(
            Fixtures.SETTINGS_REPLY, Fixtures.BATTERY_REPLY, Fixtures.BATTERY_EVENT, Fixtures.LISTING_REPLY,
            Fixtures.CHDIR_REPLY, Fixtures.VF_START_EVENT, """{"s":"} \" \\ {"}""", """{"u":"Привіт"}""",
        )
        repeat(300) {
            val expected = List(rnd.nextInt(1, 8)) { pieces[rnd.nextInt(pieces.size)] }
            val bytes = expected.joinToString(if (rnd.nextBoolean()) "" else " ").toByteArray(Charsets.UTF_8)
            val chunks = mutableListOf<ByteArray>()
            var pos = 0
            while (pos < bytes.size) {
                val len = minOf(rnd.nextInt(1, 40), bytes.size - pos)
                chunks += bytes.copyOfRange(pos, pos + len)
                pos += len
            }
            assertEquals(expected, parseAll(chunks))
        }
    }
}
