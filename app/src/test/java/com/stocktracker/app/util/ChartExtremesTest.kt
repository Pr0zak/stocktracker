package com.stocktracker.app.util

import com.stocktracker.app.data.model.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wider chart range must never report a higher low than a narrower range inside it.
 *
 * Observed on GME 2026-08-11: the 1D chart marked a low of $18.59, the 1W chart $18.70, and the 1M
 * chart $18.66 — for windows that each contain that same trading day. Nothing was wrong with the
 * data. Each range asks Yahoo for a different bar size (1D → 1-minute, 1W → 5-minute, 1M →
 * 30-minute) and the markers were picked from the CLOSE series, which has no memory of what traded
 * inside a bar. Coarsen the bars and the wick disappears; the reported low rises.
 *
 * Bar extremes nest by construction — a 5-minute bar's low IS the lowest of its five 1-minute lows —
 * so reading high/low instead of close makes the ranges agree. These tests aggregate a fine series
 * into a coarse one exactly the way an exchange does, and assert the property both ways round.
 */
class ChartExtremesTest {

    private fun bar(t: Long, close: Double, high: Double? = null, low: Double? = null) =
        PricePoint(epochMs = t, price = close, high = high, low = low)

    /** Roll [size] consecutive fine bars into one coarse bar, the way a real aggregator would. */
    private fun coarsen(fine: List<PricePoint>, size: Int): List<PricePoint> =
        fine.chunked(size).map { chunk ->
            PricePoint(
                epochMs = chunk.first().epochMs,
                price = chunk.last().price,                       // the coarse bar CLOSES on the last
                high = chunk.maxOf { it.barHigh() },
                low = chunk.minOf { it.barLow() },
            )
        }

    // One-minute bars for a session whose true low ($18.59) is a wick in the middle of a bar that
    // closed well above it — the shape that produced the reported discrepancy.
    private val oneMinute = listOf(
        bar(0, 18.75, high = 18.80, low = 18.72),
        bar(1, 18.88, high = 18.94, low = 18.74),
        bar(2, 18.70, high = 18.72, low = 18.59),   // <- the session low, traded but not closed on
        bar(3, 18.71, high = 18.74, low = 18.68),
        bar(4, 18.78, high = 18.79, low = 18.70),
        bar(5, 18.76, high = 18.78, low = 18.73),
    )

    // ------------------------------------------------------------------ the reported bug

    @Test
    fun `the old close-only rule loses the low as the bars coarsen`() {
        // Documents the defect rather than the fix: this is what the chart used to do, and why the
        // 1W view could claim a low the 1D view had already disproved.
        val fineCloseLow = oneMinute.minOf { it.price }
        val coarseCloseLow = coarsen(oneMinute, 3).minOf { it.price }
        assertEquals(18.70, fineCloseLow, 1e-9)
        assertEquals(18.70, coarseCloseLow, 1e-9)
        // ...and both are ABOVE the price that actually traded.
        assertTrue(fineCloseLow > 18.59)
    }

    @Test
    fun `bar extremes give the same low at every bar size`() {
        val fine = oneMinute[lowIndexIn(oneMinute, 0, oneMinute.lastIndex)].barLow()
        val coarse3 = coarsen(oneMinute, 3).let { it[lowIndexIn(it, 0, it.lastIndex)].barLow() }
        val coarse6 = coarsen(oneMinute, 6).let { it[lowIndexIn(it, 0, it.lastIndex)].barLow() }
        assertEquals(18.59, fine, 1e-9)
        assertEquals(18.59, coarse3, 1e-9)
        assertEquals(18.59, coarse6, 1e-9)
    }

    @Test
    fun `bar extremes give the same high at every bar size`() {
        val fine = oneMinute[highIndexIn(oneMinute, 0, oneMinute.lastIndex)].barHigh()
        val coarse3 = coarsen(oneMinute, 3).let { it[highIndexIn(it, 0, it.lastIndex)].barHigh() }
        assertEquals(18.94, fine, 1e-9)
        assertEquals(18.94, coarse3, 1e-9)
    }

