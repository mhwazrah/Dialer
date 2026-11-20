package com.goodwy.dialer.helpers

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources

/**
 * Resolves each placeholder resource once and hands out a fresh mutable copy per bind, so a row's colour filter never
 * leaks into other rows and the layer-list is not re-inflated for every row. Main thread only (adapter binds).
 */
class PlaceholderDrawableCache(private val context: Context) {
    private val states = HashMap<Int, Drawable.ConstantState>()

    fun newDrawable(resId: Int): Drawable? {
        val state = states[resId] ?: AppCompatResources.getDrawable(context, resId)?.constantState?.also { states[resId] = it }
        return (state?.newDrawable(context.resources, context.theme) ?: AppCompatResources.getDrawable(context, resId))?.mutate()
    }
}
