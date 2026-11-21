package com.mhw.phone.models

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerStateTest {
    @Test
    fun remainingIsEndMinusNow() {
        assertEquals(4_000L, TimerState.Running(10_000L, 10_000L).remaining(now = 6_000L))
    }

    @Test
    fun remainingNeverNegative() {
        assertEquals(0L, TimerState.Running(10_000L, 10_000L).remaining(now = 10_000L))
        assertEquals(0L, TimerState.Running(10_000L, 10_000L).remaining(now = 99_000L))
    }
}
