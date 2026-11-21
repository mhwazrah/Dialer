package com.mhw.phone.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallLogItemTest {
    private val day = 86_400_000L

    @Test
    fun dateItemIdIsNegativeDayNumber() {
        assertEquals(-3, CallLogItem.Date(3 * day + 5, "x").getItemId())
        assertEquals(0, CallLogItem.Date(0, "x").getItemId())
    }

    @Test
    fun sameDayGivesSameId() {
        assertEquals(CallLogItem.Date(5 * day, "a").getItemId(), CallLogItem.Date(5 * day + day - 1, "b").getItemId())
        assertNotEquals(CallLogItem.Date(5 * day, "a").getItemId(), CallLogItem.Date(6 * day, "a").getItemId())
    }

    @Test
    fun recentCallItemIdIsItsId() {
        val c = RecentCall(
            id = 42, phoneNumber = "1", name = "", photoUri = "", startTS = 0L, duration = 0, type = 1, simID = 1,
            simColor = 0, specificNumber = "", specificType = "", isUnknownNumber = false, isVoiceMail = false,
        )
        assertEquals(42, c.getItemId())
    }
}
