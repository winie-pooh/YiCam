/**
 * Responsible for: unit tests of GridGeometry and GridMode (line positions for each mode, empty cases, switching to the next mode, reading a saved name).
 * Not responsible for: drawing the grid (LiveScreen.kt) or saving it (AppPrefs.kt).
 */
package com.example.yicam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridGeometryTest {
    @Test
    fun off_hasNoLines() {
        assertTrue(GridGeometry.lines(300f, 150f, GridMode.OFF).isEmpty())
    }

    @Test
    fun grid3x3_hasFourInnerLinesAtThirds() {
        val lines = GridGeometry.lines(300f, 150f, GridMode.GRID_3X3)
        assertEquals(4, lines.size)
        val vertical = lines.filter { it.x1 == it.x2 }
        val horizontal = lines.filter { it.y1 == it.y2 }
        assertEquals(listOf(100f, 200f), vertical.map { it.x1 })
        assertEquals(listOf(50f, 100f), horizontal.map { it.y1 })
        assertTrue(vertical.all { it.y1 == 0f && it.y2 == 150f })
        assertTrue(horizontal.all { it.x1 == 0f && it.x2 == 300f })
    }

    @Test
    fun grid4x4_hasSixInnerLines() {
        assertEquals(6, GridGeometry.lines(400f, 200f, GridMode.GRID_4X4).size)
    }

    @Test
    fun emptySize_hasNoLines() {
        assertTrue(GridGeometry.lines(0f, 150f, GridMode.GRID_3X3).isEmpty())
        assertTrue(GridGeometry.lines(300f, -1f, GridMode.GRID_3X3).isEmpty())
    }

    @Test
    fun next_cyclesThroughAllModes() {
        assertEquals(GridMode.GRID_3X3, GridMode.OFF.next())
        assertEquals(GridMode.GRID_4X4, GridMode.GRID_3X3.next())
        assertEquals(GridMode.OFF, GridMode.GRID_4X4.next())
    }

    @Test
    fun fromName_readsASavedModeAndFallsBackToOff() {
        assertEquals(GridMode.GRID_4X4, GridMode.fromName("GRID_4X4"))
        assertEquals(GridMode.OFF, GridMode.fromName("nonsense"))
        assertEquals(GridMode.OFF, GridMode.fromName(null))
    }
}
