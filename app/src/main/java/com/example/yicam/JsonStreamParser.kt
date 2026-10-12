/**
 * Responsible for: cutting a continuous byte stream into complete top-level JSON objects (text), however the bytes are chunked.
 * Not responsible for: understanding the objects (MiniJson.kt, CameraMessage.kt) or any camera commands.
 */
package com.example.yicam

import java.io.ByteArrayOutputStream

/**
 * The camera sends JSON objects with no delimiters. This parser counts braces, ignoring braces inside
 * quoted strings and honouring backslash escapes. Bytes outside an object (spaces, garbage) are skipped.
 * Works on bytes: '{', '}', '"' and '\' never occur inside UTF-8 multi-byte characters.
 * Not thread-safe: use from one reader only.
 */
class JsonStreamParser(private val maxObjectBytes: Int = 1 shl 20) {
    private val buffer = ByteArrayOutputStream()
    private var depth = 0
    private var inString = false
    private var escaped = false

    /** How many oversized objects were dropped so far. */
    var droppedObjects: Int = 0
        private set

    /** Adds [length] bytes from [bytes] and returns every object completed by them, in order. */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<String> {
        var result: MutableList<String>? = null
        for (i in offset until offset + length) {
            val b = bytes[i].toInt()
            if (depth == 0) {
                if (b == OPEN) {
                    depth = 1
                    buffer.write(b)
                }
                continue
            }
            buffer.write(b)
            if (inString) {
                when {
                    escaped -> escaped = false
                    b == BACKSLASH -> escaped = true
                    b == QUOTE -> inString = false
                }
            } else {
                when (b) {
                    QUOTE -> inString = true
                    OPEN -> depth++
                    CLOSE -> {
                        depth--
                        if (depth == 0) {
                            val list = result ?: mutableListOf<String>().also { result = it }
                            list.add(buffer.toString(Charsets.UTF_8))
                            buffer.reset()
                        }
                    }
                }
            }
            if (depth > 0 && buffer.size() > maxObjectBytes) {
                droppedObjects++
                reset()
            }
        }
        return result ?: emptyList()
    }

    fun feed(text: String): List<String> = feed(text.toByteArray(Charsets.UTF_8))

    /** Forget any half-received object. */
    fun reset() {
        buffer.reset()
        depth = 0
        inString = false
        escaped = false
    }

    private companion object {
        const val OPEN = '{'.code
        const val CLOSE = '}'.code
        const val QUOTE = '"'.code
        const val BACKSLASH = '\\'.code
    }
}
