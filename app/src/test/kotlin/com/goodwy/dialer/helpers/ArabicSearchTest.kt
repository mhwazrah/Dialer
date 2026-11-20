package com.goodwy.dialer.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.text.Normalizer

class ArabicSearchTest {

    @Test
    fun foldsVariants() {
        val cases = mapOf(
            "أحمد" to "احمد", "إيمان" to "ايمان", "آمنة" to "امنه", "ٱلله" to "الله",
            "على" to "علي", "مدرسة" to "مدرسه", "مؤمن" to "مومن", "هانئ" to "هاني",
            "مُحَمَّد" to "محمد", "محـــمد" to "محمد", "کریم" to "كريم", "علي‏" to "علي"
        )
        for ((input, expected) in cases) assertEquals(input, expected, input.foldArabic())
    }

    @Test
    fun decomposedInputFoldsTheSame() {
        // commons normalizeString() runs NFD first, which splits أ into ا + U+0654
        assertEquals("احمد", Normalizer.normalize("أحمد", Normalizer.Form.NFD).foldArabic())
    }

    @Test
    fun matchRangePointsIntoOriginalText() {
        assertEquals(0..3, "أحمد علي".foldedMatchRange("احمد"))
        assertEquals(5..7, "أحمد على".foldedMatchRange("علي"))
        // tashkeel and tatweel inside and right after the match are covered
        assertEquals(0..7, "مُحَمَّد".foldedMatchRange("محمد"))
        assertEquals(0..6, "محـــمد".foldedMatchRange("محمد"))
        assertEquals(4..8, "Dr. Ahmed".foldedMatchRange("ahmed"))
        assertNull("أحمد".foldedMatchRange("سعيد"))
        assertNull("أحمد".foldedMatchRange("َ"))
    }

    @Test
    fun idempotentAndLatinUntouched() {
        val latin = "John Smith"
        assertSame(latin, latin.foldArabic())
        assertEquals("احمد", "أحمد".foldArabic().foldArabic())
    }
}
