package com.stocktracker.app.util

import com.stocktracker.app.data.model.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden values for the Stochastic oscillator against Pine Script's reference semantics:
 *
 *     ta.stoch(close, high, low, n) = 100 * (close - lowest(low, n)) / (highest(high, n) - lowest(low, n))
 *
 * The window's extremes come from the BAR HIGHS AND LOWS, not from the closes. Taking them from the
 * closes makes %K read exactly 0 whenever the current close is the window's lowest close and exactly
 * 100 whenever it is the highest — which, measured over 1-2 years of daily bars on AAPL, NVDA, MSFT,
 * AMD, SPY and BTC-USD, happens on roughly 30% of bars, against 0-1% for a true stochastic. It is a
 * binary flag wearing an oscillator's label, and SignalEngine.stochComp scores it as maximum
 * conviction every time it pins.
 *
 * The pre-existing test in ChartMathTest only asserted %K stayed inside 0..100 and that %D
 * eventually appeared, both of which hold identically for the broken version. These are the
 * assertions that can tell the two apart.
 */
class StochasticPineTest {

    private companion object {
        const val DAY = 86_400_000L

        /**
         * Five bars, hand-computed, chosen so the window's lowest LOW and highest HIGH differ from
         * the lowest and highest CLOSE at every warmed index — and so the close-basis version pins
         * to both 100 and 0 inside three bars.
         *
         *   i | close | high | low
         *   0 |  10   |  12  |  8
         *   1 |  11   |  13  |  9
         *   2 |  12   |  14  |  7
         *   3 |  11   |  15  | 10
         *   4 |  13   |  16  | 11
         */
        val BARS = listOf(
            bar(0, close = 10.0, high = 12.0, low = 8.0),
            bar(1, close = 11.0, high = 13.0, low = 9.0),
            bar(2, close = 12.0, high = 14.0, low = 7.0),
            bar(3, close = 11.0, high = 15.0, low = 10.0),
            bar(4, close = 13.0, high = 16.0, low = 11.0),
        )

        fun bar(i: Int, close: Double, high: Double, low: Double) =
            PricePoint(i * DAY, close, high = high, low = low)

        fun closeOnly(i: Int, close: Double) = PricePoint(i * DAY, close)
    }

    @Test fun `percent K matches the hand-computed Pine values`() {
        val (k, _) = stochastic(BARS, period = 3, smoothD = 2)

        assertNull("index 0 is inside the warm-up", k[0])
        assertNull("index 1 is inside the warm-up", k[1])

        // i=2: bars 0..2 → lowest low 7, highest high 14, close 12 → 100 * 5/7
        assertEquals(100.0 * 5.0 / 7.0, k[2]!!, 1e-9)
        // i=3: bars 1..3 → lowest low 7, highest high 15, close 11 → 100 * 4/8
        assertEquals(50.0, k[3]!!, 1e-9)
        // i=4: bars 2..4 → lowest low 7, highest high 16, close 13 → 100 * 6/9
        assertEquals(100.0 * 6.0 / 9.0, k[4]!!, 1e-9)
    }

    @Test fun `percent D is the simple average of the last smoothD values of percent K`() {
        val (_, d) = stochastic(BARS, period = 3, smoothD = 2)

        assertNull("only one %K exists yet", d[2])
        assertEquals((100.0 * 5.0 / 7.0 + 50.0) / 2.0, d[3]!!, 1e-9)
        assertEquals((50.0 + 100.0 * 6.0 / 9.0) / 2.0, d[4]!!, 1e-9)
    }

