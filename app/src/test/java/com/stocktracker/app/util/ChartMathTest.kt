package com.stocktracker.app.util

import com.stocktracker.app.data.model.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {

    @Test fun `rebases to percent change from first point`() {
        val pts = listOf(
            PricePoint(1, 100.0),
            PricePoint(2, 110.0),
            PricePoint(3, 90.0),
        )
        val pct = pts.asPercentChange().map { it.price }
        assertEquals(0.0, pct[0], 1e-9)
        assertEquals(10.0, pct[1], 1e-9)
        assertEquals(-10.0, pct[2], 1e-9)
    }

    @Test fun `empty series is unchanged`() {
        assertEquals(emptyList<PricePoint>(), emptyList<PricePoint>().asPercentChange())
    }

    @Test fun `formats percent change with sign`() {
        assertEquals("+3.42%", formatPercentChange(3.421))
        assertEquals("-2.00%", formatPercentChange(-2.0))
        assertEquals("+0.00%", formatPercentChange(0.0))
    }

    @Test fun `sma nulls until the window fills, then averages`() {
        val v = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val sma = simpleMovingAverage(v, 3)
        assertEquals(null, sma[0])
        assertEquals(null, sma[1])
        assertEquals(2.0, sma[2]!!, 1e-9)  // (1+2+3)/3
        assertEquals(3.0, sma[3]!!, 1e-9)  // (2+3+4)/3
        assertEquals(4.0, sma[4]!!, 1e-9)  // (3+4+5)/3
    }

    @Test fun `sma all null when period exceeds data`() {
        assertEquals(listOf<Double?>(null, null), simpleMovingAverage(listOf(1.0, 2.0), 5))
    }

    @Test fun `ema seeds with sma then smooths`() {
        val ema = exponentialMovingAverage(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertEquals(null, ema[1])
        assertEquals(2.0, ema[2]!!, 1e-9) // SMA seed (1+2+3)/3
        assertEquals(3.0, ema[3]!!, 1e-9) // 4*0.5 + 2*0.5
        assertEquals(4.0, ema[4]!!, 1e-9)
    }

    @Test fun `rsi is 100 when only gains, 0 when only losses, else in range`() {
        val up = (1..20).map { it.toDouble() }
        assertEquals(100.0, rsi(up, 14)[14]!!, 1e-9)
        val down = (1..20).map { (21 - it).toDouble() }
        assertEquals(0.0, rsi(down, 14)[14]!!, 1e-9)
        val mixed = listOf(1.0, 2.0, 1.5, 3.0, 2.0, 4.0, 3.0, 5.0, 4.0, 6.0, 5.0, 7.0, 6.0, 8.0, 7.0, 9.0)
        val r = rsi(mixed, 14)[14]!!
        assertTrue("RSI in [0,100]", r in 0.0..100.0)
    }

    @Test fun `stochastic k stays within 0 to 100`() {
        val v = listOf(1.0, 3.0, 2.0, 5.0, 4.0, 6.0, 5.0, 8.0, 7.0, 9.0, 8.0, 11.0, 10.0, 12.0, 11.0, 14.0)
        val (k, d) = stochastic(v, 14, 3)
        assertEquals(null, k[12])
        val kv = k[14]!!
        assertTrue("K in [0,100]", kv in 0.0..100.0)
        assertTrue("D computed once enough K", d[15] != null)
    }

    @Test fun `macd histogram equals line minus signal and prefixes are null`() {
        val v = (1..60).map { it.toDouble() + (it % 5) }
        val m = macd(v)
        assertEquals(v.size, m.macd.size)
        assertEquals(null, m.macd[0]) // not enough data early
        for (i in v.indices) {
            val line = m.macd[i]; val sig = m.signal[i]; val h = m.histogram[i]
            if (line != null && sig != null) assertEquals(line - sig, h!!, 1e-9)
        }
    }
}
