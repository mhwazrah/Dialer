package com.mhw.phone.helpers

/**
 * Detects the phone being turned face-down from the accelerometer's z axis (+9.8 face-up, -9.8 face-down).
 * Fires once, and only after the phone was seen face-up first, so a phone already lying face-down when a
 * call arrives keeps ringing. The face-down position must be held for [holdMs] to ignore a passing wobble.
 */
class FlipDetector(private val holdMs: Long = 400) {
    private var wasFaceUp = false
    private var faceDownSinceMs = -1L
    private var fired = false

    /** Feeds one sample; true exactly once, when the flip is confirmed. */
    fun onSample(z: Float, timeMs: Long): Boolean {
        if (fired) return false
        when {
            z > FACE_UP_Z -> {
                wasFaceUp = true
                faceDownSinceMs = -1L
            }
            z < FACE_DOWN_Z && wasFaceUp -> {
                if (faceDownSinceMs < 0) faceDownSinceMs = timeMs
                if (timeMs - faceDownSinceMs >= holdMs) fired = true
            }
            else -> faceDownSinceMs = -1L
        }
        return fired
    }

    private companion object {
        const val FACE_UP_Z = 3f // screen tilted up, e.g. in the hand or on a desk
        const val FACE_DOWN_Z = -8f // screen down, close to flat
    }
}
