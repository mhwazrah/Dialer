package com.mhw.phone.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric does not rasterise faithfully, so the tests record the circles the drawable asks the canvas to draw
 * and read the animation clock; they do not look at pixels.
 */
@RunWith(RobolectricTestRunner::class)
class CallBackdropDrawableTest {
    private class Circle(val x: Float, val y: Float, val radius: Float, val alpha: Int)

    // Records the avatar rings (the dark palette's ring colour) apart from the bubbles' circles
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val circles = ArrayList<Circle>()
        var bubbleCircles = 0
        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
            if (paint.color and 0xFFFFFF == 0x7FB2E5) circles += Circle(cx, cy, radius, paint.alpha) else bubbleCircles++
        }
    }

    private lateinit var context: Context
    private lateinit var drawable: CallBackdropDrawable

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        drawable = CallBackdropDrawable(context, light = false).apply { setBounds(0, 0, 400, 800) }
    }

    private fun record(): RecordingCanvas =
        RecordingCanvas(Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)).also { drawable.draw(it) }

    private fun drawn(): List<Circle> = record().circles

    private fun elapsedMs(): Long {
        val field = CallBackdropDrawable::class.java.getDeclaredField("elapsedMs")
        field.isAccessible = true
        return field.getLong(drawable)
    }

    private fun field(name: String) = CallBackdropDrawable::class.java.getDeclaredField(name).apply { isAccessible = true }

    // Stands in for the animator's ticks (idling Robolectric's looper would spin on a running TimeAnimator)
    private fun advance(ms: Long) = field("elapsedMs").setLong(drawable, elapsedMs() + ms)

    @Test
    fun drawsWithoutThrowingBeforeAndAfterBoundsAndRings() {
        drawn()
        drawable.setRings(200f, 200f, 60f, 400f)
        drawn()
        drawable.setRinging(true)
        drawn()
        drawable.setRinging(false)
        drawn()
    }
    @Test
    fun drawingWithEmptyBoundsDoesNotThrow() {
        drawable.setBounds(0, 0, 0, 0)
        drawn()
    }

    @Test
    fun noRingsUntilRingingStarts() {
        drawable.setRings(200f, 200f, 60f, 400f)
        assertTrue(drawn().isEmpty())
    }

    @Test
    fun noRingsWithoutARadius() {
        drawable.setRinging(true)
        drawable.setRings(200f, 200f, 0f, 400f)
        assertTrue(drawn().isEmpty())
    }

    @Test
    fun ringingDrawsCirclesCentredOnTheAvatar() {
        drawable.setRings(150f, 250f, 60f, 500f)
        drawable.setRinging(true)
        val circles = drawn()
        assertTrue(circles.isNotEmpty())
        circles.forEach {
            assertEquals(150f, it.x, 0f)
            assertEquals(250f, it.y, 0f)
            assertTrue(it.radius >= 60f)
        }
    }

    @Test
    fun stopRingingRemovesTheRings() {
        drawable.setRings(150f, 250f, 60f, 500f)
        drawable.setRinging(true)
        assertTrue(drawn().isNotEmpty())
        drawable.setRinging(false)
        assertTrue(drawn().isEmpty())
    }

    @Test
    fun ringGrowthIsCappedAtSixtyPercentWhenThereIsRoom() {
        drawable.setRings(200f, 200f, 50f, 2000f)
        drawable.setRinging(true)
        drawn().forEach { assertTrue("radius ${it.radius}", it.radius <= 50f * 1.6f + 0.01f) }
    }

    @Test
    fun ringGrowthStopsAtTheName() {
        // 70 px from the centre to the name, radius 50: the circle may grow to 70 and no further
        drawable.setRings(200f, 200f, 50f, 270f)
        drawable.setRinging(true)
        advance(1000)
        val circles = drawn()
        assertTrue(circles.isNotEmpty())
        circles.forEach { assertTrue("bottom ${it.y + it.radius}", it.y + it.radius <= 270f + 0.01f) }
    }

    @Test
    fun noGrowthWhenTheNameIsAlreadyWithinTheAvatar() {
        drawable.setRings(200f, 200f, 50f, 230f)
        drawable.setRinging(true)
        advance(500)
        drawn().forEach { assertEquals(50f, it.radius, 0.001f) }
    }

    @Test
    fun noGrowthWhenTheNameIsAboveTheCentre() {
        drawable.setRings(200f, 200f, 50f, 100f)
        drawable.setRinging(true)
        drawn().forEach { assertEquals(50f, it.radius, 0.001f) }
    }

    @Test
    fun stoppingKeepsTheClockSoNothingSnapsBack() {
        drawable.setAnimating(true)
        advance(1000)
        val atStop = elapsedMs()
        assertTrue(atStop > 0)
        drawable.setAnimating(false)
        drawn()
        assertEquals(atStop, elapsedMs())
    }

    @Test
    fun restartingResumesFromTheKeptClock() {
        drawable.setAnimating(true)
        advance(1000)
        drawable.setAnimating(false)
        val atStop = elapsedMs()
        drawable.setAnimating(true)
        assertEquals(atStop, elapsedMs())
        assertEquals(atStop, field("clockAtStart").getLong(drawable))
        drawable.setAnimating(false)
    }

    @Test
    fun bubblesAreDrawnWhetherRingingOrNot() {
        val idle = record().bubbleCircles
        assertTrue(idle >= 6) // several bubbles, each a fill, rim and highlight
        drawable.setRinging(true)
        assertEquals(idle, record().bubbleCircles)
    }

    @Test
    fun lightThemeDrawsTheSameScene() {
        drawable = CallBackdropDrawable(context, light = true).apply { setBounds(0, 0, 400, 800) }
        drawable.setRings(200f, 200f, 60f, 400f)
        drawable.setRinging(true)
        val canvas = record()
        assertTrue(canvas.bubbleCircles > 0) // light rings use another colour, so they count here too
    }

    @Test
    fun startAndStopAreIdempotent() {
        drawable.setRinging(false) // not ringing: nothing to do
        drawable.setRinging(true)
        drawable.setRinging(true)
        advance(300)
        drawable.setRinging(false)
        drawable.setRinging(false)
        assertTrue(drawn().isEmpty())
    }

    @Test
    fun alphaAndColorFilterAreIgnoredAndTheSurfaceIsOpaque() {
        drawable.alpha = 10
        drawable.colorFilter = null
        @Suppress("DEPRECATION")
        assertEquals(PixelFormat.OPAQUE, drawable.opacity)
    }
}
