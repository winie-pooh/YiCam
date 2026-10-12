/**
 * Responsible for: calculating how long to wait before the next reconnect attempt (1, 2, 4, 8, 16, 30 s, then 30 s, with a random spread of plus or minus 20 percent).
 * Not responsible for: doing the waiting or deciding whether to reconnect (CameraSession.kt).
 */
package com.example.yicam

class Backoff(
    private val stepsMs: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L),
    private val spread: Double = 0.2,
    private val random: () -> Double = { Math.random() },
) {
    /** Delay without spread. [attempt] counts from 1; past the last step the last step is used. */
    fun baseMs(attempt: Int): Long = stepsMs[(attempt - 1).coerceIn(0, stepsMs.lastIndex)]

    /** Delay with spread: the base multiplied by a factor between 1 - spread and 1 + spread ([random] must return 0 up to, not including, 1). */
    fun delayMs(attempt: Int): Long {
        val factor = 1.0 - spread + 2.0 * spread * random()
        return (baseMs(attempt) * factor).toLong()
    }
}
