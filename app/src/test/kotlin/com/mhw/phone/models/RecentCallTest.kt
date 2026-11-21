package com.mhw.phone.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
class RecentCallTest {
    private fun call(
        number: String = "+1 (415) 555-2671",
        name: String = "",
        company: String = "",
        startTS: Long = 0L,
        simID: Int = 1,
    ) = RecentCall(
        id = 1, phoneNumber = number, name = name, company = company, photoUri = "", startTS = startTS,
        duration = 0, type = 1, simID = simID, simColor = 0, specificNumber = "", specificType = "",
        isUnknownNumber = false, isVoiceMail = false,
    )

    @Test
    fun dayCodeDerivedFromStart() {
        val ts = Calendar.getInstance().apply { clear(); set(2024, Calendar.MARCH, 5, 12, 0) }.timeInMillis
        assertEquals("2024-03-05", call(startTS = ts).dayCode)
    }

    @Test
    fun containsPhoneNumberByDigitsAndFormatting() {
        val c = call()
        assertTrue(c.doesContainPhoneNumber("4155552671"))
        assertTrue(c.doesContainPhoneNumber("555"))
    }

    @Test
    fun nonNumericOrMissingTextNeverMatches() {
        val c = call()
        assertFalse(c.doesContainPhoneNumber("john"))
        assertFalse(c.doesContainPhoneNumber(""))
        assertFalse(c.doesContainPhoneNumber("999"))
    }

    @Test
    fun businessCallNeedsNonEmptyCompanyInName() {
        assertTrue(call(name = "Acme Corp - Bob", company = "Acme Corp").isABusinessCall())
        assertFalse(call(name = "Bob", company = "Acme").isABusinessCall())
        assertFalse(call(name = "Bob", company = "").isABusinessCall())
    }

    @Test
    fun arabicCompanyMatches() {
        assertTrue(call(name = "شركة النور - علي", company = "شركة النور").isABusinessCall())
    }

    @Test
    fun groupedCallsDefaultsToNull() {
        assertNull(call().groupedCalls)
    }

    @Test
    fun lastUsedSimIsTheNewestCallThatRecordedOne() {
        val calls = listOf(call(startTS = 10, simID = 1), call(startTS = 30, simID = 2), call(startTS = 50, simID = -1))
        assertEquals(2, calls.lastUsedSimId()) // the newest (50) recorded no SIM
        assertEquals(null, listOf(call(simID = 0), call(simID = -1)).lastUsedSimId())
        assertEquals(null, emptyList<RecentCall>().lastUsedSimId())
    }
}
