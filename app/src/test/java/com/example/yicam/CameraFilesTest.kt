/**
 * Responsible for: tests of CameraFiles against FakeCameraServer (several folders, 150 unsorted files, hiding, cache, not connected, timeout, camera busy).
 * Not responsible for: unit tests of the parser (ListingParserTest.kt) or the screen (FilesScreen.kt).
 */
package com.example.yicam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CameraFilesTest {
    private lateinit var server: FakeCameraServer
    private lateinit var scope: CoroutineScope
    private lateinit var session: CameraSession
    private lateinit var files: CameraFiles

    @Before
    fun setUp() {
        server = FakeCameraServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        session = CameraSession(
            FakeCameraNetwork(),
            TrafficLog(),
            scope,
            CameraSession.Config(
                host = "127.0.0.1",
                port = server.port,
                pollBatteryMs = 60_000L,
                pollSdMs = 60_000L,
                eventRefreshDelayMs = 20L,
            ),
        )
        files = CameraFiles(session, scope)
    }

    @After
    fun tearDown() {
        session.disconnect()
        scope.cancel()
        server.close()
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        repeat(300) {
            if (condition()) return
            delay(25)
        }
        fail("timed out waiting for: $what")
    }

    private suspend fun connect() {
        session.connect()
        waitUntil("connected") { session.state.value is SessionState.Connected }
    }

    @Test
    fun folders_hidesEosmisc_andCdsToTheMediaRoot() = test {
        connect()
        val folders = files.folders()
        assertEquals(listOf("102MEDIA", "101MEDIA", "Camera"), folders.map { it.name })
        val cd = server.received.last { it.contains("\"msg_id\":1283") }
        assertTrue(cd.contains("\"param\":\"\\/tmp\\/fuse_d\\/DCIM\""))
        assertEquals(ListingParser.MEDIA_ROOT, server.currentDir)
    }

    @Test
    fun files_150Unsorted_areNewestFirst_thmGrouped_hiddenFolderDropped() = test {
        val names = (1..150).map { n -> "YDXJ${n.toString().padStart(4, '0')}.jpg" }
        val shuffled = names.shuffled()
        val entries = ArrayList<Pair<String, String>>()
        for (name in shuffled) entries.add(name to "12345 bytes|2025-10-14 17:05:56")
        entries.add("YDXJ0042.THM" to "80000 bytes|2025-10-14 17:05:56")
        entries.add("YDXJ0042.mp4" to "999 bytes|2025-10-14 17:05:56")
        entries.add(".AutoPortrait/" to "0 bytes|2025-10-15 00:07:02")
        server.setListing("${ListingParser.MEDIA_ROOT}/101MEDIA", Fixtures.listingReply(*entries.toTypedArray()))

        connect()
        val list = files.files("101MEDIA")
        assertEquals(151, list.size)
        assertEquals("YDXJ0150.jpg", list.first().name)
        assertEquals("YDXJ0001.jpg", list.last().name)
        assertTrue(list.none { it.name.endsWith(".THM", ignoreCase = true) })
        assertTrue(list.none { it.name.startsWith(".") })
        val video = list.single { it.name == "YDXJ0042.mp4" }
        assertEquals("YDXJ0042.THM", video.thumbnailName)
        val cd = server.received.last { it.contains("\"msg_id\":1283") }
        assertTrue(cd.contains("101MEDIA"))
    }

    @Test
    fun cache_secondFoldersCall_doesNotTalkToTheCameraAgain() = test {
        connect()
        files.folders()
        val afterFirst = server.received.size
        files.folders()
        assertEquals(afterFirst, server.received.size)
        files.folders(force = true)
        assertTrue(server.received.size > afterFirst)
    }

    @Test
    fun listing_usesTheExistingSession_thenRefreshStillWorks() = test {
        connect()
        assertEquals(1, server.acceptedConnections.get())
        files.folders()
        files.files("101MEDIA")
        assertEquals(1, server.acceptedConnections.get())
        session.refresh()
        waitUntil("settings still read") { session.settings.value != null }
        assertEquals(1, server.acceptedConnections.get())
    }

    @Test
    fun notConnected_isReported() = test {
        try {
            files.folders()
            fail("expected not connected")
        } catch (e: CameraException.NotConnected) {
            // expected
        }
        files.screenOpened()
        waitUntil("not connected on screen") { files.state.value.problem is FilesProblem.NotConnected }
    }

    @Test
    fun timeout_isReported() = test {
        server.neverAnswer = setOf(1282)
        connect()
        try {
            files.folders()
            fail("expected timeout")
        } catch (e: CameraException.Timeout) {
            assertEquals(1282, e.msgId)
        }
    }

    @Test
    fun cameraBusy_rvalMinus21_isReported() = test {
        server.forcedRval[1282] = -21
        connect()
        try {
            files.folders()
            fail("expected busy")
        } catch (e: Exception) {
            assertEquals("camera busy", e.message)
        }
        files.loadFolders(force = true)
        waitUntil("busy on screen") { files.state.value.problem is FilesProblem.CameraBusy }
    }

    @Test
    fun listingWhileRecording_stillReturnsFolders() = test {
        connect()
        server.appStatus = "record"
        session.refresh()
        waitUntil("record") { session.status.value.appStatus == "record" }
        val folders = files.folders()
        assertTrue(folders.isNotEmpty())
        files.loadFolders(force = true)
        waitUntil("loaded while recording") { !files.state.value.loading && files.state.value.folders.isNotEmpty() }
        assertTrue(files.state.value.recording)
    }
}
