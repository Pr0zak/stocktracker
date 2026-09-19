package com.stocktracker.app.ui

import com.stocktracker.app.ui.components.priceChartDescription
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PLAT-4: PriceChart is a bare Canvas -- no semantics, so the whole plot is invisible to a screen
 * reader without this. [priceChartDescription] assembles the same summary a sighted user gets for
 * free by glancing at the price line and the "Today" change line above it.
 */
class ChartAccessibilityTest {

    @Test fun `dollar mode with a day's change`() {
        assertEquals(
            "AAPL price chart, 1M, currently \$150.23, ▲ +\$2.10 (+1.42%)",
            priceChartDescription(
                symbol = "AAPL",
                rangeLabel = "1M",
                percentMode = false,
                currentValueText = "$150.23",
                changeLine = "▲ +$2.10 (+1.42%)",
            ),
        )
    }

    @Test fun `percent mode names itself as a percent-change chart`() {
        assertEquals(
            "AAPL percent change chart, 1Y, currently +12.4%",
            priceChartDescription(
                symbol = "AAPL",
                rangeLabel = "1Y",
                percentMode = true,
                currentValueText = "+12.4%",
            ),
        )
    }

    @Test fun `no change line (equity curve, benchmark) is left out cleanly`() {
        assertEquals(
            "S&P 500 price chart, ALL, currently 100.0",
            priceChartDescription(
                symbol = "S&P 500",
                rangeLabel = "ALL",
                percentMode = false,
                currentValueText = "100.0",
                changeLine = null,
            ),
        )
    }

    @Test fun `a blank change line is treated the same as null`() {
        assertEquals(
            "VIX price chart, 1D, currently 14.2",
            priceChartDescription(
                symbol = "VIX",
                rangeLabel = "1D",
                percentMode = false,
                currentValueText = "14.2",
                changeLine = "  ",
            ),
        )
    }
}
