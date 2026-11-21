package com.mhw.phone.models

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpeedDialTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun validOnlyWithNonBlankNumber() {
        assertTrue(SpeedDial(1, "123", "A").isValid())
        assertFalse(SpeedDial(1, "", "A").isValid())
        assertFalse(SpeedDial(1, "   ", "A").isValid())
    }

    @Test
    fun nameIsPlainDisplayNameWithoutTypeAndLabel() {
        assertEquals("Ali", SpeedDial(1, "1", "Ali").getName(context))
        assertEquals("Ali", SpeedDial(1, "1", "Ali", type = 2).getName(context))
        assertEquals("Ali", SpeedDial(1, "1", "Ali", label = "x").getName(context))
    }

    @Test
    fun nameAppendsTypeTextWhenBothPresent() {
        val name = SpeedDial(1, "1", "علي", type = 2, label = "").getName(context)
        assertTrue(name.startsWith("علي - "))
    }

    @Test
    fun jsonUsesPinnedKeysAndAcceptsReadableOnes() {
        val gson = Gson()
        assertEquals("{\"a\":1,\"b\":\"5\",\"c\":\"N\"}", gson.toJson(SpeedDial(1, "5", "N")))
        val readable = "{\"id\":1,\"number\":\"5\",\"displayName\":\"N\",\"type\":2,\"label\":\"L\"}"
        assertEquals(SpeedDial(1, "5", "N", 2, "L"), gson.fromJson(readable, SpeedDial::class.java))
    }
}
