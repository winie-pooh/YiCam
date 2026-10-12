package com.example.yicam

import android.net.Network
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * v0.0.1 probe connection: one TCP socket to the camera JSON port,
 * a reader thread that splits the stream into JSON objects, and a
 * single writer thread (the camera handles one command at a time).
 *
 * "%TOKEN%" in an outgoing command is replaced by the current session token.
 */
class CameraConnection(private val log: (String) -> Unit) {
    private var socket: Socket? = null
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile
    var token: Int = 0
        private set

    /** Blocking: call from a background thread. */
    fun connect(network: Network, host: String, port: Int) {
        close()
        val s = network.socketFactory.createSocket()
        s.connect(InetSocketAddress(host, port), 5000)
        s.tcpNoDelay = true
        socket = s
        thread(isDaemon = true, name = "cam-reader") { readLoop(s) }
    }

    fun send(template: String) {
        writer.execute {
            val s = socket
            if (s == null || s.isClosed) {
                log("!! not connected")
                return@execute
            }
            val raw = escapeSlashes(template.replace("%TOKEN%", token.toString()))
            try {
                val out = s.getOutputStream()
                out.write(raw.toByteArray(Charsets.UTF_8))
                out.flush()
                log(">> $raw")
            } catch (e: Exception) {
                log("!! send failed: $e")
            }
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
        socket = null
        token = 0
    }

    private fun readLoop(s: Socket) {
        val parser = JsonStreamParser()
        val reader = InputStreamReader(s.getInputStream(), Charsets.UTF_8)
        val buf = CharArray(8192)
        try {
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                for (obj in parser.feed(buf, n)) handle(obj)
            }
            log("-- connection closed by camera")
        } catch (e: Exception) {
            if (!s.isClosed) log("-- read error: $e")
        }
    }

    private fun handle(raw: String) {
        try {
            val json = JSONObject(raw)
            if (json.optInt("msg_id") == 257 && json.has("param")) {
                token = json.optInt("param")
            }
        } catch (e: Exception) {
            // not valid JSON: just log it
        }
        log("<< " + redact(raw))
    }

    companion object {
        /** The camera expects "/" as "\/" (the original PC client does this). */
        fun escapeSlashes(s: String): String =
            Regex("(?<!\\\\)/").replace(s, Regex.escapeReplacement("\\/"))

        /** Never put the Wi-Fi password or serial number into logs. */
        fun redact(s: String): String =
            Regex("(\"(?:wifi_password|serial_number)\"\\s*:\\s*\")[^\"]*(\")")
                .replace(s, "$1***$2")
    }
}
