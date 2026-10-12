/**
 * Responsible for: integration tests of CameraApi + CameraConnection against FakeCameraServer (session, token, -4 refresh, timeouts, events, fragmentation, reconnect, serialization, raw commands, log masking).
 * Not responsible for: unit tests of the parser, JSON or masking (their own test files).
 */
package com.example.yicam

import java.net.ServerSocket
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CameraApiTest {
    private lateinit var server: FakeCameraServer
    private lateinit var scope: CoroutineScope
    private lateinit var log: TrafficLog

    @Before
    fun setUp() {
        server = FakeCameraServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        log = TrafficLog()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun newApi(autoReconnect: Boolean = true, port: Int = server.port): CameraApi {
        val connection = CameraConnection("127.0.0.1", port, SocketFactory.getDefault(), scope, log, connectTimeoutMs = 2_000)
        return CameraApi(connection, log, autoReconnect)
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            delay(25)
        }
        fail("timed out waiting for: $what")
    }

    private suspend inline fun <reified E : Throwable> expectFailure(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw e
        }
        fail("expected ${E::class.simpleName}")
        throw IllegalStateException()
    }

    // ---- session -------------------------------------------------------------------------

    @Test
    fun connect_takesToken_andPublishesConnectedState() = test {
        val api = newApi()
        api.connect()
        assertEquals(ConnectionState.Connected(1), api.state.value)
        assertEquals(1, api.currentToken)
        assertEquals(listOf("""{"msg_id":257,"token":0}"""), server.received.toList())
    }

    @Test
    fun connect_whenNothingListens_failsWithConnectFailed() = test {
        val closedPort = ServerSocket(0).use { it.localPort }
        val api = newApi(port = closedPort)
        expectFailure<CameraException.ConnectFailed> { api.connect() }
        assertTrue(api.state.value is ConnectionState.Failed)
    }

    @Test
    fun connectTwice_closesTheOldSocket_andTakesANewToken() = test {
        val api = newApi()
        api.connect()
        api.connect()
        assertEquals(2, server.acceptedConnections.get())
        waitUntil("old socket closed") { server.closedConnections.get() == 1 }
        assertEquals(2, api.currentToken)
        assertEquals(ConnectionState.Connected(2), api.state.value)
    }

    // ---- commands ------------------------------------------------------------------------

    @Test
    fun settings_returnsAllParams_andSendsTheToken() = test {
        val api = newApi()
        api.connect()
        val reply = api.send(Cmd.GET_SETTINGS)
        assertTrue(reply.isOk)
        assertEquals(Fixtures.SETTINGS_PARAM_COUNT, reply.paramList()!!.size)
        assertEquals("""{"msg_id":3,"token":1}""", server.received[1])
    }

    @Test
    fun battery_paramIsReadTolerantly() = test {
        val api = newApi()
        api.connect()
        val reply = api.send(Cmd.BATTERY)
        assertEquals("82", reply.paramString())
        assertEquals(82L, reply.paramLong())
    }

    @Test
    fun startPreview_sendsParam_andVfStartEventFollows() = test {
        val api = newApi()
        api.connect()
        val event = async(start = CoroutineStart.UNDISPATCHED) { api.events.first() }
        val reply = api.send(Cmd.START_PREVIEW, listOf("param" to "none_force"))
        assertTrue(reply.isOk)
        assertEquals("""{"msg_id":259,"token":1,"param":"none_force"}""", server.received.last())
        assertEquals("vf_start", event.await().type)
    }

    @Test
    fun listFiles_realListingIsDeliveredAsParsedJson() = test {
        val api = newApi()
        api.connect()
        val reply = api.send(Cmd.LIST_FILES, listOf("param" to "-D -S"))
        @Suppress("UNCHECKED_CAST")
        val listing = reply.fields["listing"] as List<Map<String, Any?>>
        assertEquals(4, listing.size)
        assertEquals("0 bytes|2025-10-14 17:05:56", listing[0]["101MEDIA/"])
        assertEquals("""{"msg_id":1282,"token":1,"param":"-D -S"}""", server.received.last())
    }

    // ---- transport faults ----------------------------------------------------------------

    @Test
    fun repliesArrivingInTinyFragments_areAssembled() = test {
        val api = newApi()
        api.connect()
        server.fragmentReplies = true
        val reply = api.send(Cmd.GET_SETTINGS)
        assertEquals(Fixtures.SETTINGS_PARAM_COUNT, reply.paramList()!!.size)
    }

    @Test
    fun replyAndEventInOneWrite_bothArrive() = test {
        val api = newApi()
        api.connect()
        server.gluedEventAfterReply = Fixtures.BATTERY_EVENT
        val event = async(start = CoroutineStart.UNDISPATCHED) { api.events.first() }
        val reply = api.send(Cmd.BATTERY)
        assertEquals(13, reply.msgId)
        assertEquals("battery", event.await().type)
    }

    @Test
    fun eventWhileWaiting_doesNotCompleteTheRequest() = test {
        val api = newApi()
        api.connect()
        server.replyDelayMs = 200
        val event = async(start = CoroutineStart.UNDISPATCHED) { api.events.first() }
        val reply = async { api.send(Cmd.BATTERY) }
        delay(60)
        server.pushEvent(Fixtures.VF_START_EVENT)
        assertEquals("vf_start", event.await().type)
        assertEquals(13, reply.await().msgId)
    }

    @Test
    fun unknownEventType_isDeliveredNotFatal() = test {
        val api = newApi()
        api.connect()
        val event = async(start = CoroutineStart.UNDISPATCHED) { api.events.first() }
        server.pushEvent("""{"msg_id":7,"type":"brand_new","param":"x"}""")
        assertEquals("brand_new", event.await().type)
        assertTrue(api.send(Cmd.BATTERY).isOk)
    }

    @Test
    fun garbageFromTheCamera_isIgnored() = test {
        val api = newApi()
        api.connect()
        server.pushEvent("%%% not json %%% {\"msg_id\": oops} ")
        assertTrue(api.send(Cmd.BATTERY).isOk)
    }

    // ---- token ---------------------------------------------------------------------------

    @Test
    fun invalidToken_isRefreshedOnce_andTheCommandIsRepeated() = test {
        val api = newApi()
        api.connect()
        server.expireToken()
        val reply = api.send(Cmd.BATTERY)
        assertTrue(reply.isOk)
        assertEquals(
            listOf(
                """{"msg_id":257,"token":0}""",
                """{"msg_id":13,"token":1}""",
                """{"msg_id":257,"token":0}""",
                """{"msg_id":13,"token":2}""",
            ),
            server.received.toList(),
        )
        assertEquals(2, api.currentToken)
    }

    @Test
    fun invalidToken_thatKeepsBeingRejected_isReturnedAfterOneRetry() = test {
        val api = newApi()
        api.connect()
        server.rejectEveryCommand = true
        val reply = api.send(Cmd.BATTERY)
        assertEquals(-4, reply.rval)
        assertEquals(2, server.received.count { it.contains("\"msg_id\":13") })
    }

    // ---- timeouts ------------------------------------------------------------------------

    @Test
    fun silentCamera_givesTimeout_andLaterCommandsStillWork() = test {
        val api = newApi()
        api.connect()
        server.neverAnswer = setOf(13)
        val e = expectFailure<CameraException.Timeout> { api.send(Cmd.BATTERY, timeoutMs = 150) }
        assertEquals(13, e.msgId)
        server.neverAnswer = emptySet()
        assertTrue(api.send(Cmd.GET_SETTINGS).isOk)
        assertTrue(log.lines.value.any { it.contains("[TIMEOUT]") })
    }

    @Test
    fun lateReplyOfATimedOutCommand_isIgnoredByTheNextCommand() = test {
        val api = newApi()
        api.connect()
        server.replyDelayMs = 400
        expectFailure<CameraException.Timeout> { api.send(Cmd.BATTERY, timeoutMs = 120) }
        server.replyDelayMs = 0
        val reply = api.send(Cmd.GET_SETTINGS)
        assertEquals(3, reply.msgId)
        assertTrue(log.lines.value.any { it.contains("was not expected") })
    }

    // ---- reconnect -----------------------------------------------------------------------

    @Test
    fun connectionDroppedByCamera_isReported_andNextSendReconnects() = test {
        val api = newApi()
        api.connect()
        server.dropClient()
        waitUntil("state Failed") { api.state.value is ConnectionState.Failed }
        val reply = api.send(Cmd.BATTERY)
        assertTrue(reply.isOk)
        assertEquals(2, server.acceptedConnections.get())
        assertEquals(ConnectionState.Connected(2), api.state.value)
    }

    @Test
    fun connectionDroppedWhileWaiting_failsTheRequestWithConnectionLost() = test {
        val api = newApi()
        api.connect()
        server.replyDelayMs = 300
        val pending = async { runCatching { api.send(Cmd.BATTERY) } }
        delay(80)
        server.dropClient()
        val error = pending.await().exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is CameraException.ConnectionLost)
    }

    @Test
    fun autoReconnectOff_sendOnLostConnectionThrowsNotConnected() = test {
        val api = newApi(autoReconnect = false)
        api.connect()
        server.dropClient()
        waitUntil("state Failed") { api.state.value is ConnectionState.Failed }
        expectFailure<CameraException.NotConnected> { api.send(Cmd.BATTERY) }
    }

    @Test
    fun disconnectByUser_neverReconnectsBehindTheBack() = test {
        val api = newApi()
        api.connect()
        api.disconnect()
        assertEquals(ConnectionState.Disconnected, api.state.value)
        expectFailure<CameraException.NotConnected> { api.send(Cmd.BATTERY) }
        assertEquals(1, server.acceptedConnections.get())
        api.connect() // explicit connect works again
        assertTrue(api.send(Cmd.BATTERY).isOk)
    }

    @Test
    fun neverConnected_sendReconnectsOnItsOwn() = test {
        val api = newApi()
        assertTrue(api.send(Cmd.BATTERY).isOk)
        assertEquals(ConnectionState.Connected(1), api.state.value)
    }

    // ---- serialization -------------------------------------------------------------------

    @Test
    fun concurrentSends_goOneAtATime() = test {
        val api = newApi()
        api.connect()
        server.replyDelayMs = 20
        val replies = coroutineScope { (1..8).map { async { api.send(Cmd.BATTERY) } }.awaitAll() }
        assertTrue(replies.all { it.isOk && it.msgId == 13 })
        assertFalse("client sent a request before the previous reply", server.sawOverlap)
        assertEquals(9, server.received.size)
    }

    @Test
    fun cancellingACaller_doesNotBreakTheNextCommand() = test {
        val api = newApi()
        api.connect()
        server.replyDelayMs = 300
        val job = scope.launch { api.send(Cmd.BATTERY) }
        delay(60)
        job.cancel()
        server.replyDelayMs = 0
        assertTrue(api.send(Cmd.GET_SETTINGS).isOk)
    }

    // ---- raw commands --------------------------------------------------------------------

    @Test
    fun raw_substitutesToken_andWritesSlashesTheCameraWay() = test {
        val api = newApi()
        api.connect()
        val reply = api.sendRaw("""{"msg_id":1283,"token":%TOKEN%,"param":"/tmp/fuse_d/DCIM"}""")
        assertTrue(reply.isOk)
        assertEquals("""{"msg_id":1283,"token":1,"param":"\/tmp\/fuse_d\/DCIM"}""", server.received.last())
        assertEquals("/tmp/fuse_d/DCIM", reply.fields["pwd"])
    }

    @Test
    fun raw_withoutToken_usesTheSessionToken() = test {
        val api = newApi()
        api.connect()
        api.sendRaw("""{"msg_id":13}""")
        assertEquals("""{"msg_id":13,"token":1}""", server.received.last())
    }

    @Test
    fun raw_explicitWrongToken_isSentAsIs_withoutRefresh() = test {
        val api = newApi()
        api.connect()
        val before = server.received.size
        val reply = api.sendRaw("""{"msg_id":13,"token":99}""")
        assertEquals(-4, reply.rval)
        assertEquals(before + 1, server.received.size)
        assertEquals(1, api.currentToken)
    }

    @Test
    fun raw_badJsonOrNoMsgId_isRejectedBeforeSending() = test {
        val api = newApi()
        api.connect()
        val before = server.received.size
        expectFailure<CameraException.BadRequest> { api.sendRaw("{oops") }
        expectFailure<CameraException.BadRequest> { api.sendRaw("""{"param":"x"}""") }
        expectFailure<CameraException.BadRequest> { api.sendRaw("[1,2]") }
        assertEquals(before, server.received.size)
    }

    // ---- log -----------------------------------------------------------------------------

    @Test
    fun log_showsTrafficAndTimings_butHidesSecrets() = test {
        val api = newApi()
        server.leakSecrets = true
        api.connect()
        api.send(Cmd.GET_SETTINGS)
        val text = log.asText()
        assertTrue(text.contains(">> {\"msg_id\":3,\"token\":1}"))
        assertTrue(text.contains("<< { \"rval\": 0, \"msg_id\": 3"))
        assertTrue(text.contains("[RTT] msg_id 3 rval 0 in "))
        assertTrue(text.contains("TCP connected to 127.0.0.1:${server.port}"))
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("ABC123456"))
    }

    @Test
    fun log_explainsAConnectionDrop() = test {
        val api = newApi()
        api.connect()
        server.dropClient()
        waitUntil("state Failed") { api.state.value is ConnectionState.Failed }
        assertTrue(log.lines.value.any { it.contains("[CONN] closed by the camera") || it.contains("[CONN] read error") })
    }
}
