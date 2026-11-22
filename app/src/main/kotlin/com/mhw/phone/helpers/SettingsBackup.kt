package com.mhw.phone.helpers

import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.reflect.TypeToken
import com.mhw.phone.extensions.numberForNotes
import com.mhw.phone.models.CallerNote
import org.json.JSONException
import org.json.JSONObject

/**
 * Settings export / import as a flat JSON object. Only the settings still shown on the Settings screens travel
 * ([allowed], with their stored type); pinned settings, device ids and caches never leave or enter.
 * Caller notes travel too, but are merged on import instead of replacing the notes already on the phone.
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
        FLIP_TO_SILENCE to Type.BOOLEAN,
        ANNOUNCE_CALLER_NAME to Type.BOOLEAN,
        ANNOUNCE_ONLY_WITH_HEADSET to Type.BOOLEAN,
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
        // Only notes that can be read: an unreadable store stays on the phone untouched
        prefs.getString(CALLER_NOTES, null)?.let { raw -> parseNotes(raw, strict = false)?.let { json.put(CALLER_NOTES, raw) } }
        return json.toString(2)
    }

    /**
     * Writes the allowed entries of [content] into [prefs]; unknown keys and wrong types are skipped.
     * Throws [JSONException] when [content] is not a JSON object; nothing is written then.
     */
    fun restore(prefs: SharedPreferences, content: String) {
        val json = JSONObject(content)
        val mergedNotes = (json.opt(CALLER_NOTES) as? String)?.let { mergeNotes(prefs.getString(CALLER_NOTES, null), it) }
        prefs.edit {
            mergedNotes?.let { putString(CALLER_NOTES, it) }
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

    /**
     * The notes on the phone plus the imported ones, one per number; when both have a note for a number the newer wins.
     * Null (nothing written) when either side cannot be read, so no note is ever lost.
     */
    private fun mergeNotes(existingRaw: String?, importedRaw: String): String? {
        val existing = if (existingRaw.isNullOrBlank()) emptyList() else parseNotes(existingRaw, strict = true)
        val imported = parseNotes(importedRaw, strict = false)
        if (existing == null || imported == null) return null
        val merged = LinkedHashMap<String, CallerNote>()
        for (note in existing + imported) {
            val key = note.id.numberForNotes()
            val kept = merged[key]
            if (kept == null || note.creationDate > kept.creationDate) merged[key] = note
        }
        return gson.toJson(merged.values.toList())
    }

    /**
     * Null when [raw] is not a notes list. An entry missing its number or text (a hand-edited file) is dropped, or,
     * when [strict] (the phone's own notes, which are rewritten), makes the whole list unreadable so it is left alone.
     */
    private fun parseNotes(raw: String, strict: Boolean): List<CallerNote>? = try {
        val type = object : TypeToken<List<CallerNote?>>() {}.type
        val notes = gson.fromJson<List<CallerNote?>>(raw, type) ?: return null
        // Gson can leave a non-null Kotlin field null when the JSON lacks it
        @Suppress("SENSELESS_COMPARISON")
        val valid = notes.filter { n -> n != null && n.id != null && n.note != null }
        if (strict && valid.size != notes.size) null else valid.map { it!! }
    } catch (_: Exception) {
        null
    }

    private fun Type.matches(value: Any) = when (this) {
        Type.BOOLEAN -> value is Boolean
        Type.INT -> value is Int
        Type.STRING -> value is String
    }
}
