package com.stocktracker.app.util

import com.stocktracker.app.data.model.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden values for Wilder's RMA, True Range and ATR against Pine Script's reference semantics:
 *
 *     ta.tr(true) = max(high - low, |high - close[1]|, |low - close[1]|), high - low on the first bar
 *     ta.rma(x, n) = alpha * x + (1 - alpha) * rma[1], alpha = 1/n, seeded with SMA(x, n)
 *     ta.atr(n) = ta.rma(ta.tr(true), n)
 *
 * The trap these exist to catch is the RMA-as-EMA(2n-1) substitution. The recursion coefficients are
 * identical — 2 / ((2n - 1) + 1) = 1/n — so the two agree on how much each new bar moves the average
 * and disagree permanently on where the average started. TradingView's own DMI documentation calls
 * the smoothing an "Exponential Moving Average" while its shipped Pine uses ta.rma, so implementing
 * from the prose gets a different indicator from implementing from the code.
 */
class WilderAtrTest {

    private companion object {
        const val DAY = 86_400_000L

        /**
         *   i | high | low | close | true range
         *   0 |  12  |  8  |  10   | 4   (no previous close, so high - low)
         *   1 |  13  |  9  |  11   | 4   max(4, |13-10|=3, |9-10|=1)
         *   2 |  14  |  7  |  12   | 7   max(7, |14-11|=3, |7-11|=4)
         *   3 |  15  | 10  |  11   | 5   max(5, |15-12|=3, |10-12|=2)
         *   4 |  20  | 11  |  19   | 9   max(9, |20-11|=9, |11-11|=0)
         */
        val BARS = listOf(
            bar(0, high = 12.0, low = 8.0, close = 10.0),
            bar(1, high = 13.0, low = 9.0, close = 11.0),
            bar(2, high = 14.0, low = 7.0, close = 12.0),
            bar(3, high = 15.0, low = 10.0, close = 11.0),
            bar(4, high = 20.0, low = 11.0, close = 19.0),
        )
        val EXPECTED_TR = listOf(4.0, 4.0, 7.0, 5.0, 9.0)

        fun bar(i: Int, high: Double, low: Double, close: Double) =
            PricePoint(i * DAY, close, high = high, low = low)
    }

    @Test fun `true range matches the hand-computed Pine values`() {
        val tr = trueRange(BARS)
        assertEquals(EXPECTED_TR.size, tr.size)
        EXPECTED_TR.forEachIndexed { i, want -> assertEquals("bar $i", want, tr[i]!!, 1e-9) }
    }

    @Test fun `the first bar falls back to high minus low rather than to null`() {
        // This is what Pine's ta.tr(true) — the form ta.atr is defined over — does.
        assertEquals(4.0, trueRange(BARS)[0]!!, 1e-9)
    }

    /** A gap beyond the bar's own range is the case a naive `high - low` gets wrong. */
    @Test fun `a gap down makes the previous close the true low`() {
        val gapped = listOf(
            bar(0, high = 100.0, low = 98.0, close = 99.0),
            bar(1, high = 90.0, low = 88.0, close = 89.0),  // gapped well below yesterday
        )
        // max(90-88=2, |90-99|=9, |88-99|=11) = 11, not the 2 the bar's own range shows.
        assertEquals(11.0, trueRange(gapped)[1]!!, 1e-9)
    }

    @Test fun `rma seeds on the SMA of the first window and then decays at one over n`() {
        val rma = wilderRma(EXPECTED_TR, 3)

        assertNull(rma[0])
        assertNull(rma[1])
        assertEquals("seed = mean(4, 4, 7)", 5.0, rma[2]!!, 1e-9)
        assertEquals("1/3*5 + 2/3*5", 5.0, rma[3]!!, 1e-9)
        assertEquals("1/3*9 + 2/3*5", 3.0 + 10.0 / 3.0, rma[4]!!, 1e-9)
    }

    @Test fun `atr is rma of true range`() {
        val a = atr(BARS, 3)
        val expected = wilderRma(EXPECTED_TR, 3)
        expected.indices.forEach { i ->
            if (expected[i] == null) assertNull(a[i]) else assertEquals(expected[i]!!, a[i]!!, 1e-9)
        }
    }

    /**
     * The substitution this module must not make. Both start from the same true ranges; they differ
     * in where the series begins and in its level from then on.
     */
    @Test fun `rma is not the same series as an EMA of 2n minus 1`() {
        val n = 3
        val rma = wilderRma(EXPECTED_TR, n)
        val emaSub = exponentialMovingAverage(EXPECTED_TR, 2 * n - 1)

        assertNotNull("RMA starts at index n-1", rma[n - 1])
        assertNull("the EMA substitute has produced nothing there yet", emaSub[n - 1])

        // Where both exist they still disagree: EMA(5) seeds on mean(4,4,7,5,9) = 5.8.
        assertEquals(5.8, emaSub[4]!!, 1e-9)
        assertEquals(3.0 + 10.0 / 3.0, rma[4]!!, 1e-9)
        assertTrue("the two must not be interchangeable", kotlin.math.abs(emaSub[4]!! - rma[4]!!) > 0.5)
    }

