/**
 * Responsible for: how the live picture is turned on the phone (vertical with the camera on its left side, vertical with the camera on its right side, horizontal) and the pure calculation of the sizes: the shown picture inside the free area and the size of the video view before it is turned.
 * Not responsible for: drawing (LiveScreen.kt), the video player (ExoRtspPlayer.kt) or saving the choice (AppPrefs.kt).
 */
package com.example.yicam

enum class LiveOrientation(val degrees: Float, val vertical: Boolean) {
    /** The camera stands on its left side: the picture is turned 90 degrees counterclockwise. */
    VERTICAL_LEFT(270f, true),

    /** The camera stands on its right side: the picture is turned 90 degrees clockwise. */
    VERTICAL_RIGHT(90f, true),

    /** The picture as the camera sends it. */
    HORIZONTAL(0f, false);

    /** The mode the orientation button switches to. */
    fun next(): LiveOrientation = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Unknown or missing names give VERTICAL_LEFT, the default. */
        fun fromName(name: String?): LiveOrientation = entries.firstOrNull { it.name == name } ?: VERTICAL_LEFT
    }
}

object LiveLayout {
    /**
     * [shownWidth] x [shownHeight] is the picture as the person sees it (after turning); [viewWidth] x [viewHeight] is the size of the
     * video view before it is turned (width and height are swapped for a vertical picture).
     */
    data class Sizes(val shownWidth: Float, val shownHeight: Float, val viewWidth: Float, val viewHeight: Float)

    /** The biggest picture with the right proportions that fits the free area. All zero if something is zero or negative. */
    fun fit(availableWidth: Float, availableHeight: Float, videoAspect: Float, orientation: LiveOrientation): Sizes {
        if (availableWidth <= 0f || availableHeight <= 0f || videoAspect <= 0f) return Sizes(0f, 0f, 0f, 0f)
        val shownAspect = if (orientation.vertical) 1f / videoAspect else videoAspect
        val shownWidth = minOf(availableWidth, availableHeight * shownAspect)
        val shownHeight = shownWidth / shownAspect
        return if (orientation.vertical) Sizes(shownWidth, shownHeight, shownHeight, shownWidth)
        else Sizes(shownWidth, shownHeight, shownWidth, shownHeight)
    }
}
