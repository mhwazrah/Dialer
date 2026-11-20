package com.mhw.phone.helpers

import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan

/**
 * Folds Arabic spelling variants so a search matches however a name was typed: Alef forms (أ إ آ ٱ) → ا,
 * ى/ی → ي, ة → ه, ؤ → و, ئ → ي, ک → ك; strips tashkeel (U+064B–U+065F, U+0670), tatweel and invisible
 * bidi/joiner marks. Apply to both the searched text and the query. Idempotent; returns [this] when nothing changes.
 */
fun String.foldArabic(): String {
    if (none { it.foldsArabic() }) return this
    val out = StringBuilder(length)
    for (c in this) c.foldedOrNull()?.let { out.append(it) }
    return out.toString()
}

/**
 * Where [query] matches this text once both are [foldArabic]ed, as indices into this (unfolded) text, or null.
 * Marks right after the last matched letter (e.g. its tashkeel) are included.
 */
fun String.foldedMatchRange(query: String): IntRange? {
    val folded = query.foldArabic()
    if (folded.isEmpty()) return null
    val text = StringBuilder(length)
    val origin = IntArray(length) // folded index -> index in this
    forEachIndexed { i, c -> c.foldedOrNull()?.let { origin[text.length] = i; text.append(it) } }
    val start = text.indexOf(folded, ignoreCase = true).takeIf { it >= 0 } ?: return null
    var end = origin[start + folded.length - 1] + 1
    while (end < length && this[end].isStrippedArabic()) end++
    return origin[start] until end
}

/** This text with the [foldedMatchRange] of [query] in [color], or null when it does not match. */
fun String.highlightFoldedMatch(query: String, color: Int): SpannableString? {
    val range = foldedMatchRange(query) ?: return null
    return SpannableString(this).apply {
        setSpan(ForegroundColorSpan(color), range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private fun Char.foldedOrNull(): Char? = when (this) {
    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
    'ى', 'ی', 'ئ' -> 'ي'
    'ة' -> 'ه'
    'ؤ' -> 'و'
    'ک' -> 'ك'
    else -> if (isStrippedArabic()) null else this
}

private fun Char.isStrippedArabic(): Boolean =
    this in 'ً'..'ٟ' || this == 'ٰ' || this == 'ـ' || this in '‌'..'‏' || this == '؜'

private fun Char.foldsArabic(): Boolean = this in "أإآٱىیئةؤک" || isStrippedArabic()
