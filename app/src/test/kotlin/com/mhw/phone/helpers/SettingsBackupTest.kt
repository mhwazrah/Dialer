package com.mhw.phone.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
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
            .putString(CALLER_NOTES, "private note") // data, not a setting
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
        assertFalse(prefs.contains(CALLER_NOTES)) // not a setting
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
}
