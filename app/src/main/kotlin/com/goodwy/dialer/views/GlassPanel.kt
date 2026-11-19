package com.goodwy.dialer.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.View
import com.example.liquidglass.LiquidGlassView
import com.goodwy.dialer.extensions.applyLiquidGlass
import com.goodwy.dialer.extensions.isLiquidLightTheme

/**
 * The app's glass panel. Rule: glass look, never see-through (see extensions/LiquidGlass.kt).
 *
 * - With `app:backdropSourceId` (the call screen, pointing at the call background) it is live
 *   refracting glass: the backdrop there is a plain colour/photo, never readable content.
 * - Otherwise it floats over scrolling lists, where refraction would only make rows legible
 *   through it — so it draws a static frosted panel: near-opaque frost, top sheen, hairline rim.
 *   Also used below Android 12, where the library has no GPU pipeline (150-600 ms per frame).
 */
class GlassPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LiquidGlassView(context, attrs, defStyleAttr) {
    private val useStaticGlass: Boolean

    init {
        applyLiquidGlass(pressable = isClickable)
        val hasExplicitBackdrop = (attrs?.getAttributeResourceValue(RES_AUTO, "backdropSourceId", 0) ?: 0) != 0
        useStaticGlass = !hasExplicitBackdrop || effectiveApiLevel < 31
        if (useStaticGlass) {
            enableDynamicBackground = false
            background = staticGlassDrawable()
        }
    }

    private fun staticGlassDrawable(): LayerDrawable {
        val isLight = context.isLiquidLightTheme()
        val radius = cornerRadius // clamped by GradientDrawable to half the short side
        val frost = GradientDrawable().apply {
            cornerRadius = radius
            // Fully opaque base: even 5% transparency left rows ghosting through (user rule).
            setColor(if (isLight) 0xFFF7FAFD.toInt() else 0xFF001123.toInt())
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(if (isLight) 0x66FFFFFF else 0x1FFFFFFF, 0x00FFFFFF, 0x00FFFFFF),
        ).apply { cornerRadius = radius }
        val rim = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0)
            setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), if (isLight) 0x8CFFFFFF.toInt() else 0x33FFFFFF)
        }
        return LayerDrawable(arrayOf(frost, sheen, rim))
    }

    override fun onDraw(canvas: Canvas) {
        if (!useStaticGlass) super.onDraw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow() // resolves app:backdropSourceId
        if (backdropSource == null) {
            backdropSource = rootView.findViewById<View>(android.R.id.content)
        }
    }

    private companion object {
        const val RES_AUTO = "http://schemas.android.com/apk/res-auto"
    }
}
