/**
 * Responsible for: a small JSON reader and writer (objects, arrays, strings, numbers, booleans, null) with no Android dependency.
 * Not responsible for: camera message meaning (CameraMessage.kt) or cutting a stream into objects (JsonStreamParser.kt).
 */
package com.example.yicam

class JsonException(message: String) : Exception(message)

/**
 * Values: object -> Map<String, Any?> (keeps order), array -> List<Any?>, string -> String,
 * integer -> Long, other number -> Double, true/false -> Boolean, null -> null.
 */
object MiniJson {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipSpaces()
        val value = reader.readValue(0)
        reader.skipSpaces()
        if (!reader.atEnd()) throw JsonException("Unexpected data after the value at ${reader.pos}")
        return value
    }

    /**
     * Compact JSON. The camera expects '/' written as "\/" inside strings, so that is the default.
     */
    fun toJson(value: Any?, escapeSlash: Boolean = true): String {
        val sb = StringBuilder()
        write(sb, value, escapeSlash)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: Any?, escapeSlash: Boolean) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value, escapeSlash)
            is Boolean -> sb.append(value)
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString(), escapeSlash)
                    sb.append(':')
                    write(sb, v, escapeSlash)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, v, escapeSlash)
                }
                sb.append(']')
            }
            else -> writeString(sb, value.toString(), escapeSlash)
        }
    }

    private fun writeString(sb: StringBuilder, s: String, escapeSlash: Boolean) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '/' && escapeSlash -> sb.append("\\/")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Reader(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipSpaces() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("Nesting is too deep")
            if (atEnd()) throw JsonException("Unexpected end of text")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else throw JsonException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("Unexpected data at $pos")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipSpaces()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipSpaces()
                if (peek() != '"') throw JsonException("Expected a key at $pos")
                val key = readString()
                skipSpaces()
                if (peek() != ':') throw JsonException("Expected ':' at $pos")
                pos++
                skipSpaces()
                map[key] = readValue(depth + 1)
                skipSpaces()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw JsonException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipSpaces()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                skipSpaces()
                list.add(readValue(depth + 1))
                skipSpaces()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw JsonException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw JsonException("Unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad \\u escape at $pos")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw JsonException("Bad \\u escape at $pos")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Bad escape '\\$e' at ${pos - 1}")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isInteger = true
            if (peek() == '.') {
                isInteger = false
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                isInteger = false
                pos++
                if (peek() == '+' || peek() == '-') pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            if (isInteger) return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw JsonException("Bad number '$text'")
            return text.toDoubleOrNull() ?: throw JsonException("Bad number '$text'")
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
    }
}
