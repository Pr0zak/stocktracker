package com.stocktracker.app.ui.heatmap

import androidx.compose.ui.graphics.luminance
import com.stocktracker.app.data.remote.HeatmapTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DipTierTest {

    @Test fun `the top tier the server sends as 5 is the big dip, not an overflow`() {
        assertEquals(DipTier.BIG, HeatmapTile(value = 5.0, dip = "mega_dip").tier())
        assertEquals(DipTier.BIG, HeatmapTile(value = 5.0).tier())
        assertEquals(DipTier.NONE, HeatmapTile(value = 0.0).tier())
        assertEquals(DipTier.DOWN_10, HeatmapTile(value = 2.0, dip = "pullback_10").tier())
    }

    @Test fun `a deeper dip is a brighter tile`() {
        val amber = DipTier.entries.filter { it != DipTier.NONE }.map { it.fill.luminance() }
        for (i in 1 until amber.size) assertTrue("step $i is not darker", amber[i] < amber[i - 1])
    }

    @Test fun `only buy and sell are calls`() {
        assertEquals("BUY", HeatmapTile(signal = "buy").call())
        assertEquals("SELL", HeatmapTile(signal = "sell").call())
        assertNull(HeatmapTile(signal = "hold").call())
        assertNull(HeatmapTile(signal = null).call())
    }
}
