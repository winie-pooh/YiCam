/**
 * Responsible for: one TCP connection to the camera port: open/close, a reader that turns bytes into JSON objects, serialized writes, connection diagnostics in the log.
 * Not responsible for: tokens, matching replies to requests, timeouts of commands, reconnect decisions (CameraApi.kt, CameraSession.kt), or finding the Wi-Fi (WifiBinder.kt).
 */
package com.example.yicam

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraConnection(
    private val host: String,
    private val port: Int,
    private val socketFactory: SocketFactory,
    private val scope: CoroutineScope,
    private val log: TrafficLog,
    private val connectTimeoutMs: Int = 5_000,
) {
    /** Called from the reader thread. Implementations must not block. */
    interface Listener {
        /** One complete JSON object received from the camera. */
        fun onMessage(json: String)

        /** The connection ended without a call to [close] (camera closed it, or a read error). */
        fun onClosed(cause: Throwable?)
    }

    @Volatile
    var listener: Listener? = null

    private val writeLock = Any()

    @Volatile
    private var socket: Socket? = null
    private var readerJob: Job? = null

    /** Opens a new connection. Any previous socket is closed first. Throws IOException on failure. */
    suspend fun open() = withContext(Dispatchers.IO) {
        close()
        val s = socketFactory.createSocket()
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: Exception) {
            closeQuietly(s)
            log.sys("[CONN] connect to $host:$port failed: ${e.message ?: e.javaClass.simpleName}")
            throw if (e is IOException) e else IOException(e.message, e)
        }
        socket = s
        log.sys("TCP connected to $host:$port")
        readerJob = scope.launch(Dispatchers.IO) { readLoop(s) }
    }

    /** Sends [text] as is. Throws IOException if the connection is not open or the write fails. */
    suspend fun write(text: String) = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("connection is not open")
        val bytes = text.toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            val out = s.getOutputStream()
            out.write(bytes)
            out.flush()
        }
    }

    /** Closes the socket on purpose. Does not call [Listener.onClosed]. */
    fun close() {
        val s = socket
        socket = null
        readerJob?.cancel()
        readerJob = null
        if (s != null) {
            closeQuietly(s)
            log.sys("[CONN] closed by the app")
        }
    }

    private fun readLoop(s: Socket) {
        val parser = JsonStreamParser()
        val buf = ByteArray(8192)
        var cause: Throwable? = null
        try {
            val input = s.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val dropped = parser.droppedObjects
                for (json in parser.feed(buf, 0, n)) deliver(json)
                if (parser.droppedObjects != dropped) log.sys("[WARN] oversized message from the camera was dropped")
            }
        } catch (e: Exception) {
            cause = e
        }
        if (socket !== s) return // closed on purpose or replaced by a newer connection: stay silent
        socket = null
        closeQuietly(s)
        if (cause == null) {
            log.sys("[CONN] closed by the camera (end of stream)")
        } else {
            log.sys("[CONN] read error: ${cause.message ?: cause.javaClass.simpleName}")
        }
        try {
            listener?.onClosed(cause)
        } catch (e: Exception) {
            log.sys("[ERR] listener failed on close: ${e.message}")
        }
    }

    private fun deliver(json: String) {
        try {
            listener?.onMessage(json)
        } catch (e: Exception) {
            log.sys("[ERR] failed to handle a message: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /** Camera address and JSON port in the camera's own Wi-Fi. */
        const val DEFAULT_HOST = "192.168.42.1"
        const val DEFAULT_PORT = 7878
    }
}
