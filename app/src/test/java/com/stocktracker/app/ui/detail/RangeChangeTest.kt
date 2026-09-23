package com.stocktracker.app.ui.detail

import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The chart's colour and range line describe the range on screen, not today's move. */
class RangeChangeTest {
    private fun pts(vararg p: Double) = p.mapIndexed { i, v -> PricePoint(i.toLong(), v) }

    @Test fun `a month that rose reads as up even if the last day fell`() {
        val (chg, pct) = RangeChange.of(pts(481.86, 517.78, 501.61, 498.00))!!
        assertEquals(16.14, chg, 1e-9)
        assertEquals(3.35, pct, 0.01)
        assertEquals("▲ +3.35% over the past month", RangeChange.line(pts(481.86, 498.00), ChartRange.MONTH))
    }

    @Test fun `a fall uses a real minus sign`() {
        assertEquals("▼ −10.00% over the past year", RangeChange.line(pts(100.0, 90.0), ChartRange.YEAR))
    }

    @Test fun `1D has no range line, and an unmeasurable range has none`() {
        assertNull(RangeChange.line(pts(1.0, 2.0), ChartRange.DAY))
        assertNull(RangeChange.of(pts(5.0)))
        assertNull(RangeChange.of(pts(0.0, 5.0)))
        assertNull(RangeChange.of(emptyList()))
    }
}
