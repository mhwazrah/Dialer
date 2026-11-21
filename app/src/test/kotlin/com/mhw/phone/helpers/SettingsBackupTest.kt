package com.mhw.phone.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.gson.reflect.TypeToken
import com.mhw.phone.models.CallerNote
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsBackupTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("backup-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun exportsOnlyAllowedKeysWithTheirStoredType() {
        prefs.edit()
            .putInt(APP_THEME, APP_THEME_DARK)
            .putBoolean(DIALPAD_BEEPS, true)
            .putString(SPEED_DIAL, "[]")
            .putString(CALLER_NOTES, "private note") // not a notes list, so not exported
            .putString("remember_sim_123", "x") // device specific
            .putString(APP_THEME + "_wrong", "x")
            .commit()

        val json = JSONObject(SettingsBackup.backup(prefs))

        assertEquals(setOf(APP_THEME, DIALPAD_BEEPS, SPEED_DIAL), json.keys().asSequence().toSet())
        assertEquals(APP_THEME_DARK, json.getInt(APP_THEME))
        assertTrue(json.getBoolean(DIALPAD_BEEPS))
    }

    @Test
    fun skipsAllowedKeyStoredWithAnUnexpectedType() {
        prefs.edit().putString(APP_THEME, "dark").commit()
        assertFalse(JSONObject(SettingsBackup.backup(prefs)).has(APP_THEME))
    }

    @Test
    fun restoreRoundTripsAndSkipsUnknownKeysAndWrongTypes() {
        val content = """{"$APP_THEME": 2, "$DIALPAD_BEEPS": true, "$CALL_VIBRATION": "yes", "$CALLER_NOTES": "hijack", "unknown": 1}"""

        SettingsBackup.restore(prefs, content)

        assertEquals(2, prefs.getInt(APP_THEME, -1))
        assertTrue(prefs.getBoolean(DIALPAD_BEEPS, false))
        assertFalse(prefs.contains(CALL_VIBRATION)) // wrong type
        assertFalse(prefs.contains(CALLER_NOTES)) // not a notes list
        assertFalse(prefs.contains("unknown"))
    }

    @Test(expected = JSONException::class)
    fun restoreRejectsNonJsonAndWritesNothing() {
        try {
            SettingsBackup.restore(prefs, "not json")
        } finally {
            assertTrue(prefs.all.isEmpty())
        }
    }

    private fun note(id: String, text: String, date: Long) = CallerNote(id, text, date)

    private fun storedNotes(): List<CallerNote> =
        gson.fromJson(prefs.getString(CALLER_NOTES, "[]"), object : TypeToken<List<CallerNote>>() {}.type)

    private fun backupWithNotes(vararg notes: CallerNote) = JSONObject().put(CALLER_NOTES, gson.toJson(notes.toList())).toString()

    @Test
    fun exportsReadableCallerNotesAndNotUnreadableOnes() {
        val raw = gson.toJson(listOf(note("+1 555", "dentist", 1)))
        prefs.edit().putString(CALLER_NOTES, raw).commit()
        assertEquals(raw, JSONObject(SettingsBackup.backup(prefs)).getString(CALLER_NOTES))

        prefs.edit().putString(CALLER_NOTES, "{broken").commit()
        assertFalse(JSONObject(SettingsBackup.backup(prefs)).has(CALLER_NOTES))
    }

    @Test
    fun importMergesNotesKeepingTheNewerPerNumber() {
        prefs.edit().putString(CALLER_NOTES, gson.toJson(listOf(note("+1 555", "old", 1), note("777", "phone newer", 9)))).commit()

        SettingsBackup.restore(prefs, backupWithNotes(note("1555", "new", 5), note("777", "file older", 2), note("888", "added", 3)))

        val byId = storedNotes().associate { it.id to it.note }
        assertEquals(mapOf("1555" to "new", "777" to "phone newer", "888" to "added"), byId)
    }

    @Test
    fun importIntoEmptyPhoneRoundTripsNotes() {
        val notes = listOf(note("555", "a", 1), note("666", "b", 2))
        prefs.edit().putString(CALLER_NOTES, gson.toJson(notes)).commit()
        val file = SettingsBackup.backup(prefs)
        prefs.edit().clear().commit()

        SettingsBackup.restore(prefs, file)

        assertEquals(notes, storedNotes())
    }

    @Test
    fun importNeverTouchesUnreadableNotesOnThePhone() {
        prefs.edit().putString(CALLER_NOTES, "{broken").commit()
        SettingsBackup.restore(prefs, backupWithNotes(note("555", "a", 1)))
        assertEquals("{broken", prefs.getString(CALLER_NOTES, null))
    }

    @Test
    fun importNeverRewritesPhoneNotesThatHaveAnIncompleteEntry() {
        val raw = """[{"a":"555","b":"kept","c":1},{"a":"666"}]"""
        prefs.edit().putString(CALLER_NOTES, raw).commit()
        SettingsBackup.restore(prefs, backupWithNotes(note("777", "new", 2)))
        assertEquals(raw, prefs.getString(CALLER_NOTES, null))
    }

    @Test
    fun importDropsInvalidNoteEntriesAndIgnoresNonListNotes() {
        SettingsBackup.restore(prefs, """{"$CALLER_NOTES": "[{\"a\":\"555\",\"b\":\"ok\",\"c\":1},{\"a\":\"666\"},null]"}""")
        assertEquals(listOf(note("555", "ok", 1)), storedNotes())

        SettingsBackup.restore(prefs, """{"$CALLER_NOTES": "not a list"}""")
        assertEquals(listOf(note("555", "ok", 1)), storedNotes())
    }
}
