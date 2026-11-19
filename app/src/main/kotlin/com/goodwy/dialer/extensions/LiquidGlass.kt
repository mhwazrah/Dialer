package com.goodwy.dialer.extensions

import android.content.Context
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.dialer.R

// Single source of truth for the app's Liquid Glass look: every screen paints the same
// backdrop and every glass panel is configured here, so the style can be tuned in one place.

/** Whether the user's theme is light, judged from the theme's background colour. */
fun Context.isLiquidLightTheme(): Boolean =
    ColorUtils.calculateLuminance(getProperBackgroundColor()) > 0.5

/** Paints the colour-field backdrop the glass panels refract, matching the theme brightness. */
fun View.applyLiquidBackdrop() {
    setBackgroundResource(
        if (context.isLiquidLightTheme()) R.drawable.bg_liquid_backdrop_light
        else R.drawable.bg_liquid_backdrop_dark
    )
}

/**
 * Swipe rows draw their action backgrounds (call / delete) behind the row content. With a
 * translucent glass row those backgrounds would always show through, so only draw them while the
 * row is actually swiped. Safe to call on every bind: the listener is installed once per view.
 */
fun View.showSwipeActionsOnlyWhileSwiping(content: View, vararg actions: View) {
    if (getTag(R.id.glass_swipe_listener) != null) return
    setTag(R.id.glass_swipe_listener, true)
    viewTreeObserver.addOnPreDrawListener {
        val swiping = content.translationX != 0f
        actions.forEach { if (it.isVisible != swiping) it.isVisible = swiping }
        true
    }
}

/**
 * Configures a glass panel. [backdrop] is the view the glass refracts; null means its direct
 * parent (the library default). Panels over scrolling content must keep [dynamic] on, or the
 * backdrop is captured once and the glass looks frozen.
 */
fun LiquidGlassView.applyLiquidGlass(
    cornerRadiusDp: Float? = null,
    backdrop: View? = null,
    dynamic: Boolean = true,
    pressable: Boolean = false,
) {
    val isLight = context.isLiquidLightTheme()
    if (cornerRadiusDp != null) cornerRadius = cornerRadiusDp * resources.displayMetrics.density
    material = GlassMaterial.REGULAR
    glassTint = if (isLight) 0x59FFFFFF else 0x4D001E3C
    dispersionStrength = 0.08f
    enableAdaptiveTint = false
    enableSensorHighlight = false
    enablePressEffect = pressable
    collectFrameStats = false
    enableDynamicBackground = dynamic
    if (backdrop != null) backdropSource = backdrop
}
