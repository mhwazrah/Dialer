package com.goodwy.dialer.helpers

import android.content.Context
import android.telephony.PhoneNumberUtils
import com.goodwy.dialer.R
import com.google.gson.JsonParser

private typealias CharMap = HashMap<Char, Int>

/**
 * A helper class to map chars of various alphabets to numbers
 */
object DialpadT9 {

    private class T9Language {
        val letters = Array(8) {""}
        val charMap = CharMap()
    }

    private val languages = HashMap<String, T9Language>()

    @Volatile
    private var supportedSecondaryLanguages: List<String>? = null

    /**
     * Create a map for Latin (English) characters according to ITU E.161 and ISO/IEC 9995-8
     */
    private fun initLatinChars() {
        val lang = T9Language()
        val abc = 'A'..'Z'
        val num = '0'..'9'
        val abcNum = abc + num
        for (c in abcNum) {
            val number = PhoneNumberUtils.convertKeypadLettersToDigits(c.toString()).toInt()
            if(number in 2..9) {
                lang.letters[number - 2] += c.toString()
                lang.charMap[c] = number
            }
        }

        languages[LOCALE_EN] = lang
    }

    @Volatile
    var Initialized = false
        private set

    init {
        initLatinChars()
    }

    /**
     * Loads the extra languages once. Warmed up from App on a background thread; callers that need the
     * data right away block here until it is ready instead of reading the resource themselves.
     */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (Initialized) return
        context.resources.openRawResource(R.raw.t9languages).bufferedReader().use { readFromJson(it.readText()) }
    }

    /**
     * Read extra languages data from JSON
     */
    fun readFromJson(jsonText: String) {
        for (arrayElement in JsonParser.parseString(jsonText).asJsonArray) {
            val objectElement = arrayElement.asJsonObject
            val lang = objectElement["lang"].asString
            if (languages[lang] != null) {
                continue
            }
            val language = T9Language()
            for ((i, lettersElement) in objectElement["letters"].asJsonArray.withIndex()) {
                val letters = lettersElement.asString
                language.letters[i] = letters
                for (c in letters) {
                    language.charMap[c] = i + 2
                }
            }
            languages[lang] = language
        }

        supportedSecondaryLanguages = null
        Initialized = true
    }

    fun getLettersForNumber(number: Int, language: String): String {
        val lang = languages[language]
        return if (lang == null || number < 2 || number > 9) {
            ""
        } else {
            lang.letters[number - 2]
        }
    }

    fun convertLettersToNumbers(letters: String, language: String): String {
        val charMap = languages[language]?.charMap
        val charMapEn = languages[LOCALE_EN]?.charMap
        val res = StringBuilder(letters.length)

        for (c in letters) {
            val number = charMap?.get(c) ?: charMapEn?.get(c)
            if (number != null) res.append(number) else res.append(c)
        }

        return res.toString()
    }

    fun getSupportedSecondaryLanguages(): List<String> {
        return supportedSecondaryLanguages ?: languages.keys.filter { it != LOCALE_EN }.sorted()
            .also { supportedSecondaryLanguages = it }
    }
}
