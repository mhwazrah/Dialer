package com.goodwy.dialer.extensions

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import com.goodwy.dialer.R

val View.boundingBox
    get() = Rect().also { getGlobalVisibleRect(it) }

fun View.setWidth(size: Int) {
    val lp = layoutParams
    lp.width = size
    layoutParams = lp
}

/**
 * Spacing for a keypad key's letters under its number. With two rows (secondary language + Latin) the second row
 * is pulled up and the first pushed down so number, primary and secondary letters sit evenly spaced.
 */
fun TextView.applyDialpadLetterRows(twoRows: Boolean) {
    val shift = if (twoRows) resources.getDimension(R.dimen.dialpad_letter_rows_shift) else 0f
    setLineSpacing(-shift, 1f)
    updateLayoutParams<ViewGroup.MarginLayoutParams> {
        topMargin = resources.getDimensionPixelOffset(R.dimen.dialpad_letters_top_margin) + shift.toInt()
    }
}
