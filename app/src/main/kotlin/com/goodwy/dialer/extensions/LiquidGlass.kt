package com.goodwy.dialer.extensions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.createBitmap
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getNameLetter
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.helpers.SimpleContactsHelper
import androidx.core.graphics.ColorUtils
import android.view.ViewTreeObserver
import androidx.core.view.isInvisible
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.dialer.R

// Single source of truth for the app's Liquid Glass look: every screen paints the same
// backdrop and every glass panel is configured here, so the style can be tuned in one place.

/** Whether the user's theme is light, judged from the theme's background colour. */
fun Context.isLiquidLightTheme(): Boolean =
    ColorUtils.calculateLuminance(getProperBackgroundColor()) > 0.5

// RULE — glass look, never see-through: any surface that floats over other content (bars, tab
// bar, dialpad keypad, sheets) gets an opaque frosted base. Glass shows as tint, sheen, rim and
// blur; content behind it (list rows, text) must never be legible through it. Only the plain
// colour-field backdrop may show through, as it carries no content.

/** Opaque frosted sheet for panels floating over scrolling content (e.g. the dialpad keypad). */
fun View.applyGlassSurface(topCornerRadiusDp: Float = 28f) {
    val radius = topCornerRadiusDp * resources.displayMetrics.density
    background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        setColor(if (context.isLiquidLightTheme()) 0xFFF7FAFD.toInt() else 0xFF001123.toInt())
        setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), 0x40FFFFFF)
    }
    // Opaque to touch as well as to sight: taps on the sheet's empty areas must not reach the
    // list rows underneath.
    isClickable = true
}

/**
 * Contact avatar in the brand look: a photo when there is one, otherwise the name's letter on a
 * circle in the theme's primary colour (commons draws a fixed grey when coloured contacts are off,
 * and its palettes are hardcoded). With coloured contacts on, the commons palette is kept.
 */
fun ImageView.loadBrandedContactImage(photoUri: String, name: String) {
    val helper = SimpleContactsHelper(context)
    if (photoUri.isNotEmpty() || context.baseConfig.useColoredContacts || name.isEmpty()) {
        helper.loadContactImage(photoUri, this, name)
        return
    }
    setImageBitmap(context.brandedLetterAvatar(name))
}

private fun Context.brandedLetterAvatar(name: String): Bitmap {
    val size = resources.getDimension(com.goodwy.commons.R.dimen.contact_photo_big_size).toInt()
    val primary = getProperPrimaryColor()
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Navy letter on light primaries (e.g. the dark-theme light blue), white on dark ones.
        color = if (ColorUtils.calculateLuminance(primary) > 0.3) 0xFF001123.toInt() else android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = size / 2f
    }
    val letter = name.getNameLetter()
    canvas.drawText(letter, size / 2f, size / 2f - (text.descent() + text.ascent()) / 2, text)
    return bitmap
}

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
    // INVISIBLE, not GONE: the swipe view measures its action views for the swipe distance.
    val listener = ViewTreeObserver.OnPreDrawListener {
        val swiping = content.translationX != 0f
        actions.forEach { if (it.isInvisible == swiping) it.isInvisible = !swiping }
        true
    }
    setTag(R.id.glass_swipe_listener, listener)
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = v.viewTreeObserver.addOnPreDrawListener(listener)
        override fun onViewDetachedFromWindow(v: View) = v.viewTreeObserver.removeOnPreDrawListener(listener)
    })
    if (isAttachedToWindow) viewTreeObserver.addOnPreDrawListener(listener)
}

/**
 * Configures a glass panel with the app's look. Dynamic background stays on: panels float over
 * scrolling content, and without it the backdrop is captured once and the glass looks frozen.
 */
fun LiquidGlassView.applyLiquidGlass(pressable: Boolean = false) {
    val isLight = context.isLiquidLightTheme()
    material = GlassMaterial.REGULAR
    // Frosted, not clear: content scrolling under the bars should read as soft colour, never as
    // legible rows. Strong tint plus a wide blur (radius = base + blurAmount * 32 px).
    glassTint = if (isLight) 0xEBF7FAFD.toInt() else 0xEB001123.toInt()
    blurAmount = 0.75f
    dispersionStrength = 0.08f
    enableAdaptiveTint = false
    enableSensorHighlight = false
    enablePressEffect = pressable
    collectFrameStats = false
    enableDynamicBackground = true
    // Below Android 13 the library blurs on the CPU with a native library it ships for ARM only.
    // A device running the app as x86/x86_64 (Intel Chromebooks, emulators) doesn't have it, and
    // the first draw crashes; keep tint and highlight, drop the native-backed effects instead.
    if (effectiveApiLevel < 33 && !isNativeGlassBlurAvailable) {
        enableBackdropBlur = false
        enableChromaticAberration = false
        enableChromaticDispersion = false
    }
}

private val isNativeGlassBlurAvailable: Boolean by lazy {
    try {
        System.loadLibrary("nativegauss")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }
}