    /**
     * The regression the golden values exist to catch. On the same three bars the close-basis
     * formula returns 100, 0, 100 — two saturations and a floor where the true oscillator reads
     * 71.4, 50.0 and 66.7. Nothing about the old assertions (range, eventual non-null %D) would have
     * noticed.
     */
    @Test fun `the close-basis answer is not merely different, it saturates`() {
        val (k, _) = stochastic(BARS, period = 3, smoothD = 2)
        val closes = BARS.map { it.price }

        for (i in 2..4) {
            val window = closes.subList(i - 2, i + 1)
            val lo = window.min()
            val hi = window.max()
            val closeBasis = if (hi > lo) 100.0 * (closes[i] - lo) / (hi - lo) else 50.0
            assertTrue(
                "close-basis %K at $i should pin to an extreme, was $closeBasis",
                closeBasis == 0.0 || closeBasis == 100.0,
            )
            assertTrue(
                "true-range %K at $i must not pin, was ${k[i]}",
                k[i]!! > 0.0 && k[i]!! < 100.0,
            )
        }
    }

    /**
     * A rising series is where the two disagree most cheaply: every new high close is the window's
     * highest close, so the close-basis version returns exactly 100 on every warmed bar forever.
     */
    @Test fun `a monotonic advance never pins the true-range oscillator`() {
        val rising = (0 until 40).map { bar(it, close = 100.0 + it, high = 101.0 + it, low = 99.0 + it) }
        val (k, _) = stochastic(rising, period = 14)

        val warmed = k.drop(13).filterNotNull()
        assertEquals(40 - 13, warmed.size)
        assertTrue("no warmed bar may read exactly 100", warmed.none { it >= 100.0 })
        // 100 * (close - (close-13-1)) / ((close+1) - (close-13-1)) = 100 * 14/15
        assertEquals(100.0 * 14.0 / 15.0, warmed.first(), 1e-9)
    }

    @Test fun `a flat window reads the midpoint rather than dividing by zero`() {
        val flat = (0 until 20).map { bar(it, close = 50.0, high = 50.0, low = 50.0) }
        val (k, _) = stochastic(flat, period = 14)
        assertEquals(50.0, k[19]!!, 1e-9)
    }

    // --- absence, not substitution -------------------------------------------------------------

    /**
     * CoinGecko's fallback path and the signals service's Webull history both return closes with no
     * bar extremes. `barHigh()`/`barLow()` fall back to the close, which is right for a high/low
     * MARKER and wrong for an oscillator: it would silently reinstate the close-basis formula under
     * a label claiming otherwise. Absent must stay absent.
     */
    @Test fun `a close-only series yields no oscillator at all`() {
        val closes = (0 until 30).map { closeOnly(it, 100.0 + (it % 7)) }
        val (k, d) = stochastic(closes, period = 14)
        assertTrue("every %K must be null on a close-only series", k.all { it == null })
        assertTrue("every %D must be null on a close-only series", d.all { it == null })
    }

    /**
     * One nulled bar is not a reason to drop the whole indicator, but it IS a reason to drop every
     * window that contains it — splicing across the hole would take extremes from two
     * non-adjacent runs.
     */
    @Test fun `a single incomplete bar nulls only the windows that contain it`() {
        // 40 bars, one of them extremes-less at index 20. A 14-bar window covers index 20 for every
        // i in 20..33, so 34 is the first index that can recover.
        val bars = (0 until 40).map {
            if (it == 20) closeOnly(it, 100.0 + it) else bar(it, 100.0 + it, 101.0 + it, 99.0 + it)
        }
        val (k, _) = stochastic(bars, period = 14)

        assertNotNull("the last window ending before the hole is unaffected", k[19])
        for (i in 20..33) assertNull("window at $i still contains the hole", k[i])
        assertNotNull("the first window clear of the hole recovers", k[34])
    }

    @Test fun `an incoherent bar with high below low is treated as missing`() {
        val bars = (0 until 30).map {
            if (it == 10) bar(it, close = 110.0, high = 99.0, low = 120.0)
            else bar(it, 100.0 + it, 101.0 + it, 99.0 + it)
        }
        val (k, _) = stochastic(bars, period = 14)
        assertNull("a window containing the incoherent bar must not produce a value", k[15])
    }

    @Test fun `a series shorter than the period produces nothing`() {
        val (k, d) = stochastic(BARS, period = 14)
        assertTrue(k.all { it == null })
        assertTrue(d.all { it == null })
    }
}
