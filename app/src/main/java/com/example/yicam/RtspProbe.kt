/**
 * Responsible for: a one-off RTSP trace for diagnosis: OPTIONS, DESCRIBE, SETUP (RTP over UDP, several URL guesses), PLAY and a 3-second count of the UDP packets that arrive, every step written to the log.
 * Not responsible for: playing video (ExoRtspPlayer.kt), starting the preview on the camera (ProbeController.kt) or any decision (LiveViewController.kt).
 */
package com.example.yicam

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
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

                // The camera refuses RTP over TCP (461), so the trace uses UDP, like the player.
                val udp = openUdpPair()
                if (udp == null) {
                    log.sys("[RTSP] no free UDP port pair, stopping")
                    return@use
                }
                try {
                    var session: String? = null
                    for (url in candidates) {
                        val setup = call(
                            "SETUP",
                            url,
                            listOf("Transport: RTP/AVP;unicast;client_port=${udp.port}-${udp.port + 1}"),
                        )
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
                    readUdp(udp.rtp, log, 3_000L)
                    try {
                        call("TEARDOWN", base, listOf("Session: $session"))
                    } catch (e: Exception) {
                        // the camera may close the connection at once; nothing to report
                    }
                } finally {
                    udp.close()
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

    private class UdpPair(val rtp: DatagramSocket, val rtcp: DatagramSocket, val port: Int) {
        fun close() {
            rtp.close()
            rtcp.close()
        }
    }

    private fun openUdpPair(): UdpPair? {
        var port = 40_000
        while (port < 40_200) {
            try {
                val rtp = DatagramSocket(port)
                try {
                    return UdpPair(rtp, DatagramSocket(port + 1), port)
                } catch (e: SocketException) {
                    rtp.close()
                }
            } catch (e: SocketException) {
                // port in use, try the next pair
            }
            port += 2
        }
        return null
    }

    /** Counts the RTP packets that arrive on [socket] for [durationMs]. */
    private fun readUdp(socket: DatagramSocket, log: TrafficLog, durationMs: Long) {
        socket.soTimeout = 1_000
        val buffer = ByteArray(2_048)
        val end = System.nanoTime() + durationMs * 1_000_000L
        var packets = 0
        var bytes = 0L
        while (System.nanoTime() < end) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            }
            packets++
            bytes += packet.length
            if (packets <= 3 && packet.length >= 12) {
                val type = buffer[1].toInt() and 0x7F
                val sequence = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
                log.sys("[RTSP] UDP packet $packets: ${packet.length} bytes, RTP payload type $type, sequence $sequence")
            }
        }
        log.sys("[RTSP] in $durationMs ms: $packets UDP packets, $bytes bytes")
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
