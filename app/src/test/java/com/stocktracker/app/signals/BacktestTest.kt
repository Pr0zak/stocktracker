package com.stocktracker.app.signals

import com.stocktracker.app.data.model.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BacktestTest {

    @Test fun `returns null when there is not enough history`() {
        assertNull(Backtest.run(ramp(30, 100.0, 1.0)))
    }

    @Test fun `buy-and-hold return is measured from the warmup bar`() {
        // Linear +1/day: warmup=maSlow=50, last index=119 → (219/150 - 1) = 46%.
        val result = Backtest.run(ramp(120, 100.0, 1.0))!!
        assertEquals(46.0, result.buyHoldReturnPct, 1e-6)
    }

    @Test fun `all metrics land in valid ranges`() {
        val result = Backtest.run(oscillating(240))
        assertNotNull(result)
        result!!
        assertTrue(result.bars > 0)
        assertTrue(result.trades >= 0)
        assertTrue(result.maxDrawdownPct >= 0.0)
        assertTrue(result.exposurePct in 0.0..100.0)
        // Nullable by design: no simulated trades is not a 0% win rate. When it is present it is
        // the rate its own win COUNT implies, so a thin sample can be printed as counts (SWT-9).
        result.winRatePct?.let { assertTrue(it in 0.0..100.0) }
        assertTrue(result.wins in 0..result.trades)
        assertEquals(
            result.winRatePct,
            if (result.trades > 0) result.wins.toDouble() / result.trades * 100.0 else null,
        )
        assertTrue(result.strategyReturnPct.isFinite())
        assertTrue(result.buyHoldReturnPct.isFinite())
        assertEquals(result.strategyReturnPct - result.buyHoldReturnPct, result.edgeVsBuyHoldPct, 1e-9)
    }

    @Test fun `backtest is deterministic`() {
        val series = oscillating(200)
        assertEquals(Backtest.run(series), Backtest.run(series))
    }

    @Test fun `a flat market with fees never beats buy-and-hold`() {
        // No trend to capture; any trades only bleed fees, so the edge can't be positive.
        val result = Backtest.run(flat(120, 100.0))!!
        assertTrue("edge was ${result.edgeVsBuyHoldPct}", result.edgeVsBuyHoldPct <= 1e-9)
    }

    @Test fun `a zero price never yields NaN or Infinity metrics`() {
        val series = oscillating(120).toMutableList().apply { this[60] = this[60].copy(price = 0.0) }
        Backtest.run(series)?.let { r ->
            assertTrue(r.strategyReturnPct.isFinite())
            assertTrue(r.buyHoldReturnPct.isFinite())
            assertTrue(r.maxDrawdownPct.isFinite())
        }
    }

    /**
     * SWT-9: the simulated win rate never appears without its live counterpart, and the live
     * counterpart does not exist. Borrowing the analyst's forward scorecard to fill the slot would be
     * a different subject wearing this one's label, so the honest output is a stated absence.
     */
    @Test fun `the backtested win rate is paired with an explicitly absent forward record`() {
        val result = Backtest.run(oscillating(240))!!
        val pair = result.winRatePair()
        if (result.trades == 0) {
            // Nothing simulated, so nothing to qualify — and no pair of two absences to draw.
            assertNull(pair)
            return
        }
        assertNotNull(pair)
        assertEquals(result.trades, pair!!.backtest.sample!!.n)
        assertEquals(result.wins, pair.backtest.sample!!.hits)
        // Absent, with a reason — never a zero and never a blank.
        assertNull(pair.forward.sample)
        assertTrue(pair.forwardReading is com.stocktracker.app.data.model.PairedStat.Reading.Absent)
        assertFalse(pair.forward.absentReason.isNullOrBlank())
    }

    @Test fun `a chart the rule never traded has no rate to pair`() {
        val result = Backtest.run(flat(120, 100.0))!!
        if (result.trades == 0) {
            assertNull(result.winRatePct) // not 0.0 — the rule never traded, it did not lose
            assertNull(result.winRatePair())
        }
    }

    companion object {
        const val DAY = 86_400_000L
        fun ramp(n: Int, start: Double, step: Double): List<PricePoint> =
            (0 until n).map { PricePoint(it * DAY, start + it * step) }
        fun flat(n: Int, price: Double): List<PricePoint> =
            (0 until n).map { PricePoint(it * DAY, price) }
        /** A deterministic mean-reverting wave — the kind of series a MR-flavored signal can trade. */
        fun oscillating(n: Int): List<PricePoint> =
            (0 until n).map { PricePoint(it * DAY, 100.0 + 15.0 * sin(it * 2.0 * PI / 40.0)) }
    }
}
