package com.mhw.phone.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mhw.phone.extensions.config
import com.mhw.phone.models.CallerNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallerNotesHelperTest {
    private lateinit var context: Context
    private lateinit var helper: CallerNotesHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.config.callerNotes = ""
        context.config.callerNotesBackup = ""
        helper = CallerNotesHelper(context)
    }

    @Test
    fun noNotesInitially() {
        assertNull(helper.getCallerNotes("123"))
        assertNull(helper.getCallerNotes(null))
    }

    @Test
    fun addThenGetMatchesIgnoringFormatting() {
        helper.addCallerNotes("+1 (234) 567-890", "hello", null)
        assertEquals("hello", helper.getCallerNotes("1234567890")?.note)
        assertEquals("hello", helper.getCallerNotes("+1-234-567-890")?.note)
    }

    @Test
    fun addingSameNumberReplacesNote() {
        helper.addCallerNotes("555", "one", null)
        helper.addCallerNotes("5 5 5", "two", null)
        assertEquals("two", helper.getCallerNotes("555")?.note)
        assertEquals(1, gson.fromJson(context.config.callerNotes, Array<CallerNote>::class.java).size)
    }

    @Test
    fun editingUnderNewNumberRemovesStaleEntry() {
        helper.addCallerNotes("111", "x", null)
        val old = helper.getCallerNotes("111")
        helper.addCallerNotes("222", "x", old)
        assertNull(helper.getCallerNotes("111"))
        assertNotNull(helper.getCallerNotes("222"))
    }

    @Test
    fun callbackInvoked() {
        var called = 0
        helper.addCallerNotes("1", "n", null) { called++ }
        helper.deleteCallerNotes(null) { called++ }
        assertEquals(2, called)
    }

    @Test
    fun deleteRemovesNoteAndPersists() {
        helper.addCallerNotes("111", "a", null)
        helper.addCallerNotes("222", "b", null)
        helper.deleteCallerNotes(helper.getCallerNotes("111"))
        assertNull(helper.getCallerNotes("111"))
        assertEquals("b", CallerNotesHelper(context).getCallerNotes("222")?.note)
    }

    @Test
    fun deleteForNumber() {
        helper.addCallerNotes("111", "a", null)
        helper.deleteCallerNotesForNumber("222")
        assertNotNull(helper.getCallerNotes("111")) // different number: untouched
        helper.deleteCallerNotesForNumber("1 11")
        assertNull(helper.getCallerNotes("111"))
    }

    @Test
    fun arabicNoteRoundTrips() {
        helper.addCallerNotes("999", "ملاحظة مهمة", null)
        assertEquals("ملاحظة مهمة", CallerNotesHelper(context).getCallerNotes("999")?.note)
    }

    @Test
    fun picksUpPrefChangedElsewhere() {
        helper.addCallerNotes("111", "a", null)
        context.config.callerNotes = gson.toJson(listOf(CallerNote("333", "imported", 1L)))
        assertNull(helper.getCallerNotes("111"))
        assertEquals("imported", helper.getCallerNotes("333")?.note)
    }

    @Test
    fun unreadableNotesAreBackedUpAndNeverOverwritten() {
        val garbage = "{this is not a list"
        context.config.callerNotes = garbage

        assertNull(helper.getCallerNotes("111"))
        assertEquals(garbage, context.config.callerNotesBackup)

        helper.addCallerNotes("111", "new", null)
        assertEquals(garbage, context.config.callerNotes)
    }

    @Test
    fun getInstanceIsSingleton() {
        assertTrue(CallerNotesHelper.getInstance(context) === CallerNotesHelper.getInstance(context))
    }
}
