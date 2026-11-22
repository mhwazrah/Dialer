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
    fun mixAddsUpAndIndicatorsAreRight() {
        val stats = callStats(
            listOf(
                StatCall("c1", "Sara", Calls.INCOMING_TYPE, seconds = 300, hour = 9, weekday = 6),
                StatCall("c1", "Sara", Calls.ANSWERED_EXTERNALLY_TYPE, seconds = 0, hour = 9, weekday = 6),
                StatCall("c2", "Omar", Calls.VOICEMAIL_TYPE, seconds = 0, hour = 10, weekday = 6), // counts as missed
                StatCall("c2", "Omar", Calls.REJECTED_TYPE, seconds = 0, hour = 10, weekday = 2),
                StatCall("c3", "Ali", Calls.OUTGOING_TYPE, seconds = 60, hour = 23, weekday = 1),
            )
        )
        assertEquals(stats.total, stats.incoming + stats.outgoing + stats.missed + stats.rejected)
        assertEquals(2, stats.incoming)
        assertEquals(1, stats.missed)
        assertEquals(1, stats.rejected)
        assertEquals(50, stats.answerRate) // 2 answered of 4 incoming
        assertEquals(300, stats.longestSeconds)
        assertEquals(300, stats.incomingSeconds)
        assertEquals(60, stats.outgoingSeconds)
        assertEquals(3, stats.people)
        assertEquals(6, stats.busiestWeekday) // Friday
        assertEquals(24, stats.byHour.size)
        assertEquals(3, stats.byWeekday[5])
        assertNull(callStats(listOf(StatCall("c3", "Ali", Calls.OUTGOING_TYPE, 60, 23))).answerRate) // no incoming calls
    }

    @Test
    fun seriesHaveZeroDaysAndMonthsAndOldestFirst() {
        val calls = listOf(
            StatCall("a", "A", Calls.INCOMING_TYPE, 10, 9, day = 100, month = 24_300),
            StatCall("a", "A", Calls.INCOMING_TYPE, 10, 9, day = 100, month = 24_300),
            StatCall("b", "B", Calls.OUTGOING_TYPE, 10, 9, day = 98, month = 24_298),
            StatCall("x", "X", Calls.BLOCKED_TYPE, 0, 9, day = 99, month = 24_299), // blocked: not charted
        )
        assertEquals(listOf(0, 1, 0, 2), dailySeries(calls, today = 100, days = 4))
        assertEquals(listOf(1, 0, 2), monthlySeries(calls, thisMonth = 24_300, months = 3))
    }

    @Test
    fun changeAgainstThePreviousPeriod() {
        assertEquals(50, percentChange(current = 150, previous = 100))
        assertEquals(-25, percentChange(current = 75, previous = 100))
        assertNull(percentChange(current = 10, previous = 0)) // nothing to compare against
    }

    @Test
    fun dashboardSplitsThePeriodFromTheOneBefore() {
        val today = java.time.LocalDate.ofEpochDay(100)
        val calls = listOf(
            StatCall("a", "A", Calls.INCOMING_TYPE, 120, 9, day = 100),
            StatCall("a", "A", Calls.INCOMING_TYPE, 60, 9, day = 94), // 7-day period: days 94..100
            StatCall("b", "B", Calls.OUTGOING_TYPE, 90, 9, day = 93), // previous period: days 87..93
        )
        val week = buildDashboard(calls, periodDays = 7, today = today)
        assertEquals(2, week.stats.total)
        assertEquals(1, week.previous?.total)
        assertEquals(7, week.series.size)
        assertEquals(2.0 / 7, week.callsPerDay, 0.001)
        val allTime = buildDashboard(calls, periodDays = 0, today = today)
        assertNull(allTime.previous)
        assertEquals(3, allTime.stats.total)
        assertEquals(3.0 / 8, allTime.callsPerDay, 0.001) // days 93..100
    }

    @Test
    fun reminderIdIsPerNumberWhateverTheFormat() {
        assertEquals(callBackReminderId("+966 50 123 4567"), callBackReminderId("0501234567"))
        assertTrue(callBackReminderId("0501234567") != callBackReminderId("0507654321"))
    }
}
