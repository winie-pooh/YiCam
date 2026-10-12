/**
 * Responsible for: the in-memory ring buffer of raw traffic and events, with secrets masked, observable by the UI and exportable as text.
 * Not responsible for: sockets, parsing, or showing the log.
 */
package com.example.yicam

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TrafficLog(
    private val capacity: Int = 2000,
    private val now: () -> LocalTime = { LocalTime.now() },
) {
    private val lock = Any()
    private val buffer = ArrayDeque<String>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Snapshot of all lines, oldest first. A new list is published after every added line. */
    val lines: StateFlow<List<String>> get() = _lines

    /** Request sent to the camera. */
    fun tx(text: String) = add(">> ", text)

    /** Message received from the camera. */
    fun rx(text: String) = add("<< ", text)

    /** Application event, error or diagnostic note. */
    fun sys(text: String) = add("", text)

    fun asText(): String = _lines.value.joinToString("\n")

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }

    private fun add(prefix: String, text: String) {
        val line = "[${FORMAT.format(now())}] $prefix${LogMasker.mask(text)}"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > capacity) buffer.removeFirst()
            _lines.value = buffer.toList()
        }
    }

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
