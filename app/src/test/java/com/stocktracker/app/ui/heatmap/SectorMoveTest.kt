package com.stocktracker.app.ui.heatmap

import com.stocktracker.app.data.remote.HeatmapTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectorMoveTest {

    @Test fun `a sector's move is weighted by company value, like its area`() {
        // One giant down 1%, three small names up 3%: the block is mostly red, so it reads down.
        val members = listOf(
            HeatmapTile(symbol = "BIG", size = 900.0, value = -1.0),
            HeatmapTile(symbol = "A", size = 30.0, value = 3.0),
            HeatmapTile(symbol = "B", size = 30.0, value = 3.0),
            HeatmapTile(symbol = "C", size = 40.0, value = 3.0),
        )
        assertEquals(-0.6, sectorMove(members)!!, 1e-9)
    }

    @Test fun `no usable size is no figure, not zero`() {
        assertNull(sectorMove(emptyList()))
        assertNull(sectorMove(listOf(HeatmapTile(symbol = "X", size = 0.0, value = 2.0))))
    }
}
