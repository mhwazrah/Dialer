package com.mhw.phone.helpers

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject

/**
 * Settings export / import as a flat JSON object. Only the settings still shown on the Settings screens travel
 * ([allowed], with their stored type); pinned settings, device ids, caches and notes never leave or enter.
 */
object SettingsBackup {
    private enum class Type { BOOLEAN, INT, STRING }

    // Keep in sync with the rows left on the Settings / Dialpad preferences screens
    private val allowed = mapOf(
        APP_THEME to Type.INT,
        "color_sim_icons" to Type.BOOLEAN,
        "sim_icons_colors" to Type.STRING,
        "date_format" to Type.STRING,
        "use_24_hour_format" to Type.BOOLEAN,
        "default_tab" to Type.INT,
        SWIPE_RIGHT_ACTION to Type.INT,
        SWIPE_LEFT_ACTION to Type.INT,
        "flash_for_alerts" to Type.BOOLEAN,
        SHOW_INCOMING_CALLS_FULL_SCREEN to Type.BOOLEAN,
        QUICK_ANSWERS to Type.STRING,
        DISABLE_PROXIMITY_SENSOR to Type.BOOLEAN,
        CALL_VIBRATION to Type.BOOLEAN,
        DIALPAD_VIBRATION to Type.BOOLEAN,
        CALL_START_END_VIBRATION to Type.BOOLEAN,
        DIALPAD_BEEPS to Type.BOOLEAN,
        "show_call_confirmation" to Type.BOOLEAN,
        CALL_USING_SAME_SIM to Type.BOOLEAN,
        "start_name_with_surname" to Type.BOOLEAN,
        DIALPAD_SECONDARY_LANGUAGE to Type.STRING,
        SPEED_DIAL to Type.STRING // JSON list of speed dial entries
    )

    fun backup(prefs: SharedPreferences): String {
        val json = JSONObject()
        for ((key, value) in prefs.all) {
            if (value != null && allowed[key]?.matches(value) == true) json.put(key, value)
        }
        return json.toString(2)
    }

    /**
     * Writes the allowed entries of [content] into [prefs]; unknown keys and wrong types are skipped.
     * Throws [JSONException] when [content] is not a JSON object; nothing is written then.
     */
    fun restore(prefs: SharedPreferences, content: String) {
        val json = JSONObject(content)
        prefs.edit {
            for (key in json.keys()) {
                val value = json.get(key)
                when (allowed[key]?.takeIf { it.matches(value) }) {
                    Type.BOOLEAN -> putBoolean(key, value as Boolean)
                    Type.INT -> putInt(key, value as Int)
                    Type.STRING -> putString(key, value as String)
                    null -> Unit
                }
            }
        }
    }

    private fun Type.matches(value: Any) = when (this) {
        Type.BOOLEAN -> value is Boolean
        Type.INT -> value is Int
        Type.STRING -> value is String
    }
}
