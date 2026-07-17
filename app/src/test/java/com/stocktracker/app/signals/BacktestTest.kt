package com.stocktracker.app.signals

import com.stocktracker.app.data.model.PricePoint
import org.junit.Assert.assertEquals
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
        assertTrue(result.winRatePct in 0.0..100.0)
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
