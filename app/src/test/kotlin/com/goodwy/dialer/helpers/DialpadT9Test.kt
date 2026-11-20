package com.goodwy.dialer.helpers

import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class DialpadT9Test {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadLanguages() {
            DialpadT9.readFromJson(File("src/main/res/raw/t9languages.json").readText())
        }
    }

    // The digits-only key the dialpad search matches against
    private fun keys(name: String, language: String) =
        DialpadT9.convertLettersToNumbers(name, language).filter { it.isDigit() }

    private fun assertKeys(language: String, vararg cases: Pair<String, String>) {
        for ((name, expected) in cases) assertEquals("$name ($language)", expected, keys(name, language))
    }

    @Test
    fun english() = assertKeys(
        LOCALE_EN,
        "Ahmed" to "24633",
        "john smith" to "564676484",
        "José" to "5673",
        "Straße" to "787273",
        "Ali 2" to "2542",
        "O'Neil" to "66345"
    )

    @Test
    fun arabicKeepsLabelledKeys() = assertKeys(
        "ar",
        "محمد" to "8685",
        "أحمد" to "3685",
        "إبراهيم" to "3253778",
        "آمنة" to "3872",
        "مؤمن" to "8387",
        "وائل" to "7338",
        "عائشة" to "93342",
        "فؤاد" to "8335",
        "فاطمة" to "83982",
        "مصطفى" to "84987",
        "عبد الله" to "9253887"
    )

    @Test
    fun arabicVariants() = assertKeys(
        "ar",
        "مُحَمَّد" to "8685", // tashkeel
        "محـمد" to "8685", // tatweel
        "ﻣﺤﻤﺪ" to "8685", // presentation forms
        "کریم" to "8578", // Persian kaf and yeh
        "٠٥٠" to "050" // Arabic-Indic digits
    )

    @Test
    fun bothScriptsWhateverTheSecondaryLanguage() {
        for (language in listOf(LOCALE_EN, LANGUAGE_SYSTEM, LANGUAGE_NONE, "ar", "ru")) {
            assertKeys(language, "محمد" to "8685", "Ahmed" to "24633", "Ahmed أحمد" to "246333685")
        }
    }

    @Test
    fun conversionKeepsIndexes() {
        for (name in listOf("José", "Straße", "مُحَمَّد", "ﻣﺤﻤﺪ", "عبد الله")) {
            assertEquals(name, name.length, DialpadT9.convertLettersToNumbers(name, "ar").length)
        }
    }

    @Test
    fun labelsHaveNoSeparatorKey() {
        assertEquals(null, DialpadT9.digitFor(' ', "ar"))
        assertEquals("ا أ إ آ ء ؤ ئ", DialpadT9.getLettersForNumber(3, "ar"))
        assertEquals(listOf("ar", "be", "el", "ru", "uk"), DialpadT9.getSupportedSecondaryLanguages())
    }
}
