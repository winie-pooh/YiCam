package com.example.yicam

/**
 * Splits a continuous text stream into complete top-level JSON objects.
 * The camera sends objects back-to-back without delimiters, and one read()
 * may contain half an object or several objects. Braces inside "strings" are ignored.
 */
class JsonStreamParser {
    private val sb = StringBuilder()
    private var depth = 0
    private var inString = false
    private var escaped = false

    fun feed(chunk: CharArray, length: Int): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until length) {
            val ch = chunk[i]
            if (depth == 0 && ch != '{') continue // skip anything between objects
            sb.append(ch)
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        out.add(sb.toString())
                        sb.setLength(0)
                    }
                }
            }
        }
        return out
    }
}
