package com.stocktracker.app.ui

import com.stocktracker.app.data.remote.HeatmapTile
import com.stocktracker.app.ui.heatmap.heatmapTileDescription
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PLAT-4: below ~40dp a heat-map tile draws no text at all -- colour is the only thing carrying the
 * move, on the one screen in this app where that's true (see the "Area = ... colour = ..." legend
 * HeatmapScreen prints under the map). [heatmapTileDescription] is what a screen reader says
 * instead, for every tile regardless of whether it was big enough to draw a label.
 */
class HeatmapAccessibilityTest {

    @Test fun `an up mover on the price scale`() {
        val t = HeatmapTile(symbol = "AAPL", value = 1.23, scale = "price")
        assertEquals("AAPL, up 1.2%", heatmapTileDescription(t))
    }

    @Test fun `a down mover on the price scale`() {
        val t = HeatmapTile(symbol = "XOM", value = -2.5, scale = "price")
        assertEquals("XOM, down 2.5%", heatmapTileDescription(t))
    }

    @Test fun `a move inside the dead zone reads flat, not a false direction`() {
        val t = HeatmapTile(symbol = "KO", value = 0.01, scale = "price")
        assertEquals("KO, flat 0.0%", heatmapTileDescription(t))
    }

    @Test fun `signal scale reports the drawdown, not a price move`() {
        val t = HeatmapTile(symbol = "TSLA", scale = "signal", pctOff52wHigh = 34.0)
        assertEquals("TSLA, 34% off its 52-week high", heatmapTileDescription(t))
    }

    @Test fun `signal scale with no drawdown figure still names the tile`() {
        val t = HeatmapTile(symbol = "GME", scale = "signal", pctOff52wHigh = null)
        assertEquals("GME, flagged by signals", heatmapTileDescription(t))
    }
}