    @Test
    fun `a wider window can only ever match or beat the narrower one it contains`() {
        // The property the user actually stated. Widening the window may find a NEW extreme; it may
        // never lose one.
        val week = oneMinute + listOf(
            bar(6, 19.50, high = 19.79, low = 19.40),   // a higher high earlier in the week
            bar(7, 19.10, high = 19.20, low = 19.00),
        )
        val dayLow = oneMinute[lowIndexIn(oneMinute, 0, oneMinute.lastIndex)].barLow()
        val weekLow = week[lowIndexIn(week, 0, week.lastIndex)].barLow()
        val dayHigh = oneMinute[highIndexIn(oneMinute, 0, oneMinute.lastIndex)].barHigh()
        val weekHigh = week[highIndexIn(week, 0, week.lastIndex)].barHigh()
        assertTrue("wider window lost the low", weekLow <= dayLow)
        assertTrue("wider window lost the high", weekHigh >= dayHigh)
        assertEquals(19.79, weekHigh, 1e-9)
    }

    // ------------------------------------------------------------------ sources without OHLC

    @Test
    fun `a close-only source falls back to the close rather than dropping the marker`() {
        // CoinGecko returns no OHLC. Crypto charts must still mark a high and a low.
        val closesOnly = listOf(bar(0, 100.0), bar(1, 92.0), bar(2, 105.0))
        assertEquals(1, lowIndexIn(closesOnly, 0, 2))
        assertEquals(2, highIndexIn(closesOnly, 0, 2))
        assertEquals(92.0, closesOnly[1].barLow(), 1e-9)
    }

    @Test
    fun `a partially populated series mixes bar extremes and closes without crashing`() {
        val mixed = listOf(bar(0, 100.0), bar(1, 99.0, high = 101.0, low = 90.0), bar(2, 98.0))
        assertEquals(1, lowIndexIn(mixed, 0, 2))
        assertEquals(90.0, mixed[lowIndexIn(mixed, 0, 2)].barLow(), 1e-9)
    }

    // ------------------------------------------------------------------ the visible sub-range

    @Test
    fun `zooming reports the extremes of what is on screen, not of the whole series`() {
        assertEquals(18.68, oneMinute[lowIndexIn(oneMinute, 3, 5)].barLow(), 1e-9)
        assertEquals(18.79, oneMinute[highIndexIn(oneMinute, 3, 5)].barHigh(), 1e-9)
    }

    @Test
    fun `an empty or inverted range returns no index instead of index zero`() {
        // -1 rather than 0: index 0 would silently mark a bar that is not on screen.
        assertEquals(-1, lowIndexIn(emptyList(), 0, 0))
        assertEquals(-1, highIndexIn(oneMinute, 4, 2))
    }

    // ------------------------------------------------------------------ percent mode

    @Test
    fun `percent mode rebases the bar extremes along with the close`() {
        // Leaving high/low in dollars beside a percentage close would blow the y-axis out by orders
        // of magnitude and format "$18.59" through the percent formatter.
        val pct = listOf(bar(0, 100.0, high = 110.0, low = 90.0), bar(1, 120.0, high = 130.0, low = 115.0))
            .asPercentChange()
        assertEquals(0.0, pct[0].price, 1e-9)
        assertEquals(10.0, pct[0].high!!, 1e-9)
        assertEquals(-10.0, pct[0].low!!, 1e-9)
        assertEquals(30.0, pct[1].high!!, 1e-9)
        // And the ordering the chart relies on still holds after rebasing.
        assertTrue(pct.all { it.barLow() <= it.price && it.price <= it.barHigh() })
    }

    @Test
    fun `percent mode leaves a close-only series close-only`() {
        val pct = listOf(bar(0, 50.0), bar(1, 75.0)).asPercentChange()
        assertEquals(null, pct[1].high)
        assertEquals(50.0, pct[1].barHigh(), 1e-9)   // falls back to the rebased close
    }
}
