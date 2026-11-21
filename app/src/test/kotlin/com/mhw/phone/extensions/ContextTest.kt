package com.mhw.phone.extensions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mhw.phone.models.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContextTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun configIsASingletonAcrossContexts() {
        assertSame(context.config, context.applicationContext.config)
    }

    @Test
    fun createNewTimerUsesDefaults() {
        context.config.callVibration = false
        val timer = context.createNewTimer()
        assertEquals(600, timer.seconds)
        assertEquals(TimerState.Idle, timer.state)
        assertEquals("Timer", timer.title)
        assertEquals(false, timer.vibrate)
        assertTrue(timer.createdAt > 0)
        context.config.callVibration = true
        assertEquals(true, context.createNewTimer().vibrate)
    }

    @Test
    fun displaySettingsKeyChangesWithDisplaySettings() {
        val before = context.displaySettingsKey()
        assertEquals(before, context.displaySettingsKey())
        val c = context.config
        c.useDividers = !c.useDividers
        val afterDividers = context.displaySettingsKey()
        assertNotEquals(before, afterDividers)
        c.swipeLeftAction = c.swipeLeftAction + 1
        assertNotEquals(afterDividers, context.displaySettingsKey())
    }

    @Test
    fun displaySettingsKeyIgnoresUnrelatedSettings() {
        val before = context.displaySettingsKey()
        context.config.toneVolume = context.config.toneVolume + 1
        assertEquals(before, context.displaySettingsKey())
    }
}
