package com.stocktracker.app.ui

import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.ui.detail.benchmarkPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The S&P overlay used to bucket the benchmark by calendar day (last write wins), which collapsed an
 * intraday series to one price. On a 1D range every ticker point then resolved to that same price
 * and the chart drew a flat 0% line — not a missing comparison but an invented one, asserting the
 * index went nowhere all session.
 */
class BenchmarkOverlayTest {

    private val hour = 3_600_000L
    private val day = 86_400_000L

    private fun pts(start: Long, step: Long, prices: List<Double>) =
        prices.mapIndexed { i, p -> PricePoint(start + i * step, p) }

    @Test
    fun `an intraday overlay tracks the benchmark instead of flattening to zero`() {
        val t0 = 1_780_000_000_000L
        val ticker = pts(t0, hour, listOf(100.0, 101.0, 102.0, 103.0))
        val bench = pts(t0, hour, listOf(200.0, 202.0, 201.0, 206.0))

        val out = benchmarkPercent(ticker, bench)
        assertEquals(4, out.size)
        assertTrue("overlay is entirely null", out.any { it != null })
        assertTrue("overlay collapsed to a flat line", out.filterNotNull().distinct().size > 1)
        assertEquals(0.0, out[0]!!, 1e-9)          // rebased to 0% at the start
        assertEquals(1.0, out[1]!!, 1e-6)          // 202/200 - 1
        assertEquals(3.0, out[3]!!, 1e-6)          // 206/200 - 1
    }

    @Test
    fun `a daily overlay still aligns`() {
        val t0 = 1_780_000_000_000L
        val ticker = pts(t0, day, listOf(100.0, 101.0, 102.0))
        val bench = pts(t0, day, listOf(50.0, 51.0, 49.0))
        val out = benchmarkPercent(ticker, bench)
        assertEquals(0.0, out[0]!!, 1e-9)
        assertEquals(2.0, out[1]!!, 1e-6)
        assertEquals(-2.0, out[2]!!, 1e-6)
    }

    @Test
    fun `a benchmark that genuinely never moves draws nothing rather than a fake flat line`() {
        val t0 = 1_780_000_000_000L
        val ticker = pts(t0, hour, listOf(100.0, 101.0, 102.0))
        val bench = pts(t0, hour, listOf(200.0, 200.0, 200.0))
        assertTrue(benchmarkPercent(ticker, bench).all { it == null })
    }

    @Test
    fun `a benchmark far from the ticker's window is not matched`() {
        val t0 = 1_780_000_000_000L
        val ticker = pts(t0, hour, listOf(100.0, 101.0))
        val bench = pts(t0 - 30 * day, day, listOf(200.0, 201.0, 202.0))   // a month earlier
        assertTrue(benchmarkPercent(ticker, bench).all { it == null })
    }

    @Test
    fun `too little benchmark data yields no overlay`() {
        val t0 = 1_780_000_000_000L
        assertTrue(benchmarkPercent(pts(t0, hour, listOf(1.0, 2.0)), emptyList()).all { it == null })
    }
}
