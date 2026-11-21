package com.mhw.phone.views

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import com.mhw.phone.helpers.isAppThemeDark
import org.xmlpull.v1.XmlPullParser

/**
 * Frosted glass background for every dialog window, ours and the commons library's: it replaces the commons
 * `dialog_bg` drawable (res/drawable/dialog_bg.xml). Commons tints that drawable with a flat colour filter, which
 * would erase the sheen and rim, so [setColorFilter] is ignored on purpose. The fill is opaque: nothing behind a
 * dialog is ever readable through it. Light/dark follows the app theme ([isAppThemeDark]).
 */
class GlassDialogDrawable : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var radius = 0f
    private var dark = false

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        // The app theme, not the resources' night mode: dialog text uses the app theme's colours, and the always-dark
        // call screen forces night resources
        dark = isAppThemeDark
        radius = r.getDimension(com.goodwy.commons.R.dimen.dialog_corner_radius)
        rim.strokeWidth = r.displayMetrics.density.coerceAtLeast(1f)
        // Same opaque bases as the dialpad's glass sheet (LiquidGlass.applyGlassSurface)
        fill.color = if (dark) 0xFF001123.toInt() else 0xFFF7FAFD.toInt()
        rim.color = if (dark) 0x40FFFFFF else 0x26001E3C
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        rect.inset(rim.strokeWidth / 2, rim.strokeWidth / 2)
        // Top specular sheen fading out by a third of the height
        sheen.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.top + bounds.height() * 0.35f,
            if (dark) 0x1FFFFFFF else 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, sheen)
        canvas.drawRoundRect(rect, radius, radius, rim)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit // see class doc

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
