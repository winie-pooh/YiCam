/**
 * Responsible for: unit tests of ListingParser (real DCIM listing, 1.4 GB size, hidden .AutoPortrait/, .THM grouping, newest-first order).
 * Not responsible for: tests of sending 1283/1282 (CameraFilesTest.kt) or drawing (FilesScreen.kt).
 */
package com.example.yicam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingParserTest {
    private fun reply(json: String): CameraMessage.Response = CameraMessage.parse(json) as CameraMessage.Response

    @Test
    fun folders_realDcimListing_hidesEosmisc_andPutsNewerMediaFirst() {
        val folders = ListingParser.folders(reply(Fixtures.LISTING_REPLY))
        assertEquals(listOf("102MEDIA", "101MEDIA", "Camera"), folders.map { it.name })
        assertEquals("2008-03-09 06:26:52", folders[0].dateText)
        assertEquals("2025-10-14 17:05:56", folders[1].dateText)
        assertTrue(folders.none { it.name.equals("EOSMISC", ignoreCase = true) })
    }

    @Test
    fun folders_hiddenDotFolder_isDropped() {
        val json = Fixtures.listingReply(
            "101MEDIA/" to "0 bytes|2025-10-14 17:05:56",
            ".AutoPortrait/" to "0 bytes|2025-10-15 00:07:02",
            "Camera/" to "0 bytes|2025-10-15 00:07:02",
        )
        val folders = ListingParser.folders(reply(json))
        assertEquals(listOf("101MEDIA", "Camera"), folders.map { it.name })
    }

    @Test
    fun files_sizeAbove2G_isLong_andDateIsText() {
        val json = Fixtures.listingReply(
            "YDXJ0001.mp4" to "3000000000 bytes|2025-10-14 17:05:56",
        )
        val files = ListingParser.files(reply(json), "101MEDIA")
        assertEquals(1, files.size)
        assertEquals(3_000_000_000L, files[0].sizeBytes)
        assertEquals("2025-10-14 17:05:56", files[0].dateText)
        assertEquals(FileKind.VIDEO, files[0].kind)
    }

    @Test
    fun files_onePointFourGb_fromTheRoadmapExample() {
        val json = Fixtures.listingReply(
            "YDXJ0042.mp4" to "1503238553 bytes|2025-10-14 17:05:56",
        )
        assertEquals(1_503_238_553L, ListingParser.files(reply(json), "101MEDIA").single().sizeBytes)
    }

    @Test
    fun files_thmIsAttachedToItsVideo_andIsNotARow() {
        val json = Fixtures.listingReply(
            "YDXJ0002.THM" to "80000 bytes|2025-10-14 17:06:00",
            "YDXJ0002.mp4" to "12345678 bytes|2025-10-14 17:06:00",
            "YDXJ0001.jpg" to "4000000 bytes|2025-10-14 17:05:56",
        )
        val files = ListingParser.files(reply(json), "101MEDIA")
        assertEquals(listOf("YDXJ0002.mp4", "YDXJ0001.jpg"), files.map { it.name })
        assertEquals("YDXJ0002.THM", files[0].thumbnailName)
        assertNull(files[1].thumbnailName)
        assertEquals(FileKind.PHOTO, files[1].kind)
    }

    @Test
    fun files_newestYdxjNumberFirst_fallbackName() {
        val json = Fixtures.listingReply(
            "YDXJ0001.jpg" to "1 bytes|a",
            "readme.txt" to "10 bytes|b",
            "YDXJ0010.mp4" to "2 bytes|c",
            "YDXJ0009.jpg" to "3 bytes|d",
        )
        val names = ListingParser.files(reply(json), "101MEDIA").map { it.name }
        assertEquals(listOf("YDXJ0010.mp4", "YDXJ0009.jpg", "YDXJ0001.jpg", "readme.txt"), names)
    }

    @Test
    fun files_orphanThm_isShownAsOther() {
        val json = Fixtures.listingReply("YDXJ0099.THM" to "50000 bytes|2025-10-14 17:05:56")
        val file = ListingParser.files(reply(json), "101MEDIA").single()
        assertEquals("YDXJ0099.THM", file.name)
        assertEquals(FileKind.OTHER, file.kind)
    }

    @Test
    fun kindOf_extensions() {
        assertEquals(FileKind.VIDEO, ListingParser.kindOf("YDXJ0001.MP4"))
        assertEquals(FileKind.PHOTO, ListingParser.kindOf("YDXJ0001.JPG"))
        assertEquals(FileKind.THUMBNAIL, ListingParser.kindOf("YDXJ0001.thm"))
        assertEquals(FileKind.OTHER, ListingParser.kindOf("notes.txt"))
    }
}
