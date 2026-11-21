package com.mhw.phone.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class LongTest {
    private fun millis(y: Int, m: Int, d: Int) =
        Calendar.getInstance().apply { clear(); set(y, m, d, 12, 0) }.timeInMillis

    @Test
    fun dayCodeIsSortableIsoDate() {
        assertEquals("2024-03-05", millis(2024, Calendar.MARCH, 5).getDayCode())
    }

    @Test
    fun dayCodesSortChronologically() {
        assertTrue(millis(2023, Calendar.DECEMBER, 31).getDayCode() < millis(2024, Calendar.JANUARY, 1).getDayCode())
    }

    @Test
    fun formattedDurationFromMillis() {
        assertEquals("00:05", 5_000L.getFormattedDuration())
        assertEquals("01:05", 65_000L.getFormattedDuration())
        assertEquals("00:02", 1_500L.getFormattedDuration()) // rounds to the nearest second
    }

    @Test
    fun formattedDurationForceHours() {
        assertEquals("01:00:00", 3_600_000L.getFormattedDuration(true))
    }
}
