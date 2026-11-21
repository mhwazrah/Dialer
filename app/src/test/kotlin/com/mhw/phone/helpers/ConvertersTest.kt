package com.mhw.phone.helpers

import com.mhw.phone.models.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun stringListRoundTrip() {
        val list = arrayListOf("a", "محمد", "")
        assertEquals(list, converters.jsonToStringList(converters.stringListToJson(list)))
    }

    @Test
    fun stringListFromJsonNullIsEmpty() {
        assertEquals(arrayListOf<String>(), converters.jsonToStringList("null"))
    }

    @Test
    fun timerStateRoundTrips() {
        val states = listOf(
            TimerState.Idle,
            TimerState.Running(60_000L, 1_700_000_000_000L),
            TimerState.Paused(60_000L, 12_000L),
            TimerState.Finished,
        )
        states.forEach {
            val restored = converters.jsonToTimerState(converters.timerStateToJson(it))
            assertEquals(it::class, restored::class)
            if (it is TimerState.Running || it is TimerState.Paused) assertEquals(it, restored)
        }
    }

    @Test
    fun timerStateObjectsKeepIdentityAfterRoundTrip() {
        assertSame(TimerState.Idle, converters.jsonToTimerState(converters.timerStateToJson(TimerState.Idle)))
        assertSame(TimerState.Finished, converters.jsonToTimerState(converters.timerStateToJson(TimerState.Finished)))
    }

    @Test
    fun nullEmptyAndGarbageGiveIdle() {
        assertEquals(TimerState.Idle, converters.jsonToTimerState(null))
        assertEquals(TimerState.Idle, converters.jsonToTimerState(""))
        assertEquals(TimerState.Idle, converters.jsonToTimerState("{garbage"))
    }

    @Test
    fun legacyRunningContinuesFromRemainingTick() {
        val legacy = converters.timerStateToJson(TimerState.Running(60_000L, 0L))
            .replace("\"endTime\":0", "\"tick\":30000")
        val before = System.currentTimeMillis()

        val state = converters.jsonToTimerState(legacy) as TimerState.Running
        val after = System.currentTimeMillis()

        assertEquals(60_000L, state.duration)
        assertTrue(state.endTime in (before + 30_000L)..(after + 30_000L))
    }

    @Test
    fun legacyRunningWithoutTickIsFinished() {
        val legacy = converters.timerStateToJson(TimerState.Running(60_000L, 0L))
        assertEquals(TimerState.Finished, converters.jsonToTimerState(legacy))
    }
}
