/**
 * Responsible for: a scriptable fake camera (TCP server on localhost) for integration tests: session token, canned replies from real logs, and switches for faults.
 * Not responsible for: assertions (the tests do that) or real camera behaviour beyond what the logs showed.
 */
package com.example.yicam

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FakeCameraServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    /** Raw request texts, in arrival order, including the 257 handshakes. */
    val received = CopyOnWriteArrayList<String>()
    val acceptedConnections = AtomicInteger(0)
    val closedConnections = AtomicInteger(0)

    @Volatile var replyDelayMs = 0L
    @Volatile var fragmentReplies = false
    @Volatile var gluedEventAfterReply: String? = null
    @Volatile var neverAnswer: Set<Int> = emptySet()
    @Volatile var rejectEveryCommand = false
    @Volatile var leakSecrets = false
    @Volatile var sawOverlap = false

    private val nextToken = AtomicInteger(1)
    @Volatile private var validToken = -1
    @Volatile private var client: Socket? = null
    private val acceptor = Thread({ acceptLoop() }, "fake-camera-accept").apply { isDaemon = true }

    init {
        acceptor.start()
    }

    /** Makes the camera forget the current token: next command gets rval -4 until a new 257. */
    fun expireToken() {
        validToken = -1
    }

    /** Sends [json] to the connected client now, as the camera does with events. */
    fun pushEvent(json: String) {
        val s = client ?: error("no client connected")
        synchronized(s) {
            s.getOutputStream().write(json.toByteArray())
            s.getOutputStream().flush()
        }
    }

    /** Closes the current client connection from the camera side. */
    fun dropClient() {
        try {
            client?.close()
        } catch (_: IOException) {
        }
    }

    override fun close() {
        dropClient()
        try {
            server.close()
        } catch (_: IOException) {
        }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val s = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            acceptedConnections.incrementAndGet()
            client = s
            Thread({ serve(s) }, "fake-camera-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(s: Socket) {
        val parser = JsonStreamParser()
        val buf = ByteArray(4096)
        try {
            val input = s.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (text in parser.feed(buf, 0, n)) {
                    handle(s, text)
                    if (input.available() > 0) sawOverlap = true
                }
            }
        } catch (_: IOException) {
        } finally {
            closedConnections.incrementAndGet()
            try {
                s.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun handle(s: Socket, text: String) {
        received.add(text)
        @Suppress("UNCHECKED_CAST")
        val request = MiniJson.parse(text) as Map<String, Any?>
        val msgId = (request["msg_id"] as Number).toInt()
        val token = (request["token"] as? Number)?.toInt()
        if (msgId in neverAnswer) return
        if (replyDelayMs > 0) Thread.sleep(replyDelayMs)

        val reply = when {
            msgId == 257 -> {
                validToken = nextToken.getAndIncrement()
                """{ "rval": 0, "msg_id": 257, "param": $validToken }"""
            }
            rejectEveryCommand || token != validToken -> """{"rval":-4,"msg_id":$msgId}"""
            msgId == 3 -> settings()
            msgId == 13 -> Fixtures.BATTERY_REPLY
            msgId == 1283 -> Fixtures.CHDIR_REPLY
            msgId == 1282 -> Fixtures.LISTING_REPLY
            msgId == 259 -> Fixtures.START_PREVIEW_REPLY
            else -> """{"rval":0,"msg_id":$msgId}"""
        }
        val extra = if (msgId != 257) gluedEventAfterReply.orEmpty() else ""
        write(s, reply + extra)
        if (msgId == 259 && token == validToken) {
            Thread.sleep(60)
            write(s, Fixtures.VF_START_EVENT)
        }
    }

    private fun settings(): String =
        if (leakSecrets) {
            Fixtures.SETTINGS_REPLY
                .replace("\"wifi_password\": \"*\"", "\"wifi_password\": \"hunter2\"")
                .replace("\"serial_number\": \"*\"", "\"serial_number\": \"ABC123456\"")
        } else {
            Fixtures.SETTINGS_REPLY
        }

    private fun write(s: Socket, text: String) {
        val bytes = text.toByteArray()
        synchronized(s) {
            val out = s.getOutputStream()
            if (fragmentReplies) {
                var pos = 0
                while (pos < bytes.size) {
                    val len = minOf(7, bytes.size - pos)
                    out.write(bytes, pos, len)
                    out.flush()
                    pos += len
                    Thread.sleep(1)
                }
            } else {
                out.write(bytes)
                out.flush()
            }
        }
    }
}
