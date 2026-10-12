/**
 * Responsible for: the camera session: connect/reconnect, the session token, one command in flight at a time, reply matching by msg_id, timeouts, one automatic token refresh on rval -4, and the stream of camera events.
 * Not responsible for: sockets and framing (CameraConnection.kt), UI, Wi-Fi binding, background reconnecting and polling (CameraSession.kt), or the meaning of individual commands' replies (StatusParser.kt).
 */
package com.example.yicam

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * @param autoReconnect if a command is sent while the connection is lost (and not closed by [disconnect]),
 * reconnect once and send it. Background reconnect with growing delays lives in CameraSession, which creates this class with autoReconnect = false.
 */
class CameraApi(
    private val connection: CameraConnection,
    private val log: TrafficLog,
    private val autoReconnect: Boolean = true,
) : CameraConnection.Listener {

    private class Pending(val msgId: Int, val reply: CompletableDeferred<CameraMessage.Response>)

    private val mutex = Mutex()
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _events = MutableSharedFlow<CameraMessage.Event>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var token = 0

    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var closedByUser = false

    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Messages the camera sends on its own (battery, vf_start, ...). Unknown types are delivered too. */
    val events: SharedFlow<CameraMessage.Event> = _events.asSharedFlow()

    val currentToken: Int get() = token

    init {
        connection.listener = this
    }

    /** Opens the connection and the session. Safe to call again: the old socket is closed and a new token is taken. */
    suspend fun connect() {
        mutex.withLock {
            closedByUser = false
            connectLocked()
        }
    }

    /** Closes the connection on purpose; no automatic reconnect afterwards until [connect]. */
    fun disconnect() {
        closedByUser = true
        connection.close()
        failPending(CameraException.ConnectionLost("Disconnected by the app"))
        _state.value = ConnectionState.Disconnected
    }

    /**
     * Sends one command and waits for its reply. Commands are queued: only one is in flight.
     * Returns the reply even when rval is not 0 (check [CameraMessage.Response.isOk]).
     * rval -4 (bad token) triggers one token refresh and one retry.
     *
     * @param tokenOverride send exactly this token and do not refresh on -4 (for tests of bad tokens).
     * @throws CameraException.Timeout, CameraException.ConnectionLost, CameraException.NotConnected, CameraException.ConnectFailed
     */
    suspend fun send(
        msgId: Int,
        fields: List<Pair<String, Any?>> = emptyList(),
        timeoutMs: Long = Cmd.timeoutFor(msgId),
        tokenOverride: Int? = null,
    ): CameraMessage.Response = mutex.withLock {
        ensureConnectedLocked()
        if (tokenOverride != null) {
            return@withLock exchange(msgId, tokenOverride, fields, timeoutMs)
        }
        var reply = exchange(msgId, token, fields, timeoutMs)
        if (reply.rval == Cmd.RVAL_INVALID_TOKEN && msgId != Cmd.START_SESSION.msgId) {
            log.sys("[SESSION] token $token was rejected (rval -4), taking a new one")
            refreshTokenLocked()
            reply = exchange(msgId, token, fields, timeoutMs)
        } else if (msgId == Cmd.START_SESSION.msgId && reply.isOk) {
            reply.paramLong()?.let { adoptToken(it.toInt()) }
        }
        reply
    }

    suspend fun send(
        cmd: Cmd,
        fields: List<Pair<String, Any?>> = emptyList(),
        timeoutMs: Long = cmd.timeoutMs,
    ): CameraMessage.Response = send(cmd.msgId, fields, timeoutMs)

    /**
     * Sends a command typed as JSON, e.g. {"msg_id":1283,"param":"/tmp/fuse_d/DCIM"}. "/" may be typed as is.
     * "%TOKEN%" is replaced by the current token. If the text has a numeric "token", that exact token is used.
     */
    suspend fun sendRaw(json: String): CameraMessage.Response {
        val value = try {
            MiniJson.parse(json.replace("%TOKEN%", token.toString()))
        } catch (e: JsonException) {
            throw CameraException.BadRequest("Not valid JSON: ${e.message}")
        }
        @Suppress("UNCHECKED_CAST")
        val map = value as? Map<String, Any?> ?: throw CameraException.BadRequest("Expected a JSON object")
        val msgId = (map["msg_id"] as? Number)?.toInt() ?: throw CameraException.BadRequest("msg_id is missing")
        val explicitToken = (map["token"] as? Number)?.toInt()
        val fields = map.entries.filter { it.key != "msg_id" && it.key != "token" }.map { it.key to it.value }
        return send(msgId, fields, Cmd.timeoutFor(msgId), explicitToken)
    }

    // ---- connection and session -------------------------------------------------------------

    private suspend fun ensureConnectedLocked() {
        if (_state.value is ConnectionState.Connected) return
        if (autoReconnect && !closedByUser) {
            log.sys("[SESSION] not connected, reconnecting before sending")
            connectLocked()
        } else {
            throw CameraException.NotConnected()
        }
    }

    private suspend fun connectLocked() {
        _state.value = ConnectionState.Connecting
        token = 0
        try {
            connection.open()
        } catch (e: CancellationException) {
            _state.value = ConnectionState.Disconnected
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            _state.value = ConnectionState.Failed("Cannot connect: $reason")
            throw CameraException.ConnectFailed("Cannot connect to the camera: $reason", e)
        }
        try {
            takeTokenLocked()
        } catch (e: CancellationException) {
            connection.close()
            _state.value = ConnectionState.Disconnected
            throw e
        } catch (e: CameraException) {
            connection.close()
            _state.value = ConnectionState.Failed(e.message ?: "Session failed")
            throw e
        }
    }

    /** Asks for a new token on the open connection and publishes the Connected state. */
    private suspend fun takeTokenLocked() {
        val reply = exchange(Cmd.START_SESSION.msgId, 0, emptyList(), Cmd.START_SESSION.timeoutMs)
        val newToken = reply.paramLong()
        if (!reply.isOk || newToken == null) {
            throw CameraException.Rejected(
                Cmd.START_SESSION.msgId,
                reply.rval,
                "The camera refused the session (rval ${reply.rval}, param ${reply.paramString()})",
            )
        }
        adoptToken(newToken.toInt())
    }

    private suspend fun refreshTokenLocked() {
        try {
            takeTokenLocked()
        } catch (e: CameraException) {
            if (e is CameraException.ConnectionLost) _state.value = ConnectionState.Failed(e.message ?: "Connection lost")
            throw e
        }
    }

    private fun adoptToken(newToken: Int) {
        token = newToken
        _state.value = ConnectionState.Connected(newToken)
    }

    // ---- one request / one reply -------------------------------------------------------------

    private suspend fun exchange(
        msgId: Int,
        tokenValue: Int,
        fields: List<Pair<String, Any?>>,
        timeoutMs: Long,
    ): CameraMessage.Response {
        val waiter = Pending(msgId, CompletableDeferred())
        pending = waiter
        val startedNs = System.nanoTime()
        try {
            val text = CameraMessage.encodeRequest(msgId, tokenValue, fields)
            log.tx(text)
            try {
                connection.write(text)
            } catch (e: IOException) {
                throw CameraException.ConnectionLost("Write failed: ${e.message ?: e.javaClass.simpleName}", e)
            }
            val reply = withTimeoutOrNull(timeoutMs) { waiter.reply.await() }
            if (reply == null) {
                log.sys("[TIMEOUT] no reply to msg_id $msgId within $timeoutMs ms")
                throw CameraException.Timeout(msgId, timeoutMs)
            }
            val ms = (System.nanoTime() - startedNs) / 1_000_000
            log.sys("[RTT] msg_id $msgId rval ${reply.rval} in $ms ms")
            return reply
        } finally {
            if (pending === waiter) pending = null
        }
    }

    // ---- callbacks from the reader thread ----------------------------------------------------

    override fun onMessage(json: String) {
        log.rx(json)
        when (val message = CameraMessage.parse(json)) {
            is CameraMessage.Response -> {
                val waiting = pending
                if (waiting != null && waiting.msgId == message.msgId) {
                    waiting.reply.complete(message)
                } else {
                    log.sys("[WARN] reply to msg_id ${message.msgId} was not expected (late or unsolicited), ignored")
                }
            }
            is CameraMessage.Event -> _events.tryEmit(message)
            is CameraMessage.Malformed -> log.sys("[WARN] unusable message from the camera: ${message.reason}")
        }
    }

    override fun onClosed(cause: Throwable?) {
        val reason = cause?.message ?: "closed by the camera"
        failPending(CameraException.ConnectionLost("Connection lost: $reason", cause))
        if (!closedByUser) _state.value = ConnectionState.Failed("Connection lost: $reason")
    }

    private fun failPending(error: CameraException) {
        pending?.reply?.completeExceptionally(error)
    }
}
