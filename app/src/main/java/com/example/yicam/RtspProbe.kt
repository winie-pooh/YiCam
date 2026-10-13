/**
 * Responsible for: a one-off RTSP trace for diagnosis: OPTIONS, DESCRIBE, SETUP (RTP over TCP, several URL guesses), PLAY and a 3-second read of the interleaved data, every step written to the log.
 * Not responsible for: playing video (ExoRtspPlayer.kt), starting the preview on the camera (ProbeController.kt) or any decision (LiveViewController.kt).
 */
package com.example.yicam

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

object RtspProbe {
    private class Reply(val head: String, val body: String) {
        val status: Int
            get() = Regex("^RTSP/1\\.0\\s+(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: -1

        fun header(name: String): String? =
            Regex("(?im)^" + Regex.escape(name) + ":\\s*(.*)$").find(head)?.groupValues?.get(1)?.trim()
    }

    /** Blocking: run it on a background thread. The app process is bound to the camera Wi-Fi, so a plain socket reaches the camera. */
    fun run(host: String, port: Int, path: String, log: TrafficLog) {
        val base = "rtsp://$host:$port$path"
        log.sys("[RTSP] trace start: $base")
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                var cseq = 0

                fun call(method: String, url: String, headers: List<String> = emptyList()): Reply {
                    cseq++
                    val request = buildString {
                        append("$method $url RTSP/1.0\r\nCSeq: $cseq\r\nUser-Agent: YiCam-probe\r\n")
                        for (h in headers) append(h).append("\r\n")
                        append("\r\n")
                    }
                    log.sys("[RTSP] >> " + request.trim().replace("\r\n", " | "))
                    output.write(request.toByteArray(Charsets.UTF_8))
                    output.flush()
                    val reply = readReply(input)
                    log.sys("[RTSP] << " + reply.head.trim().replace("\r\n", " | "))
                    if (reply.body.isNotBlank()) {
                        log.sys("[RTSP] body: " + reply.body.trim().replace("\r\n", " | ").replace("\n", " | "))
                    }
                    return reply
                }

                call("OPTIONS", base)
                val describe = call("DESCRIBE", base, listOf("Accept: application/sdp"))
                if (describe.status != 200) {
                    log.sys("[RTSP] DESCRIBE did not answer 200, stopping (is the preview running?)")
                    return@use
                }
                val controls = Regex("(?m)^a=control:(\\S+)").findAll(describe.body).map { it.groupValues[1] }.toList()
                log.sys("[RTSP] control attributes in the SDP: $controls")

                val trackControl = controls.lastOrNull { it != "*" }
                val candidates = ArrayList<String>()
                if (trackControl != null) {
                    if (trackControl.startsWith("rtsp://")) {
                        candidates += trackControl
                    } else {
                        candidates += base.trimEnd('/') + "/" + trackControl
                        candidates += "rtsp://$host:$port/$trackControl"
                    }
                }
                candidates += base

                var session: String? = null
                for (url in candidates) {
                    val setup = call("SETUP", url, listOf("Transport: RTP/AVP/TCP;unicast;interleaved=0-1"))
                    if (setup.status == 200) {
                        session = setup.header("Session")?.substringBefore(';')?.trim()
                        log.sys("[RTSP] SETUP worked with $url (session $session)")
                        break
                    }
                }
                if (session == null) {
                    log.sys("[RTSP] no SETUP address worked, stopping")
                    return@use
                }

                call("PLAY", base, listOf("Session: $session", "Range: npt=0.000-"))
                readInterleaved(input, log, 3_000L)
                try {
                    call("TEARDOWN", base, listOf("Session: $session"))
                } catch (e: Exception) {
                    // the camera may close the connection at once; nothing to report
                }
            }
        } catch (e: Exception) {
            log.sys("[RTSP] failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        log.sys("[RTSP] trace end")
    }

    /** Reads one RTSP reply; interleaved media frames ('$' + channel + length) that arrive before it are skipped. */
    private fun readReply(input: InputStream): Reply {
        while (true) {
            val first = input.read()
            if (first < 0) throw IOException("the camera closed the connection")
            if (first == '$'.code) {
                skipFrame(input)
                continue
            }
            val head = ByteArrayOutputStream()
            head.write(first)
            while (!endsWithBlankLine(head)) {
                val b = input.read()
                if (b < 0) throw IOException("the camera closed the connection inside a reply")
                head.write(b)
            }
            val text = head.toString("UTF-8")
            val length = Regex("(?i)content-length:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            readFully(input, body)
            return Reply(text, String(body, Charsets.UTF_8))
        }
    }

    private fun readInterleaved(input: InputStream, log: TrafficLog, durationMs: Long) {
        val end = System.nanoTime() + durationMs * 1_000_000L
        var frames = 0
        var bytes = 0L
        try {
            while (System.nanoTime() < end) {
                val b = input.read()
                if (b < 0) {
                    log.sys("[RTSP] the camera closed the stream")
                    break
                }
                if (b != '$'.code) continue
                val channel = input.read()
                val high = input.read()
                val low = input.read()
                if (channel < 0 || low < 0) break
                val length = (high shl 8) or low
                val payload = ByteArray(length)
                readFully(input, payload)
                frames++
                bytes += length
                if (frames <= 3) {
                    val type = if (length > 1) payload[1].toInt() and 0x7F else -1
                    log.sys("[RTSP] frame $frames: channel $channel, $length bytes, RTP payload type $type")
                }
            }
        } catch (e: SocketTimeoutException) {
            log.sys("[RTSP] no data for 5 s")
        }
        log.sys("[RTSP] in $durationMs ms: $frames interleaved frames, $bytes bytes")
    }

    private fun skipFrame(input: InputStream) {
        input.read() // channel
        val high = input.read()
        val low = input.read()
        if (low < 0) throw IOException("the camera closed the connection inside a frame")
        readFully(input, ByteArray((high shl 8) or low))
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw IOException("the camera closed the connection")
            offset += n
        }
    }

    private fun endsWithBlankLine(out: ByteArrayOutputStream): Boolean {
        val a = out.toByteArray()
        val n = a.size
        return n >= 4 && a[n - 4] == 13.toByte() && a[n - 3] == 10.toByte() && a[n - 2] == 13.toByte() && a[n - 1] == 10.toByte()
    }
}
