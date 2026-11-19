package com.goodwy.dialer.views

import android.content.Context
import android.util.AttributeSet
import com.example.liquidglass.LiquidGlassView
import com.goodwy.dialer.extensions.applyLiquidGlass

/**
 * A [LiquidGlassView] pre-configured with the app's glass style, so layouts can declare glass
 * with just the tag. Corner radius comes from `app:cornerRadius` (library default: pill);
 * `app:backdropSourceId` points it at a backdrop other than its direct parent.
 */
class GlassPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LiquidGlassView(context, attrs, defStyleAttr) {
    init {
        applyLiquidGlass(backdrop = backdropSource, pressable = isClickable)
    }
}
