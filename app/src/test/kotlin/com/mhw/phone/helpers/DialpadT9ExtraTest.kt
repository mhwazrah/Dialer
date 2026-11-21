package com.mhw.phone.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Gaps left by [DialpadT9Test]: digitFor edge cases, label lookups, non-Arabic scripts, readFromJson re-reads. */
class DialpadT9ExtraTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadLanguages() {
            DialpadT9.readFromJson(File("src/main/res/raw/t9languages.json").readText())
        }
    }

    @Test
    fun digitsMapToThemselvesIncludingNonAsciiDigits() {
        assertEquals('0', DialpadT9.digitFor('0', LOCALE_EN))
        assertEquals('7', DialpadT9.digitFor('7', "ar"))
        assertEquals('3', DialpadT9.digitFor('٣', LOCALE_EN)) // Arabic-Indic three
        assertEquals('5', DialpadT9.digitFor('۵', LOCALE_EN)) // Persian five
    }

    @Test
    fun separatorsPunctuationAndEmojiHaveNoKey() {
        for (c in listOf(' ', '\t', '-', '\'', '+', '#', '*', '.', '\uD83D')) assertNull("U+%04X".format(c.code), DialpadT9.digitFor(c, LOCALE_EN))
    }

    @Test
    fun convertKeepsUnmappedCharsInPlace() {
        assertEquals("2-3 +4", DialpadT9.convertLettersToNumbers("A-D +G", LOCALE_EN))
        assertEquals("", DialpadT9.convertLettersToNumbers("", LOCALE_EN))
        assertEquals("😀", DialpadT9.convertLettersToNumbers("😀", LOCALE_EN))
    }

    @Test
    fun latinIsCaseInsensitiveOnAllKeys() {
        val expected = "222" + "333" + "444" + "555" + "666" + "7777" + "888" + "9999"
        assertEquals(expected, DialpadT9.convertLettersToNumbers("abcdefghijklmnopqrstuvwxyz", LOCALE_EN))
        assertEquals(expected, DialpadT9.convertLettersToNumbers("ABCDEFGHIJKLMNOPQRSTUVWXYZ", LOCALE_EN))
    }

    @Test
    fun accentedLatinFallsBackToItsBaseLetter() {
        assertEquals('6', DialpadT9.digitFor('Ñ', LOCALE_EN)) // N with tilde
        assertEquals('2', DialpadT9.digitFor('å', LOCALE_EN)) // a with ring
        assertEquals('2', DialpadT9.digitFor('À', "ru")) // A grave, with a non-Latin secondary language
    }

    @Test
    fun cyrillicNameKeysSameWhateverTheSecondaryLanguage() {
        // Cyrillic letters sit on the same key in ru/uk/be, so they are searchable even when another language is chosen
        val ivan = "Иван"
        for (language in listOf("ru", "uk", "be", LOCALE_EN, LANGUAGE_NONE)) {
            assertEquals(language, "4225", DialpadT9.convertLettersToNumbers(ivan, language))
        }
    }

    @Test
    fun languageSpecificLettersUseTheirLanguageKey() {
        assertEquals('2', DialpadT9.digitFor('ґ', "uk")) // Ukrainian ghe with upturn
        assertEquals('3', DialpadT9.digitFor('є', "uk")) // Ukrainian ye
        assertEquals('6', DialpadT9.digitFor('ў', "be")) // Belarusian short u
    }

    @Test
    fun greekLettersIncludingFinalSigma() {
        assertEquals('7', DialpadT9.digitFor('ς', "el")) // final sigma uppercases to sigma
        assertEquals('2', DialpadT9.digitFor('Α', "el"))
    }

    @Test
    fun persianAndUrduLettersAliasToArabicOnes() {
        assertEquals(DialpadT9.digitFor('ك', "ar"), DialpadT9.digitFor('ک', "ar")) // keheh -> kaf
        assertEquals(DialpadT9.digitFor('ي', "ar"), DialpadT9.digitFor('ی', "ar")) // farsi yeh -> yeh
        assertEquals(DialpadT9.digitFor('ب', "ar"), DialpadT9.digitFor('پ', "ar")) // peh -> beh
        assertEquals(DialpadT9.digitFor('S', "ar"), DialpadT9.digitFor('ß', "ar")) // sharp s -> S
    }

    @Test
    fun arabicLetterKeepsItsLabelledKeyOverItsBaseLetter() {
        assertEquals('3', DialpadT9.digitFor('ؤ', "ar")) // hamza on waw stays on 3, not waw's 7
        assertEquals('7', DialpadT9.digitFor('و', "ar"))
    }

    @Test
    fun lettersForNumberBounds() {
        assertEquals("ABC", DialpadT9.getLettersForNumber(2, LOCALE_EN))
        assertEquals("WXYZ", DialpadT9.getLettersForNumber(9, LOCALE_EN))
        assertEquals("", DialpadT9.getLettersForNumber(1, LOCALE_EN))
        assertEquals("", DialpadT9.getLettersForNumber(0, LOCALE_EN))
        assertEquals("", DialpadT9.getLettersForNumber(10, LOCALE_EN))
        assertEquals("", DialpadT9.getLettersForNumber(5, "xx"))
        assertEquals("", DialpadT9.getLettersForNumber(5, LANGUAGE_NONE))
        assertEquals("АБВГ", DialpadT9.getLettersForNumber(2, "ru"))
    }

    @Test
    fun unknownLanguageStillConvertsLatinAndFallbackScripts() {
        assertEquals("24633", DialpadT9.convertLettersToNumbers("Ahmed", "xx"))
        assertEquals("8685", DialpadT9.convertLettersToNumbers("محمد", "xx"))
    }

    @Test
    fun rereadingLanguagesKeepsExistingOnesAndTheirOrder() {
        val before = DialpadT9.getSupportedSecondaryLanguages()
        val arabicBefore = DialpadT9.getLettersForNumber(2, "ar")
        // a lang that is already loaded is skipped, whatever letters the new data carries
        DialpadT9.readFromJson("""[{"lang":"ar","letters":["x","x","x","x","x","x","x","x"]}]""")
        assertEquals(before, DialpadT9.getSupportedSecondaryLanguages())
        assertEquals(arabicBefore, DialpadT9.getLettersForNumber(2, "ar"))
        assertEquals(before.sorted(), before)
        assertEquals(false, LOCALE_EN in before)
    }
}

/** Runs against the real raw resource, in its own sandbox so the object starts uninitialised. */
@RunWith(RobolectricTestRunner::class)
class DialpadT9InitTest {
    @Test
    fun ensureInitializedLoadsTheBundledLanguagesOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // the object is process-wide and may already be loaded by an earlier test class: this only proves the
        // real raw resource parses and a repeated call is harmless
        DialpadT9.ensureInitialized(context)
        assertEquals(listOf("ar", "be", "el", "ru", "uk"), DialpadT9.getSupportedSecondaryLanguages())
        assertEquals('8', DialpadT9.digitFor('م', "ar"))

        DialpadT9.ensureInitialized(context) // idempotent
        assertEquals(listOf("ar", "be", "el", "ru", "uk"), DialpadT9.getSupportedSecondaryLanguages())
    }
}
