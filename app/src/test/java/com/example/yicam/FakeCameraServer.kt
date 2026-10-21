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
    @Volatile var tinySd = false
    @Volatile var appStatus = "idle"

    /** When true a new session does NOT leave the preview state (the automatic unlock then cannot work). */
    @Volatile var previewSurvivesNewSession = false
    @Volatile var previewRval = 0
    @Volatile var sawOverlap = false

    /** Settings changed by msg_id 2 (and by time-lapse stop); the next msg_id 3 reply shows them. */
    val settingValues = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Forces the rval of a command (msg_id to rval), for refusal tests. */
    val forcedRval = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    @Volatile var zoom = 0

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
                // The real camera leaves the preview state when a new session starts (phone logs 2026-10-19/20).
                if (appStatus == "vf" && !previewSurvivesNewSession) appStatus = "idle"
                validToken = nextToken.getAndIncrement()
                """{ "rval": 0, "msg_id": 257, "param": $validToken }"""
            }
            rejectEveryCommand || token != validToken -> """{"rval":-4,"msg_id":$msgId}"""
            msgId == 3 -> if (request["param"] is String) options(request["param"] as String) else settings()
            msgId == 5 -> sdReply(request["type"] == "total")
            msgId == 13 -> Fixtures.BATTERY_REPLY
            forcedRval[msgId] != null -> """{"rval":${forcedRval[msgId]},"msg_id":$msgId}"""
            msgId == 2 -> setSetting(request)
            msgId == 770 -> {
                settingValues["precise_cont_capturing"] = "off"
                """{"rval":0,"msg_id":770}"""
            }
            msgId == 513 -> {
                appStatus = "record"
                """{"rval":0,"msg_id":513}"""
            }
            msgId == 514 -> {
                appStatus = "idle"
                """{"rval":0,"msg_id":514}"""
            }
            msgId == 14 -> {
                zoom = (request["param"] as? String)?.toIntOrNull() ?: zoom
                """{"rval":0,"msg_id":14}"""
            }
            msgId == 15 -> """{ "rval": 0, "msg_id": 15, "param": "$zoom" }"""
            msgId == 1283 -> Fixtures.CHDIR_REPLY
            msgId == 1282 -> Fixtures.LISTING_REPLY
            msgId == 259 -> if (previewRval == 0) Fixtures.START_PREVIEW_REPLY else """{"rval":$previewRval,"msg_id":259}"""
            else -> """{"rval":0,"msg_id":$msgId}"""
        }
        val extra = if (msgId != 257) gluedEventAfterReply.orEmpty() else ""
        write(s, reply + extra)
        val accepted = token == validToken && forcedRval[msgId] == null
        if (msgId == 259 && token == validToken && previewRval == 0) {
            Thread.sleep(60)
            write(s, Fixtures.VF_START_EVENT)
        }
        if (msgId == 513 && accepted) {
            Thread.sleep(30)
            write(s, Fixtures.START_VIDEO_RECORD_EVENT)
            write(s, Fixtures.VF_STOP_EVENT)
        }
        if (msgId == 514 && accepted) {
            Thread.sleep(30)
            write(s, Fixtures.VIDEO_RECORD_COMPLETE_EVENT)
            write(s, Fixtures.VF_START_EVENT)
        }
    }

    /** msg_id 2: type/param. Remembers the value; the next msg_id 3 reply shows it. */
    private fun setSetting(request: Map<String, Any?>): String {
        val key = request["type"] as? String
        val value = request["param"] as? String
        if (key == null || value == null) return """{"rval":-1,"msg_id":2}"""
        settingValues[key] = value
        return """{"rval":0,"msg_id":2}"""
    }

    /** msg_id 3 with param=key: the allowed values; PAL changes the resolution lists; unknown keys are read only. */
    private fun options(key: String): String {
        val pal = settingValues["video_standard"] == "PAL"
        val text = when {
            pal && (key == "video_resolution" || key == "timelapse_video_resolution") -> Fixtures.PAL_RESOLUTIONS
            else -> Fixtures.OPTION_TEXTS[key] ?: "readonly:"
        }.let { text ->
            // While the preview is on the real camera lists these values but calls them "readonly" (phone log 2026-10-19).
            if (appStatus == "vf" && key in PREVIEW_LOCKED && text.startsWith("settable:")) "readonly:" + text.removePrefix("settable:") else text
        }
        return Fixtures.optionsReply(key, text)
    }

    private val PREVIEW_LOCKED = setOf("video_standard", "video_quality", "video_stamp", "timelapse_video")

    private fun sdReply(total: Boolean): String = when {
        tinySd && total -> Fixtures.SMALL_SD_TOTAL_REPLY
        tinySd -> Fixtures.SMALL_SD_FREE_REPLY
        total -> Fixtures.SD_TOTAL_REPLY
        else -> Fixtures.SD_FREE_REPLY
    }

    private fun settings(): String =
        if (leakSecrets) {
            withAppStatus(Fixtures.SETTINGS_REPLY)
                .replace("\"wifi_password\": \"*\"", "\"wifi_password\": \"hunter2\"")
                .replace("\"serial_number\": \"*\"", "\"serial_number\": \"ABC123456\"")
        } else {
            withAppStatus(Fixtures.SETTINGS_REPLY)
        }

    private fun withAppStatus(json: String): String {
        var result = json.replace(Regex("\"app_status\":\\s*\"idle\""), "\"app_status\": \"$appStatus\"")
        for ((key, value) in settingValues) {
            result = result.replace(Regex("(\"$key\":\\s*)\"[^\"]*\""), "$1\"$value\"")
        }
        return result
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
