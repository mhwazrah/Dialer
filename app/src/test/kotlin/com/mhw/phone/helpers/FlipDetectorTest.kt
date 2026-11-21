package com.mhw.phone.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipDetectorTest {
    private val up = 9.8f
    private val down = -9.8f

    /** Feeds (z, timeMs) samples and returns the times at which the detector fired. */
    private fun FlipDetector.firedAt(vararg samples: Pair<Float, Long>) = samples.filter { onSample(it.first, it.second) }.map { it.second }

    @Test
    fun firesOnceAfterFaceUpThenFaceDownHeld() {
        val fired = FlipDetector(holdMs = 400).firedAt(up to 0, down to 100, down to 300, down to 500, down to 900)
        assertEquals(listOf(500L), fired)
    }

    @Test
    fun neverFiresWhenAlreadyFaceDownAtRingStart() {
        assertTrue(FlipDetector(holdMs = 400).firedAt(down to 0, down to 1000, down to 5000).isEmpty())
    }

    @Test
    fun aShortWobbleDoesNotCount() {
        val detector = FlipDetector(holdMs = 400)
        assertTrue(detector.firedAt(up to 0, down to 100, down to 300, 0f to 400, down to 450, down to 700).isEmpty())
        assertTrue(detector.onSample(down, 850)) // held 400 ms since it went down again at 450
    }

    @Test
    fun liftingBackUpResetsTheHold() {
        val detector = FlipDetector(holdMs = 400)
        assertFalse(detector.onSample(up, 0))
        assertFalse(detector.onSample(down, 100))
        assertFalse(detector.onSample(up, 300))
        assertFalse(detector.onSample(down, 600))
        assertFalse(detector.onSample(down, 900))
        assertTrue(detector.onSample(down, 1000))
    }
}
