package com.mhw.phone.extensions

import android.util.LruCache
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.*

private val WHITESPACE_REGEX = Regex("\\s+")

/** The text of a search box: trimmed, inner whitespace runs collapsed to one space. */
fun String.toSearchQuery(): String = trim().replace(WHITESPACE_REGEX, " ")

fun String.firstCodePointOrNull(): Int? = if (isEmpty()) null else codePointAt(0)

private val countryByNumberCache = LruCache<String, String>(256)

fun String.getCountryByNumber(): String {
    val locale = Locale.getDefault()
    val key = "$locale|$this"
    countryByNumberCache.get(key)?.let { return it }
    val country = try {
        val numberParse = PhoneNumberUtil.getInstance().parse(this, locale.country)
        PhoneNumberOfflineGeocoder.getInstance().getDescriptionForNumber(numberParse, locale)
    } catch (_: NumberParseException) {
        ""
    }
    countryByNumberCache.put(key, country)
    return country
}

// remove the pluses, spaces and hyphens.
fun String.numberForNotes() = filterNot { it.isWhitespace() || it in "+()-" }

fun String.removeNumberFormatting() = filterNot { it.isWhitespace() || it in "()-*#" }
