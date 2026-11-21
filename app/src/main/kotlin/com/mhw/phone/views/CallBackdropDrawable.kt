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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Call screen backdrop (the call screen is always dark): near-black navy built on the brand primary (#001E3C). Over it, two soft colour fields; while ringing they drift and breathe (14 s loop) and three rings
 * pulse out of the caller avatar (2.4 s). One drawable and one animator: the call screen's glass panels refract
 * this background, so it is redrawn only while ringing. With system animations off it stays still (one faint ring).
 */
class CallBackdropDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val basePaint = Paint()
    private val fieldPaints = List(2) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val fieldMatrix = Matrix()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = 0xFF7FB2E5.toInt()
    }

    // Rings: avatar centre and radius in this drawable's coordinates; they never grow past [ringClipBottom] (the name)
    private var ringX = 0f
    private var ringY = 0f
    private var ringRadius = 0f
    private var ringClipBottom = 0f

    private var ringing = false
    // Animation clock; kept when ringing stops so the fields freeze where they are instead of snapping back
    private var elapsedMs = 0L
    private var clockAtStart = 0L
    private var lastFrameMs = -FRAME_MS
    private val animator = TimeAnimator().apply {
        setTimeListener { _, totalTime, _ ->
            elapsedMs = clockAtStart + totalTime
            // ~30 fps: the motion is slow and soft, and every redraw makes the glass panels re-refract this backdrop
            if (elapsedMs - lastFrameMs >= FRAME_MS) {
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

    fun startRinging() {
        if (ringing) return
        ringing = true
        clockAtStart = elapsedMs
        lastFrameMs = elapsedMs - FRAME_MS
        if (ValueAnimator.areAnimatorsEnabled()) animator.start()
        invalidateSelf()
    }

    fun stopRinging() {
        if (!ringing) return
        ringing = false
        animator.cancel()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return // not laid out yet: gradients need a positive radius
        basePaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF08284A.toInt(), 0xFF001E3C.toInt(), 0xFF000A18.toInt()), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // Fields stay in the top two-thirds: nothing moves near the answer slider
        val fields = listOf(0x267FB2E5, 0x1A4DB6AC) // light navy 15%, teal 10%
        val centres = listOf(0.2f to 0.18f, 0.82f to 0.55f)
        fieldPaints.forEachIndexed { i, paint ->
            paint.shader = RadialGradient(
                centres[i].first * w, centres[i].second * h, 0.7f * w,
                fields[i], fields[i] and 0x00FFFFFF, Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return // shaders are built in onBoundsChange for a non-empty size
        canvas.drawRect(b, basePaint)

        val animating = ringing && animator.isRunning
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

        if (!ringing || ringRadius <= 0f) return
        // Full circles that never reach the name: the growth is capped at the gap below the avatar
        val maxGrowth = ((ringClipBottom - ringY) / ringRadius - 1f).coerceIn(0f, 0.6f)
        if (animating) {
            for (i in 0 until RINGS) {
                val t = ((elapsedMs % RING_MS) / RING_MS.toFloat() + i / RINGS.toFloat()) % 1f
                val eased = 1f - (1f - t) * (1f - t) // decelerate
                ringPaint.alpha = (0.30f * (1f - eased) * 255).toInt()
                canvas.drawCircle(ringX, ringY, ringRadius * (1f + maxGrowth * eased), ringPaint)
            }
        } else {
            ringPaint.alpha = (0.15f * 255).toInt()
            canvas.drawCircle(ringX, ringY, ringRadius * (1f + maxGrowth / 4), ringPaint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        const val AURORA_MS = 14_000L
        const val RING_MS = 2_400L
        const val RINGS = 3
        const val FRAME_MS = 33L
    }
}
