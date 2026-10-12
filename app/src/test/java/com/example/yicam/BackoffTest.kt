/**
 * Responsible for: unit tests of Backoff (the steps, the cap and the plus or minus 20 percent spread).
 * Not responsible for: tests of the reconnect loop itself (CameraSessionTest.kt).
 */
package com.example.yicam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test
    fun baseDelaysFollowTheSteps_andStayAtTheLastStep() {
        val backoff = Backoff()
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)
        assertEquals(expected, (1..8).map { backoff.baseMs(it) })
    }

    @Test
    fun attemptZeroOrNegativeUsesTheFirstStep() {
        val backoff = Backoff()
        assertEquals(1_000L, backoff.baseMs(0))
        assertEquals(1_000L, backoff.baseMs(-3))
    }

    @Test
    fun spreadStaysWithinTwentyPercent() {
        for (random in listOf(0.0, 0.25, 0.5, 0.75, 0.999999)) {
            val delay = Backoff(random = { random }).delayMs(3) // base 4000
            assertTrue("delay $delay for random $random", delay in 3_199L..4_800L)
        }
    }

    @Test
    fun middleOfTheRandomRangeGivesTheBaseDelay() {
        val delay = Backoff(random = { 0.5 }).delayMs(2)
        assertTrue("delay $delay", delay in 1_999L..2_001L)
    }

    @Test
    fun noSpreadGivesExactlyTheBaseDelay() {
        val backoff = Backoff(stepsMs = listOf(20L, 40L), spread = 0.0)
        assertEquals(20L, backoff.delayMs(1))
        assertEquals(40L, backoff.delayMs(2))
        assertEquals(40L, backoff.delayMs(9))
    }
}
