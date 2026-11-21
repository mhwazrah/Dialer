package com.mhw.phone.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StringTest {
    @Test
    fun searchQueryTrimsAndCollapsesWhitespace() {
        assertEquals("a b c", "  a \t b\n\n c  ".toSearchQuery())
        assertEquals("", "   ".toSearchQuery())
        assertEquals("", "".toSearchQuery())
    }

    @Test
    fun searchQueryKeepsArabicText() {
        assertEquals("محمد علي", "  محمد   علي ".toSearchQuery())
    }

    @Test
    fun firstCodePoint() {
        assertNull("".firstCodePointOrNull())
        assertEquals('a'.code, "abc".firstCodePointOrNull())
        assertEquals('م'.code, "محمد".firstCodePointOrNull())
        assertEquals(0x1F600, "😀x".firstCodePointOrNull()) // surrogate pair is one code point
    }

    @Test
    fun numberForNotesStripsPlusSpacesParensHyphens() {
        assertEquals("1234567890", "+1 (234) 567-890".numberForNotes())
        assertEquals("*123#", "*123#".numberForNotes())
        assertEquals("", "".numberForNotes())
    }

    @Test
    fun removeNumberFormattingAlsoStripsStarAndHash() {
        assertEquals("+1234567890", "+1 (234) 567-890".removeNumberFormatting())
        assertEquals("123", "*123#".removeNumberFormatting())
    }

    @Test
    fun countryByNumberIsEmptyForUnparseableNumber() {
        assertEquals("", "abc".getCountryByNumber())
        assertEquals("", "".getCountryByNumber())
    }

    @Test
    fun countryByNumberIsStableAcrossCachedCalls() {
        val first = "+14155552671".getCountryByNumber()
        assertEquals(first, "+14155552671".getCountryByNumber())
    }
}