    @Test fun `for n of 14 the two start 13 bars apart`() {
        val v = (1..60).map { (it % 7).toDouble() + 1.0 }
        val rma = wilderRma(v, 14)
        val emaSub = exponentialMovingAverage(v, 27)
        assertEquals(13, v.indices.first { rma[it] != null })
        assertEquals(26, v.indices.first { emaSub[it] != null })
    }

    // --- absence, not substitution -------------------------------------------------------------

    @Test fun `a bar with no extremes has no true range`() {
        val bars = listOf(
            bar(0, 12.0, 8.0, 10.0),
            PricePoint(1 * DAY, 11.0),  // close only
            bar(2, 14.0, 7.0, 12.0),
        )
        val tr = trueRange(bars)
        assertNotNull(tr[0])
        assertNull("a close is not a range", tr[1])
        assertNotNull(tr[2])
    }

    @Test fun `an incoherent bar with high below low has no true range`() {
        val bars = listOf(bar(0, high = 8.0, low = 12.0, close = 10.0))
        assertNull(trueRange(bars)[0])
    }

    @Test fun `a close-only series produces no ATR at all`() {
        val closes = (0 until 40).map { PricePoint(it * DAY, 100.0 + it) }
        assertTrue(atr(closes, 14).all { it == null })
    }

    /**
     * A hole restarts the average rather than being smoothed over. Carrying it across would average
     * two runs of bars that are not adjacent in time.
     */
    @Test fun `rma restarts after a hole instead of bridging it`() {
        val v = listOf<Double?>(1.0, 1.0, 1.0, null, 5.0, 5.0, 5.0, 5.0)
        val rma = wilderRma(v, 3)
        assertEquals("seeded on the first run", 1.0, rma[2]!!, 1e-9)
        assertNull("the hole itself", rma[3])
        assertNull("re-seeding, one value in", rma[4])
        assertNull("re-seeding, two values in", rma[5])
        assertEquals("re-seeded on mean(5, 5, 5)", 5.0, rma[6]!!, 1e-9)
    }

    // --- bar-size labelling --------------------------------------------------------------------

    /**
     * "ATR 14" means fourteen DAYS of range on a 1Y chart and fourteen MINUTES of it on a 1D chart,
     * and every stop distance elsewhere in the app is denominated in days. The label is read off the
     * plotted bars because the requested range does not fix the bar size on its own — ChartRange.MONTH
     * is 30-minute bars for a stock and daily bars for crypto.
     */
    @Test fun `bar spacing is read from the data and named`() {
        fun series(stepMs: Long) = (0 until 10).map { PricePoint(it * stepMs, 100.0) }
        assertEquals("1m", barSpacingLabel(medianBarSpacingMs(series(60_000L))))
        assertEquals("5m", barSpacingLabel(medianBarSpacingMs(series(300_000L))))
        assertEquals("30m", barSpacingLabel(medianBarSpacingMs(series(1_800_000L))))
        assertEquals("1h", barSpacingLabel(medianBarSpacingMs(series(3_600_000L))))
        assertEquals("1d", barSpacingLabel(medianBarSpacingMs(series(DAY))))
        assertEquals("1wk", barSpacingLabel(medianBarSpacingMs(series(7 * DAY))))
    }

    @Test fun `weekend gaps do not stretch a daily series into something else`() {
        // Mon-Fri then a 3-day gap, twice over: the mean would read ~1.4d, the median reads 1d.
        val steps = listOf(1L, 1L, 1L, 1L, 3L, 1L, 1L, 1L, 1L, 3L, 1L)
        var t = 0L
        val pts = ArrayList<PricePoint>()
        pts.add(PricePoint(0, 100.0))
        steps.forEach { d -> t += d * DAY; pts.add(PricePoint(t, 100.0)) }
        assertEquals("1d", barSpacingLabel(medianBarSpacingMs(pts)))
    }

    @Test fun `an unrecognisable spacing is left unnamed rather than guessed`() {
        assertNull(barSpacingLabel(7_777_777L))
        assertNull(barSpacingLabel(null))
        assertNull(barSpacingLabel(0L))
    }

    @Test fun `too few bars to measure a spacing reports none`() {
        assertNull(medianBarSpacingMs(listOf(PricePoint(0, 1.0), PricePoint(DAY, 1.0))))
    }
}
