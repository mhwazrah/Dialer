package com.mhw.phone.helpers

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.CallLog.Calls
import androidx.test.core.app.ApplicationProvider
import com.mhw.phone.models.RecentCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the logic that needs no call-log provider: number matching, grouping, the missed-call key and the
 * cached-name diff. Not covered (they query/write the call-log ContentProvider, contacts or telecom):
 * getRecentCalls, getRecentCallsOfNumber, getRecents, loggedNumbers, removeRecentCalls, removeAllRecentCalls,
 * restoreRecentCalls, loadCallKeys, getNewMissedCallCounts, markMissedCallsAsRead, applyCachedInfoUpdates,
 * loadVoiceMailNumbers, updateRecentOutgoingNumbers (config-backed only).
 */
@RunWith(RobolectricTestRunner::class)
class RecentsHelperTest {
    private lateinit var helper: RecentsHelper

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val NOON = 1_700_000_000_000L // a fixed instant; +/- a few hours stays on the same day only by chance, so days are offset by DAY
    }

    @Before
    fun setUp() {
        helper = RecentsHelper(ApplicationProvider.getApplicationContext<Context>())
    }

    // ---- isSameNumber

    @Test
    fun identicalStringsMatch() {
        assertTrue(RecentsHelper.isSameNumber("0501234567", "0501234567"))
        assertTrue(RecentsHelper.isSameNumber("abc", "abc"))
    }

    @Test
    fun blankNeverMatchesANumber() {
        assertFalse(RecentsHelper.isSameNumber("", "0501234567"))
        assertFalse(RecentsHelper.isSameNumber("0501234567", "  "))
        assertFalse(RecentsHelper.isSameNumber("", " "))
    }

    @Test
    fun blankMatchesItself() {
        assertTrue(RecentsHelper.isSameNumber("", ""))
    }

    @Test
    fun formattingDoesNotMatter() {
        assertTrue(RecentsHelper.isSameNumber("050-123-4567", "0501234567"))
        assertTrue(RecentsHelper.isSameNumber("+966 50 123 4567", "+966501234567"))
    }

    @Test
    fun nationalAndInternationalFormsMatch() {
        assertTrue(RecentsHelper.isSameNumber("0501234567", "+966501234567"))
        assertTrue(RecentsHelper.isSameNumber("+966501234567", "0501234567"))
    }

    @Test
    fun differentNumbersDoNotMatch() {
        assertFalse(RecentsHelper.isSameNumber("0501234567", "0501234568"))
        assertFalse(RecentsHelper.isSameNumber("+966501234567", "+966501234568"))
    }

    @Test
    fun numberMerelyEndingInTheOtherDoesNotMatch() {
        // PhoneNumberUtils.compare alone would accept these (last 7 digits)
        assertFalse(RecentsHelper.isSameNumber("00000111", "0500000111"))
        assertFalse(RecentsHelper.isSameNumber("1234567", "0501234567"))
    }

    // ---- grouping (private; driven by reflection)

    private fun call(
        id: Int,
        number: String = "0501234567",
        name: String = number,
        ts: Long = NOON,
        sim: Int = 1
    ) = RecentCall(
        id = id, phoneNumber = number, name = name, photoUri = "", startTS = ts, duration = 0, type = Calls.INCOMING_TYPE,
        simID = sim, simColor = 0, specificNumber = "", specificType = "", isUnknownNumber = false, isVoiceMail = false
    )

    private fun shouldGroup(a: RecentCall, b: RecentCall): Boolean {
        val m = RecentsHelper::class.java.getDeclaredMethod("shouldGroupCalls", RecentCall::class.java, RecentCall::class.java)
        m.isAccessible = true
        return m.invoke(helper, a, b) as Boolean
    }

    @Suppress("UNCHECKED_CAST")
    private fun group(calls: List<RecentCall>): List<RecentCall> {
        val m = RecentsHelper::class.java.getDeclaredMethod("groupSubsequentCalls", List::class.java)
        m.isAccessible = true
        return m.invoke(helper, calls) as List<RecentCall>
    }

    @Test
    fun sameNumberSameDaySameSimGroups() {
        assertTrue(shouldGroup(call(1), call(2, ts = NOON + 1000)))
    }

    @Test
    fun differentSimDoesNotGroup() {
        assertFalse(shouldGroup(call(1, sim = 1), call(2, sim = 2)))
    }

    @Test
    fun differentDayDoesNotGroup() {
        assertFalse(shouldGroup(call(1, ts = NOON), call(2, ts = NOON + 2 * DAY)))
    }

    @Test
    fun differentRealNamesDoNotGroupEvenForTheSameNumber() {
        assertFalse(shouldGroup(call(1, name = "Ali"), call(2, name = "Omar")))
    }

    @Test
    fun sameNameGroups() {
        assertTrue(shouldGroup(call(1, name = "Ali"), call(2, name = "Ali")))
    }

    @Test
    fun anUnnamedRowGroupsWithANamedRowOfTheSameNumber() {
        // name == number means "no name yet", so it is not a real different name
        assertTrue(shouldGroup(call(1, name = "Ali"), call(2)))
        assertTrue(shouldGroup(call(1), call(2, name = "Ali")))
    }

    @Test
    fun differentNumbersDoNotGroup() {
        assertFalse(shouldGroup(call(1, number = "0501234567"), call(2, number = "0501234568")))
    }

    @Test
    fun groupingEmptyListIsEmpty() {
        assertTrue(group(emptyList()).isEmpty())
    }

    @Test
    fun groupingSingleCallLeavesItUngrouped() {
        val result = group(listOf(call(1)))
        assertEquals(1, result.size)
        assertNull(result[0].groupedCalls)
    }

    @Test
    fun consecutiveCallsOfOneNumberCollapseIntoTheFirst() {
        val a = call(1, ts = NOON + 3000)
        val b = call(2, ts = NOON + 2000)
        val c = call(3, ts = NOON + 1000)
        val result = group(listOf(a, b, c))
        assertEquals(1, result.size)
        assertEquals(1, result[0].id)
        assertEquals(listOf(1, 2, 3), result[0].groupedCalls!!.map { it.id })
    }

    @Test
    fun interleavedNumbersStaySeparateGroups() {
        val result = group(
            listOf(
                call(1, number = "0501111111"),
                call(2, number = "0502222222"),
                call(3, number = "0501111111")
            )
        )
        assertEquals(listOf(1, 2, 3), result.map { it.id })
        assertTrue(result.all { it.groupedCalls == null })
    }

    @Test
    fun groupBreaksWhenTheRunEndsThenStartsANewOne() {
        val result = group(
            listOf(
                call(1, number = "0501111111"),
                call(2, number = "0501111111"),
                call(3, number = "0502222222"),
                call(4, number = "0502222222"),
                call(5, number = "0502222222")
            )
        )
        assertEquals(listOf(1, 3), result.map { it.id })
        assertEquals(listOf(1, 2), result[0].groupedCalls!!.map { it.id })
        assertEquals(listOf(3, 4, 5), result[1].groupedCalls!!.map { it.id })
    }

    @Test
    fun nationalAndInternationalFormsOfOneNumberGroup() {
        val result = group(listOf(call(1, number = "0501234567"), call(2, number = "+966501234567")))
        assertEquals(1, result.size)
        assertEquals(2, result[0].groupedCalls!!.size)
    }

    @Test
    fun groupingDoesNotMutateTheInputCalls() {
        val a = call(1)
        val b = call(2)
        group(listOf(a, b))
        assertNull(a.groupedCalls)
        assertNull(b.groupedCalls)
    }

    // ---- missedCallKey

    private fun missedKey(number: String?): String {
        val m = RecentsHelper::class.java.getDeclaredMethod("missedCallKey", String::class.java)
        m.isAccessible = true
        return m.invoke(helper, number) as String
    }

    @Test
    fun missedCallKeyCollapsesUnknownNumbers() {
        assertEquals("", missedKey(null))
        assertEquals("", missedKey(""))
        assertEquals("", missedKey("   "))
        assertEquals("", missedKey("-1"))
        assertEquals("0501234567", missedKey("0501234567"))
    }

    // ---- buildCachedInfoUpdate (null = the log already holds the same values)

    private fun cachedUpdate(
        number: String = "0501234567",
        countryIso: String? = "SA",
        name: String,
        photoUri: String? = null,
        logName: String?,
        logPhoto: String? = null
    ): ContentProviderOperation? {
        val m = RecentsHelper::class.java.getDeclaredMethod(
            "buildCachedInfoUpdate", String::class.java, String::class.java, String::class.java,
            String::class.java, String::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(helper, number, countryIso, name, photoUri, logName, logPhoto) as ContentProviderOperation?
    }

    @Test
    fun noUpdateWhenLogAlreadyHoldsTheName() {
        assertNull(cachedUpdate(name = "Ali", logName = "Ali"))
    }

    @Test
    fun updateWhenNameDiffers() {
        val op = cachedUpdate(name = "Ali", logName = "Old")
        assertNotNull(op)
        assertTrue(op!!.isUpdate)
    }

    @Test
    fun updateWhenLogHasNoName() {
        assertNotNull(cachedUpdate(name = "Ali", logName = null))
        assertNotNull(cachedUpdate(name = "Ali", logName = ""))
        assertNotNull(cachedUpdate(name = "Ali", logName = "-1"))
    }

    @Test
    fun aNameEqualToTheNumberCountsAsNoName() {
        // the number shown in place of a name is not written to the log
        assertNull(cachedUpdate(name = "0501234567", logName = null))
        assertNull(cachedUpdate(name = "0501234567", logName = "-1"))
        assertNotNull(cachedUpdate(name = "0501234567", logName = "Stale name"))
    }

    @Test
    fun nullCountryIsoStillBuildsAnUpdate() {
        assertNotNull(cachedUpdate(countryIso = null, name = "Ali", logName = null))
    }

    @Test
    fun photoOnlyChangeAlsoUpdates() {
        val contactPhoto = "content://com.android.contacts/contacts/1/photo"
        assertNotNull(cachedUpdate(name = "Ali", photoUri = contactPhoto, logName = "Ali", logPhoto = null))
        assertNull(cachedUpdate(name = "Ali", photoUri = contactPhoto, logName = "Ali", logPhoto = contactPhoto))
    }

    @Test
    fun nonContactPhotoIsTreatedAsNoPhoto() {
        assertNull(cachedUpdate(name = "Ali", photoUri = "file:///sdcard/x.jpg", logName = "Ali", logPhoto = null))
    }
}
