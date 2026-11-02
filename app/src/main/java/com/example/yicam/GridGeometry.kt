/**
 * Responsible for: the grid overlay of the live view: the modes (off, 3x3, 4x4), the next mode when the button is pressed and the pure calculation of the line positions for a given video size.
 * Not responsible for: drawing the lines (LiveScreen.kt) or saving the choice (AppPrefs.kt).
 */
package com.example.yicam

enum class GridMode(val columns: Int, val rows: Int) {
    OFF(0, 0),
    GRID_3X3(3, 3),
    GRID_4X4(4, 4);

    /** The mode the grid button switches to: off, 3x3, 4x4, off, ... */
    fun next(): GridMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Unknown or missing names give OFF. */
        fun fromName(name: String?): GridMode = entries.firstOrNull { it.name == name } ?: OFF
    }
}

data class GridLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

object GridGeometry {
    /** The inner lines that split a picture of [width] x [height] into the mode's cells (no border lines). */
    fun lines(width: Float, height: Float, mode: GridMode): List<GridLine> {
        if (mode == GridMode.OFF || width <= 0f || height <= 0f) return emptyList()
        val result = ArrayList<GridLine>()
        for (i in 1 until mode.columns) {
            val x = width * i / mode.columns
            result += GridLine(x, 0f, x, height)
        }
        for (i in 1 until mode.rows) {
            val y = height * i / mode.rows
            result += GridLine(0f, y, width, y)
        }
        return result
    }
}
