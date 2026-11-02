/**
 * Responsible for: unit tests of LiveOrientation and LiveLayout (cycling the orientation, reading a saved name, the sizes for horizontal and vertical pictures, empty cases).
 * Not responsible for: drawing the picture (LiveScreen.kt) or saving it (AppPrefs.kt).
 */
package com.example.yicam

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveOrientationTest {
    @Test
    fun next_cyclesThroughAllThree() {
        assertEquals(LiveOrientation.VERTICAL_RIGHT, LiveOrientation.VERTICAL_LEFT.next())
        assertEquals(LiveOrientation.HORIZONTAL, LiveOrientation.VERTICAL_RIGHT.next())
        assertEquals(LiveOrientation.VERTICAL_LEFT, LiveOrientation.HORIZONTAL.next())
    }

    @Test
    fun fromName_defaultsToVerticalLeft() {
        assertEquals(LiveOrientation.VERTICAL_LEFT, LiveOrientation.fromName(null))
        assertEquals(LiveOrientation.VERTICAL_LEFT, LiveOrientation.fromName("nonsense"))
        assertEquals(LiveOrientation.HORIZONTAL, LiveOrientation.fromName("HORIZONTAL"))
    }

    @Test
    fun degrees_areTheTurnsOfThePicture() {
        assertEquals(270f, LiveOrientation.VERTICAL_LEFT.degrees, 0.001f)
        assertEquals(90f, LiveOrientation.VERTICAL_RIGHT.degrees, 0.001f)
        assertEquals(0f, LiveOrientation.HORIZONTAL.degrees, 0.001f)
    }

    @Test
    fun fit_horizontal_keepsTheProportions() {
        val s = LiveLayout.fit(1000f, 500f, 2f, LiveOrientation.HORIZONTAL)
        assertEquals(1000f, s.shownWidth, 0.01f)
        assertEquals(500f, s.shownHeight, 0.01f)
        assertEquals(1000f, s.viewWidth, 0.01f)
        assertEquals(500f, s.viewHeight, 0.01f)
    }

    @Test
    fun fit_vertical_limitedByTheWidth() {
        // a 4:3 picture turned to 3:4 in a 300 x 600 area: 300 x 400 is shown, the view before turning is 400 x 300
        val s = LiveLayout.fit(300f, 600f, 4f / 3f, LiveOrientation.VERTICAL_LEFT)
        assertEquals(300f, s.shownWidth, 0.01f)
        assertEquals(400f, s.shownHeight, 0.01f)
        assertEquals(400f, s.viewWidth, 0.01f)
        assertEquals(300f, s.viewHeight, 0.01f)
    }

    @Test
    fun fit_vertical_limitedByTheHeight() {
        val s = LiveLayout.fit(600f, 300f, 4f / 3f, LiveOrientation.VERTICAL_RIGHT)
        assertEquals(225f, s.shownWidth, 0.01f)
        assertEquals(300f, s.shownHeight, 0.01f)
        assertEquals(300f, s.viewWidth, 0.01f)
        assertEquals(225f, s.viewHeight, 0.01f)
    }

    @Test
    fun fit_withNothingToFit_isZero() {
        assertEquals(LiveLayout.Sizes(0f, 0f, 0f, 0f), LiveLayout.fit(0f, 500f, 2f, LiveOrientation.HORIZONTAL))
        assertEquals(LiveLayout.Sizes(0f, 0f, 0f, 0f), LiveLayout.fit(500f, 500f, 0f, LiveOrientation.VERTICAL_LEFT))
    }
}
