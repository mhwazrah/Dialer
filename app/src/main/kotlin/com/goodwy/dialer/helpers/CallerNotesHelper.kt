package com.goodwy.dialer.helpers

import android.content.Context
import com.goodwy.dialer.extensions.config
import com.goodwy.dialer.extensions.numberForNotes
import com.goodwy.dialer.models.CallerNote
import com.google.gson.reflect.TypeToken
import java.util.Calendar
import java.util.Locale

/**
 * Notes live in one JSON pref string. The parsed list is cached against the raw string it came from,
 * so reads never re-parse and a pref changed elsewhere (e.g. settings import) is picked up.
 * Notes are only ever removed explicitly: never pruned against the call log.
 */
class CallerNotesHelper(val context: Context) {

    private var cachedRaw: String? = null
    private var cachedNotes: List<CallerNote> = emptyList()
    private var unreadable = false

    @Synchronized
    private fun readNotes(): List<CallerNote> {
        val raw = context.config.callerNotes
        if (raw == cachedRaw) return cachedNotes
        val type = object : TypeToken<List<CallerNote>>() {}.type
        val parsed = try {
            gson.fromJson<List<CallerNote>>(raw, type)?.filterNotNull() ?: emptyList()
        } catch (_: Exception) {
            // Unreadable (e.g. written by a different app version): keep the raw text so nothing is lost, and refuse
            // to write until it can be read; not cached, so every access retries.
            if (raw.isNotBlank()) context.config.callerNotesBackup = raw
            unreadable = raw.isNotBlank()
            return emptyList()
        }
        unreadable = false
        cachedNotes = parsed
        cachedRaw = raw
        return cachedNotes
    }

    @Synchronized
    private fun writeNotes(notes: List<CallerNote>) {
        if (unreadable) return // never overwrite notes we could not read
        val raw = gson.toJson(notes)
        context.config.callerNotes = raw
        cachedRaw = raw
        cachedNotes = notes
    }

    @Synchronized
    fun addCallerNotes(number: String, note: String, callerNote: CallerNote?, callback: () -> Unit = {}) {
        val key = number.numberForNotes()
        val date = Calendar.getInstance(Locale.ENGLISH).timeInMillis
        val staleKey = callerNote?.id?.numberForNotes()
        writeNotes(readNotes().filter { it.id.numberForNotes().let { id -> id != key && id != staleKey } } +
            CallerNote(key, note, date))
        callback.invoke()
    }

    @Synchronized
    fun deleteCallerNotes(callerNote: CallerNote?, callback: () -> Unit = {}) {
        if (callerNote != null) {
            val key = callerNote.id.numberForNotes()
            writeNotes(readNotes().filter { it.id.numberForNotes() != key })
        }
        callback.invoke()
    }

    @Synchronized
    fun deleteCallerNotesForNumber(number: String) {
        val key = number.numberForNotes()
        val notes = readNotes()
        if (notes.any { it.id.numberForNotes() == key }) writeNotes(notes.filter { it.id.numberForNotes() != key })
    }

    @Synchronized
    fun getCallerNotes(number: String?): CallerNote? {
        val key = number?.numberForNotes() ?: return null
        return readNotes().firstOrNull { it.id.numberForNotes() == key }
    }

    companion object {
        @Volatile
        private var instance: CallerNotesHelper? = null

        fun getInstance(context: Context): CallerNotesHelper =
            instance ?: synchronized(this) { instance ?: CallerNotesHelper(context).also { instance = it } }
    }
}
