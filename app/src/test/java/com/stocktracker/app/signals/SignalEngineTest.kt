package com.stocktracker.app.signals

import com.stocktracker.app.data.model.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SignalEngineTest {

    private val engine = SignalEngine()

    @Test fun `too-short series has no signal`() {
        assertNull(engine.evaluate(ramp(5, 100.0, +1.0)))
    }

    @Test fun `oversold decline reads RSI bullish and trend bearish`() {
        val result = engine.evaluate(ramp(80, 200.0, -1.0))!!
        val rsi = result.components.first { it.name == "RSI" }
        val trend = result.components.first { it.name == "Trend" }
        assertTrue("RSI should be bullish (oversold) on a steady decline, was ${rsi.score}", rsi.score > 0.0)
        assertTrue("Trend should be bearish (below MA) on a decline, was ${trend.score}", trend.score < 0.0)
    }

    @Test fun `score always stays within 0 to 100`() {
        for (series in listOf(ramp(80, 200.0, -1.0), ramp(80, 100.0, +1.0), flat(80, 100.0))) {
            val s = engine.evaluate(series)!!.score
            assertTrue("score $s out of range", s in 0..100)
        }
    }

    @Test fun `evaluation is deterministic`() {
        val series = ramp(80, 100.0, +0.7)
        assertEquals(engine.evaluate(series), engine.evaluate(series))
    }

    @Test fun `high VIX dampens conviction and flags the regime`() {
        val series = ramp(80, 200.0, -1.0)
        val calm = engine.evaluate(series, vix = 12.0)!!
        val stormy = engine.evaluate(series, vix = 40.0)!!
        assertNull(calm.regimeNote)
        assertNotNull(stormy.regimeNote)
        assertTrue("high-VIX net should be no larger than calm net", abs(stormy.net) <= abs(calm.net) + 1e-9)
    }

    /** With only RSI weighted, a deep-oversold series must resolve to the max bullish label, and a
     *  deep-overbought series to the max bearish — proves the component → net → score → label chain. */
    @Test fun `single-indicator extremes drive the label ends`() {
        val onlyRsi = SignalWeights(
            rsi = 1.0, macd = 0.0, priceVsMa = 0.0, maCross = 0.0,
            bollinger = 0.0, stochastic = 0.0, volume = 0.0, relativeStrength = 0.0,
            minComponents = 1,
        )
        val e = SignalEngine(onlyRsi)
        val oversold = e.evaluate(ramp(80, 300.0, -1.0))!!
        val overbought = e.evaluate(ramp(80, 50.0, +1.0))!!
        assertEquals(100, oversold.score)
        assertEquals(SignalLabel.STRONG_BUY, oversold.label)
        assertEquals(0, overbought.score)
        assertEquals(SignalLabel.STRONG_SELL, overbought.label)
    }

    @Test fun `too-few components before warmup returns a neutral hold`() {
        val ctx = engine.prepare(ramp(80, 100.0, +1.0).map { it.price })
        val early = engine.evaluateAt(ctx, 5) // no indicator is warmed up this early
        assertEquals(50, early.score)
        assertEquals(SignalLabel.HOLD, early.label)
        assertTrue(early.regimeNote!!.contains("Not enough"))
    }

    @Test fun `a flat market reads a neutral hold, not an extreme`() {
        val r = engine.evaluate(flat(80, 100.0))!!
        assertEquals(50, r.score)
        assertEquals(SignalLabel.HOLD, r.label)
    }

    @Test fun `benchmark aligns by calendar day`() {
        val pts = listOf(PricePoint(0 * DAY, 1.0), PricePoint(1 * DAY, 2.0), PricePoint(2 * DAY, 3.0))
        val bench = listOf(PricePoint(0 * DAY, 10.0), PricePoint(2 * DAY, 30.0)) // day 1 missing
        val aligned = alignByDay(pts, bench)
        assertEquals(10.0, aligned[0]!!, 1e-9)
        assertNull(aligned[1])
        assertEquals(30.0, aligned[2]!!, 1e-9)
    }

    companion object {
        const val DAY = 86_400_000L
        /** [n] daily bars starting at [start], each stepping by [step]. */
        fun ramp(n: Int, start: Double, step: Double): List<PricePoint> =
            (0 until n).map { PricePoint(it * DAY, start + it * step) }
        fun flat(n: Int, price: Double): List<PricePoint> =
            (0 until n).map { PricePoint(it * DAY, price) }
    }
}
