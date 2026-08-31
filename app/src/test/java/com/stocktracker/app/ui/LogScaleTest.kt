package com.stocktracker.app.ui

import com.stocktracker.app.ui.components.axisTickValues
import com.stocktracker.app.ui.components.logScaleUsable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * The logarithmic price axis and the price ticks that finally put a number on it.
 *
 * The refusal path is the one that matters. `logScaleUsable` is handed the COMPOSED bounds — the ones
 * that already fold in the cost line, the 200-week line and every overlay — because Bollinger's lower
 * band is `mid - 2*sd` and goes at or below zero on a volatile sub-$5 ticker, and the AI-analyst
 * levels are arbitrary numbers from a backend. Testing the price series alone would pass and then
 * take the log of a negative.
 */
class LogScaleTest {

    @Test fun `a positive range is usable`() {
        assertTrue(logScaleUsable(min = 10.0, max = 100.0, requested = true))
    }

    @Test fun `not requested is never usable`() {
        assertFalse(logScaleUsable(min = 10.0, max = 100.0, requested = false))
    }

    @Test fun `a composed bound at or below zero refuses`() {
        // A $4 stock with Bollinger enabled: the lower band reaches -0.4 and joins the bounds.
        assertFalse("a negative composed min must refuse", logScaleUsable(-0.4, 6.2, true))
        assertFalse("zero is not a log-able floor either", logScaleUsable(0.0, 6.2, true))
    }

    @Test fun `a degenerate range refuses rather than dividing by a zero span`() {
        assertFalse(logScaleUsable(50.0, 50.0, true))
        assertFalse(logScaleUsable(50.0, 49.0, true))
    }

    // --- tick values -----------------------------------------------------------------------------

    @Test fun `linear ticks span the range inclusively and evenly`() {
        val t = axisTickValues(min = 100.0, max = 200.0, log = false, n = 4)
        assertEquals(listOf(100.0, 133.333333, 166.666667, 200.0).size, t.size)
        assertEquals(100.0, t.first(), 1e-6)
        assertEquals(200.0, t.last(), 1e-6)
        for (i in 1 until t.size) {
            assertEquals(t[1] - t[0], t[i] - t[i - 1], 1e-6)
        }
    }

    /** The whole point of a log axis: equal spacing on screen is an equal RATIO, not an equal delta. */
    @Test fun `log ticks are evenly spaced in the log, so successive ratios are constant`() {
        val t = axisTickValues(min = 10.0, max = 1000.0, log = true, n = 3)
        assertEquals(listOf(10.0, 100.0, 1000.0).size, t.size)
        assertEquals(10.0, t[0], 1e-9)
        assertEquals(100.0, t[1], 1e-6)
        assertEquals(1000.0, t[2], 1e-6)
        assertEquals(t[1] / t[0], t[2] / t[1], 1e-9)
    }

    @Test fun `log ticks stay evenly spaced in log space for an awkward range`() {
        val t = axisTickValues(min = 183.0, max = 214.0, log = true, n = 4)
        assertEquals(4, t.size)
        val gaps = (1 until t.size).map { ln(t[it]) - ln(t[it - 1]) }
        gaps.forEach { assertEquals(gaps[0], it, 1e-9) }
    }

    /**
     * A 1-2-5x10^n "nice number" generator produces at most one tick inside a typical single-stock
     * window, which is why this one spans the range instead. Pinned so the approach is not quietly
     * swapped back.
     */
    @Test fun `a narrow range still yields every requested tick, all distinct`() {
        val t = axisTickValues(min = 183.0, max = 214.0, log = false, n = 4)
        assertEquals(4, t.size)
        assertEquals(4, t.distinct().size)
        assertTrue(t.all { it in 183.0..214.0 })
    }

    @Test fun `a degenerate range yields no ticks rather than a repeated label`() {
        assertTrue(axisTickValues(50.0, 50.0, false, 4).isEmpty())
        assertTrue(axisTickValues(50.0, 49.0, false, 4).isEmpty())
        assertTrue(axisTickValues(10.0, 100.0, false, 1).isEmpty())
    }

    @Test fun `log ticks refuse a non-positive floor rather than taking the log of it`() {
        assertTrue(axisTickValues(-5.0, 100.0, log = true, n = 4).isEmpty())
        assertTrue(axisTickValues(0.0, 100.0, log = true, n = 4).isEmpty())
    }

    /**
     * The claim that makes the feature worth having, stated as arithmetic: on a linear axis a 40%
     * drawdown from $30 occupies less height than a 10% one from $300, and on a log axis it occupies
     * more — which is the correct reading.
     */
    @Test fun `log height tracks percentage where linear height tracks dollars`() {
        val min = 10.0
        val max = 400.0

        fun linearFrac(v: Double) = (v - min) / (max - min)
        fun logFrac(v: Double) = (ln(v) - ln(min)) / (ln(max) - ln(min))

        val bigPctSmallPrice = linearFrac(30.0) - linearFrac(18.0)   // -40% from $30
        val smallPctBigPrice = linearFrac(300.0) - linearFrac(270.0) // -10% from $300
        assertTrue(
            "on a linear axis the larger percentage draws smaller",
            bigPctSmallPrice < smallPctBigPrice,
        )

        val logBig = logFrac(30.0) - logFrac(18.0)
        val logSmall = logFrac(300.0) - logFrac(270.0)
        assertTrue("on a log axis the larger percentage draws larger", logBig > logSmall)
    }
}
