package com.mhw.phone.helpers

import android.provider.CallLog.Calls
import com.mhw.phone.models.RecentCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// RecentCall works out its day code with Android time classes
@RunWith(RobolectricTestRunner::class)
class BlockedCallsAndStatsTest {
    private val records = listOf(
        BlockedCall("+966 50 123 4567", time = 100_000, reason = BLOCK_REASON_UNKNOWN),
        BlockedCall("", time = 500_000, reason = BLOCK_REASON_HIDDEN),
    )

    private fun call(number: String, time: Long, type: Int = Calls.MISSED_TYPE, simID: Int = 1, systemReason: Int = 0) = RecentCall(
        id = 1, phoneNumber = number, name = number, photoUri = "", startTS = time, duration = 0, type = type, simID = simID,
        simColor = 0, specificNumber = "", specificType = "", isUnknownNumber = false, isVoiceMail = false, blockReason = systemReason
    )

    @Test
    fun reasonMatchesTheSameNumberInAnyFormatWithinAMinute() {
        assertEquals(BLOCK_REASON_UNKNOWN, findBlockReason(records, "0501234567", 130_000))
        assertNull(findBlockReason(records, "0501234567", 200_000)) // more than a minute apart
        assertNull(findBlockReason(records, "0509999999", 100_000))
        assertEquals(BLOCK_REASON_HIDDEN, findBlockReason(records, "-2", 510_000)) // hidden numbers: "", "-1", "-2"
    }

    @Test
    fun withoutOurRecordTheSystemReasonIsUsed() {
        assertEquals(BLOCK_REASON_LIST, blockReasonOf(emptyList(), "0501234567", 0, Calls.BLOCK_REASON_BLOCKED_NUMBER))
        assertEquals(BLOCK_REASON_SYSTEM, blockReasonOf(emptyList(), "0501234567", 0, Calls.BLOCK_REASON_CALL_SCREENING_SERVICE))
        assertNull(blockReasonOf(emptyList(), "0501234567", 0, 0))
    }

    @Test
    fun blockedFilterKeepsBlockedAndSilencedCallsOnly() {
        assertTrue(call("0501234567", 120_000).matchesRecentsFilter(FILTER_RECENT_CALLS_BLOCKED, records)) // silenced: our record
        assertTrue(call("0507777777", 0, type = Calls.BLOCKED_TYPE).matchesRecentsFilter(FILTER_RECENT_CALLS_BLOCKED, records))
        assertFalse(call("0507777777", 0).matchesRecentsFilter(FILTER_RECENT_CALLS_BLOCKED, records))
    }

    @Test
    fun simFilterKeepsThatSimsCalls() {
        assertTrue(call("1", 0, simID = 2).matchesRecentsFilter(FILTER_RECENT_CALLS_SIM_BASE + 2, emptyList()))
        assertFalse(call("1", 0, simID = 1).matchesRecentsFilter(FILTER_RECENT_CALLS_SIM_BASE + 2, emptyList()))
        assertTrue(call("1", 0, type = Calls.MISSED_TYPE).matchesRecentsFilter(Calls.MISSED_TYPE, emptyList()))
    }

    @Test
    fun statsCountTalkTimeTopPeopleAndBusiestHour() {
        val stats = callStats(
            listOf(
                StatCall("c1", "Sara", Calls.OUTGOING_TYPE, seconds = 120, hour = 18),
                StatCall("c1", "Sara", Calls.INCOMING_TYPE, seconds = 60, hour = 18),
                StatCall("n123", "0501234567", Calls.MISSED_TYPE, seconds = 0, hour = 9),
                StatCall("c2", "Omar", Calls.OUTGOING_TYPE, seconds = 0, hour = 18), // not answered: no talk time
                StatCall("n999", "Spam", Calls.BLOCKED_TYPE, seconds = 0, hour = 3), // blocked: left out
            )
        )
        assertEquals(4, stats.total)
        assertEquals(1, stats.incoming)
        assertEquals(2, stats.outgoing)
        assertEquals(1, stats.missed)
        assertEquals(180, stats.talkSeconds)
        assertEquals(90, stats.averageSeconds)
        assertEquals(TopCaller("Sara", calls = 2, seconds = 180), stats.topCallers.first())
        assertEquals(3, stats.topCallers.size)
        assertEquals(18, stats.busiestHour)
    }

    @Test
    fun noCallsMeansZerosAndNoBusiestHour() {
        val stats = callStats(emptyList())
        assertEquals(0, stats.total)
        assertEquals(0, stats.averageSeconds)
        assertNull(stats.busiestHour)
    }

    @Test
    fun reminderIdIsPerNumberWhateverTheFormat() {
        assertEquals(callBackReminderId("+966 50 123 4567"), callBackReminderId("0501234567"))
        assertTrue(callBackReminderId("0501234567") != callBackReminderId("0507654321"))
    }
}
