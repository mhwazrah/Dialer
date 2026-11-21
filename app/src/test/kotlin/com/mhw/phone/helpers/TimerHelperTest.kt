package com.mhw.phone.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mhw.phone.extensions.createNewTimer
import com.mhw.phone.extensions.timerDb
import com.mhw.phone.models.Timer
import com.mhw.phone.models.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// TimerHelper takes its DAO from Context.timerDb (the AppDatabase singleton), so an in-memory DB cannot be injected;
// these run against Robolectric's real SQLite file, emptied through the DAO before each test.
@RunWith(RobolectricTestRunner::class)
class TimerHelperTest {
    private lateinit var context: Context
    private lateinit var helper: TimerHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        helper = TimerHelper(context)
        val wipe = Thread { context.timerDb.getTimers().forEach { it.id?.let(context.timerDb::deleteTimer) } }
        wipe.start()
        wipe.join()
    }

    private fun <T> await(block: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<T>()
        block { ref.set(it); latch.countDown() }
        assertTrue("callback not invoked", latch.await(10, TimeUnit.SECONDS))
        return ref.get()
    }

    private fun newTimer(seconds: Int = 60, label: String = "") =
        context.createNewTimer().copy(id = null, seconds = seconds, label = label)

    private fun insert(timer: Timer): Long = await { done -> helper.insertOrUpdateTimer(timer, done) }

    private fun all(): List<Timer> = await { done -> helper.getTimers(done) }

    @Test
    fun insertThenGetTimersReturnsIt() {
        val id = insert(newTimer(seconds = 90, label = "tea"))
        val timers = all()
        assertEquals(1, timers.size)
        assertEquals(id.toInt(), timers[0].id)
        assertEquals(90, timers[0].seconds)
        assertEquals("tea", timers[0].label)
        assertTrue(timers[0].state is TimerState.Idle) // Gson builds a new Idle instance, so no equality
    }

    @Test
    fun getTimersOrdersByCreationTime() {
        insert(newTimer(seconds = 2).copy(createdAt = 200))
        insert(newTimer(seconds = 1).copy(createdAt = 100))
        assertEquals(listOf(1, 2), all().map { it.seconds })
    }

    @Test
    fun getTimerReturnsExistingTimer() {
        val id = insert(newTimer(seconds = 5)).toInt()
        val found = await<Timer> { done -> helper.getTimer(id, done) }
        assertEquals(5, found.seconds)
    }

    @Test
    fun getTimerSkipsCallbackWhenMissing() {
        val latch = CountDownLatch(1)
        helper.getTimer(9999) { latch.countDown() }
        assertFalse(latch.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun insertOrUpdateReplacesExistingRow() {
        val id = insert(newTimer(seconds = 5)).toInt()
        insert(newTimer(seconds = 77).copy(id = id))
        val timers = all()
        assertEquals(1, timers.size)
        assertEquals(77, timers[0].seconds)
    }

    @Test
    fun runningStateSurvivesRoundTrip() {
        val state = TimerState.Running(duration = 1000, endTime = 5000)
        val id = insert(newTimer().copy(state = state)).toInt()
        assertEquals(state, await<Timer> { done -> helper.getTimer(id, done) }.state)
    }

    @Test
    fun deleteRemovesOnlyThatTimerAndInvokesCallback() {
        val keep = insert(newTimer(seconds = 1)).toInt()
        val drop = insert(newTimer(seconds = 2)).toInt()
        await<Unit> { done -> helper.deleteTimer(drop) { done(Unit) } }
        assertEquals(listOf(keep), all().map { it.id })
    }
}
