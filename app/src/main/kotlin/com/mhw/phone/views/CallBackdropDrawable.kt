package com.mhw.phone.views

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.withTranslation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Call screen backdrop, light or dark with the app theme. Over a navy (or pale blue) gradient, two soft colour fields
 * drift and breathe (14 s loop) and glass bubbles of several sizes float on slow, looping paths. While ringing, three
 * rings also pulse out of the caller avatar (2.4 s). One drawable and one animator, running while the screen is
 * shown: the call screen's glass panels refract this background, so frames are rationed (~30 fps while ringing, ~15
 * fps otherwise; the motion is slow). With system animations off everything stays still (one faint ring).
 */
class CallBackdropDrawable(context: Context, light: Boolean) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val palette = if (light) LIGHT else DARK
    private val basePaint = Paint()
    private val fieldPaints = List(2) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val fieldMatrix = Matrix()
    // Paint alpha scales each bubble's shaders by its opacity
    private val bubbleFills = BUBBLES.map { Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = (it.opacity * 255).toInt() } }
    private val bubbleShines = BUBBLES.map { Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = (it.opacity * 255).toInt() } }
    private val bubbleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = palette.bubbleRim
    }
    private val rimAlpha = palette.bubbleRim ushr 24
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = palette.ring
    }
    private val ringAlpha = (palette.ring ushr 24) / 255f

    // Rings: avatar centre and radius in this drawable's coordinates; they never grow past [ringClipBottom] (the name)
    private var ringX = 0f
    private var ringY = 0f
    private var ringRadius = 0f
    private var ringClipBottom = 0f

    private var ringing = false
    // Animation clock; kept when the animation stops so everything freezes where it is instead of snapping back
    private var elapsedMs = 0L
    private var clockAtStart = 0L
    private var lastFrameMs = 0L
    private val animator = TimeAnimator().apply {
        setTimeListener { _, totalTime, _ ->
            elapsedMs = clockAtStart + totalTime
            if (elapsedMs - lastFrameMs >= if (ringing) RINGING_FRAME_MS else IDLE_FRAME_MS) {
                lastFrameMs = elapsedMs
                invalidateSelf()
            }
        }
    }

    fun setRings(centerX: Float, centerY: Float, radius: Float, clipBottom: Float) {
        ringX = centerX
        ringY = centerY
        ringRadius = radius
        ringClipBottom = clipBottom
        invalidateSelf()
    }

    /** Runs the motion while the call screen is shown (started), freezing it otherwise. */
    fun setAnimating(animating: Boolean) {
        if (!animating) {
            animator.cancel()
            return
        }
        if (animator.isRunning || !ValueAnimator.areAnimatorsEnabled()) return
        clockAtStart = elapsedMs
        lastFrameMs = elapsedMs
        animator.start()
    }

    /** The avatar rings show only while the call is ringing. */
    fun setRinging(ringing: Boolean) {
        if (this.ringing == ringing) return
        this.ringing = ringing
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return // not laid out yet: gradients need a positive radius
        basePaint.shader = LinearGradient(0f, 0f, 0f, h, palette.base, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        // Fields stay in the top two-thirds: nothing moves near the answer slider
        val centres = listOf(0.2f to 0.18f, 0.82f to 0.55f)
        fieldPaints.forEachIndexed { i, paint ->
            val colour = palette.fields[i]
            paint.shader = RadialGradient(
                centres[i].first * w, centres[i].second * h, 0.7f * w, colour, colour and 0x00FFFFFF, Shader.TileMode.CLAMP
            )
        }
        // Each bubble is drawn around the origin (draw translates the canvas), so its shaders are built once here.
        // Glass sphere: clear centre, tinted edge, and a soft highlight towards the top-start
        BUBBLES.forEachIndexed { i, bubble ->
            val r = bubble.radiusDp * density
            val edge = palette.bubbleEdge
            bubbleFills[i].shader = RadialGradient(
                0f, 0f, r, intArrayOf(edge and 0x00FFFFFF, edge and 0x00FFFFFF, edge), floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            bubbleShines[i].shader = RadialGradient(
                -0.38f * r, -0.42f * r, 0.42f * r, palette.bubbleShine, palette.bubbleShine and 0x00FFFFFF,
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return // shaders are built in onBoundsChange for a non-empty size
        canvas.drawRect(b, basePaint)

        val loop = (elapsedMs % AURORA_MS) / AURORA_MS.toFloat()
        fieldPaints.forEachIndexed { i, paint ->
            val phase = 2 * PI.toFloat() * loop + i * PI.toFloat() // opposite phases
            val drift = sin(phase) * 24 * density
            val scale = 1f + 0.075f * (1f - cos(phase)) // 1.0 → 1.15
            fieldMatrix.setScale(scale, scale, b.exactCenterX(), b.exactCenterY())
            fieldMatrix.postTranslate(drift, -drift / 2)
            paint.shader.setLocalMatrix(fieldMatrix)
            canvas.drawRect(b, paint)
        }
        drawBubbles(canvas, b)
        if (ringing && ringRadius > 0f) drawRings(canvas)
    }

    // Lissajous float: each bubble loops on its own periods around its home spot, breathing a little
    private fun drawBubbles(canvas: Canvas, b: Rect) {
        BUBBLES.forEachIndexed { i, bubble ->
            val phase = i * 2.4f // spreads the bubbles' starting points around their loops
            val tx = 2 * PI.toFloat() * (elapsedMs % bubble.periodMs) / bubble.periodMs + phase
            val ty = 2 * PI.toFloat() * (elapsedMs % (bubble.periodMs * 13 / 10)) / (bubble.periodMs * 1.3f) + phase
            val r = bubble.radiusDp * density
            val scale = 1f + 0.06f * sin(tx * 2)
            bubbleRim.alpha = (rimAlpha * bubble.opacity).toInt()
            canvas.withTranslation(
                b.left + bubble.x * b.width() + sin(tx) * bubble.swayDp * density,
                b.top + bubble.y * b.height() + cos(ty) * bubble.swayDp * 0.8f * density
            ) {
                scale(scale, scale)
                drawCircle(0f, 0f, r, bubbleFills[i])
                drawCircle(0f, 0f, r - bubbleRim.strokeWidth / 2, bubbleRim)
                drawCircle(-0.38f * r, -0.42f * r, 0.42f * r, bubbleShines[i])
            }
        }
    }

    // Full circles that never reach the name: the growth is capped at the gap below the avatar
    private fun drawRings(canvas: Canvas) {
        val maxGrowth = ((ringClipBottom - ringY) / ringRadius - 1f).coerceIn(0f, 0.6f)
        if (!animator.isRunning) {
            ringPaint.alpha = (ringAlpha / 2 * 255).toInt()
            canvas.drawCircle(ringX, ringY, ringRadius * (1f + maxGrowth / 4), ringPaint)
            return
        }
        for (i in 0 until RINGS) {
            val t = ((elapsedMs % RING_MS) / RING_MS.toFloat() + i / RINGS.toFloat()) % 1f
            val eased = 1f - (1f - t) * (1f - t) // decelerate
            ringPaint.alpha = (ringAlpha * (1f - eased) * 255).toInt()
            canvas.drawCircle(ringX, ringY, ringRadius * (1f + maxGrowth * eased), ringPaint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    /** Home spot as a fraction of the screen, size, sway distance, loop length and opacity (1 = full). */
    private class Bubble(val x: Float, val y: Float, val radiusDp: Float, val swayDp: Float, val periodMs: Long, val opacity: Float = 1f)

    private class Palette(
        val base: IntArray,
        val fields: List<Int>,
        val ring: Int, // its alpha is the newest ring's opacity
        val bubbleEdge: Int,
        val bubbleRim: Int,
        val bubbleShine: Int,
    )

    private companion object {
        const val AURORA_MS = 14_000L
        const val RING_MS = 2_400L
        const val RINGS = 3
        const val RINGING_FRAME_MS = 33L
        const val IDLE_FRAME_MS = 66L

        // Big ones at the edges, small ones in between; the lowest stay small and faint near the answer slider
        val BUBBLES = listOf(
            Bubble(0.12f, 0.10f, 70f, 28f, 23_000),
            Bubble(0.88f, 0.20f, 42f, 22f, 19_000),
            Bubble(0.66f, 0.06f, 20f, 18f, 17_000),
            Bubble(0.30f, 0.40f, 26f, 30f, 27_000),
            Bubble(0.98f, 0.50f, 60f, 22f, 31_000, opacity = 0.5f), // behind the right-hand buttons: small and faint
            Bubble(0.06f, 0.66f, 54f, 24f, 25_000),
            Bubble(0.58f, 0.60f, 16f, 20f, 21_000),
            Bubble(0.80f, 0.84f, 34f, 22f, 29_000),
            Bubble(0.24f, 0.92f, 22f, 18f, 18_000),
        )

        val DARK = Palette(
            base = intArrayOf(0xFF08284A.toInt(), 0xFF001E3C.toInt(), 0xFF000A18.toInt()),
            fields = listOf(0x267FB2E5, 0x1A4DB6AC), // light navy 15%, teal 10%
            ring = 0x4D7FB2E5, // 30%
            bubbleEdge = 0x2E9CC8F0, // pale blue 18%
            bubbleRim = 0x33FFFFFF,
            bubbleShine = 0x4DFFFFFF,
        )
        val LIGHT = Palette(
            base = intArrayOf(0xFFEFF5FC.toInt(), 0xFFE3EDF8.toInt(), 0xFFCFDFF0.toInt()),
            fields = listOf(0x403D8BD9, 0x2E4DB6AC), // brand blue 25%, teal 18%
            ring = 0x662E6BB0, // 40%
            bubbleEdge = 0x332E6BB0, // brand blue 20%
            bubbleRim = 0xB3FFFFFF.toInt(),
            bubbleShine = 0xCCFFFFFF.toInt(),
        )
    }
}
