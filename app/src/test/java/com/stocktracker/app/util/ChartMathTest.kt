package com.stocktracker.app.util

import com.stocktracker.app.data.model.PricePoint
import org.junit.Assert.assertEquals
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
}
