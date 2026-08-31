package com.stocktracker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widget sparkline's baseline must participate in the vertical scale, not just be drawn on top
 * of it. A previous close outside the day's range is the whole point of the line — a gap-down that
 * recovers — and that is exactly the case a clamped baseline would misplace.
 *
 * Only the bounds are covered: `render()` needs `Bitmap` and `Canvas`, which cannot be instantiated
 * in a JVM unit test without Robolectric.
 */
class SparklineBaselineTest {

    private val intraday = listOf(98.0, 99.5, 101.0, 100.25)

    @Test
    fun `no baseline leaves the series bounds untouched`() {
        val (lo, hi) = SparklineRenderer.verticalBounds(intraday, null)
        assertEquals(98.0, lo, 0.0)
        assertEquals(101.0, hi, 0.0)
    }

    @Test
    fun `a previous close above the day's high widens the top`() {
        // Gapped down and recovered but never reclaimed yesterday's close: the line rises all day
        // and still finishes under the baseline. Clamping would draw it at the top of the plot and
        // assert the opposite.
        val (lo, hi) = SparklineRenderer.verticalBounds(intraday, 104.0)
        assertEquals(98.0, lo, 0.0)
        assertEquals(104.0, hi, 0.0)
    }

    @Test
    fun `a previous close below the day's low widens the bottom`() {
        val (lo, hi) = SparklineRenderer.verticalBounds(intraday, 95.0)
        assertEquals(95.0, lo, 0.0)
        assertEquals(101.0, hi, 0.0)
    }

    @Test
    fun `a previous close inside the day's range changes nothing`() {
        val (lo, hi) = SparklineRenderer.verticalBounds(intraday, 100.0)
        assertEquals(98.0, lo, 0.0)
        assertEquals(101.0, hi, 0.0)
    }
}
