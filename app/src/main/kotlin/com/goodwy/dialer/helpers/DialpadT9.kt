package com.goodwy.dialer.helpers

import android.content.Context
import com.goodwy.dialer.R
import com.google.gson.JsonParser
import java.text.Normalizer

/**
 * A helper class to map chars of various alphabets to numbers
 */
object DialpadT9 {

    private class T9Language(val letters: Array<String>, val charMap: Map<Char, Int>)

    /**
     * Immutable, published in one write. [fallback]: every char of every loaded language that sits on the same key
     * in all of them, so names in another script still match whatever secondary language is chosen.
     */
    private class State(val languages: Map<String, T9Language>, val fallback: Map<Char, Int>, val initialized: Boolean) {
        val latin = languages.getValue(LOCALE_EN).charMap
        val secondaryLanguages = languages.keys.filter { it != LOCALE_EN }.sorted()
    }

    // ITU E.161 / ISO/IEC 9995-8
    private val LATIN_LETTERS = arrayOf("ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")

    // Letters keyed like the letter they stand in for: ß as S, and Persian/Urdu letters common in Arabic-script names
    private val LETTER_ALIASES = mapOf(
        'ß' to 'S',
        'ک' to 'ك', 'گ' to 'ك', 'ی' to 'ي', 'ے' to 'ي', 'پ' to 'ب', 'چ' to 'ج',
        'ژ' to 'ز', 'ڤ' to 'ف', 'ڨ' to 'ق', 'ٱ' to 'ا', 'ھ' to 'ه', 'ہ' to 'ه', 'ە' to 'ه'
    )

    @Volatile
    private var state = State(mapOf(LOCALE_EN to language(LATIN_LETTERS)), emptyMap(), initialized = false)

    private fun language(letters: Array<String>): T9Language {
        val charMap = HashMap<Char, Int>()
        letters.forEachIndexed { i, group ->
            group.forEach { c -> if (!c.isWhitespace()) charMap[c] = i + 2 } // the labels separate letters with U+200A
        }
        return T9Language(letters, charMap)
    }

    /**
     * Loads the extra languages once. Warmed up from App on a background thread; callers that need the
     * data right away block here until it is ready instead of reading the resource themselves.
     */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (state.initialized) return
        context.resources.openRawResource(R.raw.t9languages).bufferedReader().use { readFromJson(it.readText()) }
    }

    /**
     * Read extra languages data from JSON
     */
    @Synchronized
    fun readFromJson(jsonText: String) {
        val languages = HashMap(state.languages)
        for (arrayElement in JsonParser.parseString(jsonText).asJsonArray) {
            val objectElement = arrayElement.asJsonObject
            val lang = objectElement["lang"].asString
            if (lang in languages) continue
            languages[lang] = language(objectElement["letters"].asJsonArray.map { it.asString }.toTypedArray())
        }
        state = State(languages, unambiguousChars(languages.values), initialized = true)
    }

    private fun unambiguousChars(languages: Collection<T9Language>): Map<Char, Int> {
        val keys = HashMap<Char, Int>()
        val ambiguous = HashSet<Char>()
        for (language in languages) {
            for ((c, key) in language.charMap) {
                val previous = keys.put(c, key)
                if (previous != null && previous != key) ambiguous.add(c)
            }
        }
        return keys - ambiguous
    }

    fun getLettersForNumber(number: Int, language: String): String {
        val lang = state.languages[language]
        return if (lang == null || number < 2 || number > 9) "" else lang.letters[number - 2]
    }

    /**
     * The dialpad key of [c] ('2'..'9', or the digit itself), or null when it has none. Looked up as typed first, so a
     * letter keeps the key its label shows (ؤ on 3, not on و's 7), then by its base letter (é → E, presentation forms).
     */
    fun digitFor(c: Char, language: String): Char? {
        if (c.isDigit()) return '0' + c.digitToInt()
        if (c.isWhitespace()) return null
        val s = state
        val selected = s.languages[language]?.charMap
        fun lookup(ch: Char): Int? = (selected?.get(ch) ?: s.latin[ch] ?: s.fallback[ch])
            ?: LETTER_ALIASES[ch]?.let { selected?.get(it) ?: s.fallback[it] }
        val upper = c.uppercaseChar()
        val key = lookup(upper) ?: baseChar(upper)?.let(::lookup) ?: return null
        return '0' + key
    }

    private fun baseChar(c: Char): Char? =
        Normalizer.normalize(c.toString(), Normalizer.Form.NFKD).firstOrNull()?.uppercaseChar()?.takeIf { it != c }

    /** [letters] with every char that has a key replaced by it; same length, so indexes line up with [letters]. */
    fun convertLettersToNumbers(letters: String, language: String): String {
        val res = StringBuilder(letters.length)
        for (c in letters) res.append(digitFor(c, language) ?: c)
        return res.toString()
    }

    fun getSupportedSecondaryLanguages(): List<String> = state.secondaryLanguages
}
