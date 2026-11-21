package com.mhw.phone.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.mhw.phone.R
import com.mhw.phone.extensions.config
import com.mhw.phone.helpers.APP_THEME_DARK
import com.mhw.phone.helpers.APP_THEME_LIGHT
import com.mhw.phone.helpers.applyAppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Draws into a recording canvas: Robolectric does not rasterise faithfully, so the paints handed to it are what is asserted. */
@RunWith(RobolectricTestRunner::class)
class GlassDialogDrawableTest {
    private class RoundRect(val rect: RectF, val radius: Float, val color: Int, val alpha: Int, val colorFilter: ColorFilter?, val stroke: Boolean)

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val roundRects = ArrayList<RoundRect>()
        override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
            roundRects += RoundRect(RectF(rect), rx, paint.color, paint.alpha, paint.colorFilter, paint.style == Paint.Style.STROKE)
        }
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun inflated(dark: Boolean): GlassDialogDrawable {
        context.config.appTheme = if (dark) APP_THEME_DARK else APP_THEME_LIGHT
        context.applyAppTheme()
        val drawable = ContextCompat.getDrawable(context, R.drawable.dialog_bg)
        assertTrue("dialog_bg resolves to $drawable", drawable is GlassDialogDrawable)
        return (drawable as GlassDialogDrawable).apply { setBounds(0, 0, 300, 400) }
    }

    private fun draw(drawable: GlassDialogDrawable): List<RoundRect> {
        val canvas = RecordingCanvas(Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888))
        drawable.draw(canvas)
        return canvas.roundRects
    }

    @Test
    fun resourceDialogBgIsTheGlassDrawable() {
        inflated(dark = true)
    }

    @Test
    fun drawsFillSheenAndRimInOrder() {
        val rects = draw(inflated(dark = true))
        assertEquals(3, rects.size)
        assertEquals(listOf(false, false, true), rects.map { it.stroke })
    }

    @Test
    fun darkThemeUsesTheDarkOpaqueFill() {
        val fill = draw(inflated(dark = true))[0]
        assertEquals(0xFF001123.toInt(), fill.color)
    }

    @Test
    fun lightThemeUsesTheLightOpaqueFill() {
        val fill = draw(inflated(dark = false))[0]
        assertEquals(0xFFF7FAFD.toInt(), fill.color)
    }

    @Test
    fun rimColourFollowsTheTheme() {
        assertEquals(0x40FFFFFF, draw(inflated(dark = true))[2].color)
        assertEquals(0x26001E3C, draw(inflated(dark = false))[2].color)
    }

    @Test
    fun themeIsReadAtInflateTimeNotAtDrawTime() {
        val drawable = inflated(dark = true)
        context.config.appTheme = APP_THEME_LIGHT
        context.applyAppTheme()
        assertEquals(0xFF001123.toInt(), draw(drawable)[0].color)
    }

    @Test
    fun colorFilterIsIgnored() {
        val drawable = inflated(dark = true)
        drawable.colorFilter = PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
        draw(drawable).forEach { assertNull(it.colorFilter) }
        assertEquals(0xFF001123.toInt(), draw(drawable)[0].color)
    }

    @Test
    fun alphaOnlyAffectsTheFill() {
        val drawable = inflated(dark = false)
        drawable.alpha = 100
        val rects = draw(drawable)
        assertEquals(100, rects[0].alpha)
        assertEquals(0xF7FAFD, rects[0].color and 0xFFFFFF)
    }

    @Test
    fun allLayersShareOneRoundedRectInsetByHalfTheRim() {
        val rects = draw(inflated(dark = true))
        val first = rects[0]
        assertTrue(first.radius > 0f)
        rects.forEach {
            assertEquals(first.rect, it.rect)
            assertEquals(first.radius, it.radius, 0f)
        }
        assertTrue(first.rect.left > 0f && first.rect.top > 0f)
        assertEquals(300f - first.rect.right, first.rect.left, 0.001f)
        assertEquals(400f - first.rect.bottom, first.rect.top, 0.001f)
    }

    @Test
    fun resizingFollowsTheNewBounds() {
        val drawable = inflated(dark = true)
        drawable.setBounds(0, 0, 120, 90)
        val rect = draw(drawable)[0].rect
        assertTrue(rect.right <= 120f && rect.bottom <= 90f)
    }

    @Test
    fun isTranslucentSoTheRoundedCornersShowThrough() {
        @Suppress("DEPRECATION")
        assertEquals(PixelFormat.TRANSLUCENT, inflated(dark = true).opacity)
    }
}
