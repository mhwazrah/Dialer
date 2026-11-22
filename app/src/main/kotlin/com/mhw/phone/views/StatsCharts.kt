package com.mhw.phone.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import kotlin.math.max

private fun View.dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

/**
 * Plain bar chart: one rounded bar per value, the tallest in full colour and the rest softer, with optional labels
 * under the bars (blank labels are skipped, so long series can label every few bars). Follows the layout direction.
 */
class BarChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var values: List<Int> = emptyList()
    private var labels: List<String> = emptyList()
    private var barColor = 0
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }
    private val bar = RectF()
    private val labelSpace = dp(18f)
    private val minBar = dp(3f)
    private val maxBar = dp(16f) // a short series (7 weekdays) keeps slim bars, not blobs

    fun setData(values: List<Int>, labels: List<String>, barColor: Int, labelColor: Int) {
        this.values = values
        this.labels = labels
        this.barColor = barColor
        labelPaint.color = labelColor
        emptyPaint.color = ColorUtils.setAlphaComponent(labelColor, 0x22)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val hasLabels = labels.any { it.isNotEmpty() }
        val chartBottom = height - paddingBottom - if (hasLabels) labelSpace else 0f
        val chartHeight = chartBottom - paddingTop
        val slot = (width - paddingStart - paddingEnd).toFloat() / values.size
        val barWidth = (slot * 0.62f).coerceAtMost(maxBar)
        val radius = barWidth / 2
        val top = max(values.max(), 1)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        values.forEachIndexed { index, value ->
            val position = if (rtl) values.size - 1 - index else index
            val centre = paddingStart + slot * position + slot / 2
            val barHeight = if (value == 0) minBar else max(minBar, chartHeight * value / top)
            bar.set(centre - barWidth / 2, chartBottom - barHeight, centre + barWidth / 2, chartBottom)
            val paint = if (value == 0) emptyPaint else barPaint.apply {
                color = if (value == top) barColor else ColorUtils.setAlphaComponent(barColor, 0x8C)
            }
            canvas.drawRoundRect(bar, radius, radius, paint)
            labels.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let {
                // Kept inside the view, so the first and last labels are not cut off at the edges
                val half = labelPaint.measureText(it) / 2
                val x = centre.coerceIn(paddingStart + half, width - paddingEnd - half)
                canvas.drawText(it, x, height - paddingBottom - labelPaint.descent(), labelPaint)
            }
        }
    }
}

/** One rounded bar split into coloured parts in proportion to their values (the call mix), from the start side. */
class MixBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var parts: List<Pair<Int, Int>> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val bounds = RectF()
    private val gap = dp(2f)

    /** [parts] are (value, colour) pairs. */
    fun setParts(parts: List<Pair<Int, Int>>) {
        this.parts = parts.filter { it.first > 0 }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = parts.sumOf { it.first }
        if (total == 0) return
        bounds.set(paddingStart.toFloat(), paddingTop.toFloat(), (width - paddingEnd).toFloat(), (height - paddingBottom).toFloat())
        clip.reset()
        clip.addRoundRect(bounds, bounds.height() / 2, bounds.height() / 2, Path.Direction.CW)
        canvas.withClip(clip) {
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            var start = 0f
            parts.forEachIndexed { index, (value, color) ->
                val length = bounds.width() * value / total
                val left = if (rtl) bounds.right - start - length else bounds.left + start
                // A thin gap between parts; the edge of the bar stays flush
                val visible = if (index == parts.lastIndex) length else length - gap
                paint.color = color
                if (rtl) canvas.drawRect(left + length - visible, bounds.top, left + length, bounds.bottom, paint)
                else canvas.drawRect(left, bounds.top, left + visible, bounds.bottom, paint)
                start += length
            }
        }
    }
}
