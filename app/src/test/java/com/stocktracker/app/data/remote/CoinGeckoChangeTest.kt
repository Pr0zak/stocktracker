package com.stocktracker.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinGeckoChangeTest {
    @Test fun `a dollar change that disagrees with the percentage is recomputed from it`() {
        // The 2026-09-28 ETH row: -0.44 dollars beside +0.05%.
        val (change, pct) = consistentChange(2694.89, -0.44, 0.05)
        assertEquals(0.05, pct, 1e-9)
        assertTrue("the arrow must point the way the percentage does", change > 0.0)
        assertEquals(1.347, change, 0.01)
    }

    @Test fun `the dollar change always reproduces the percentage`() {
        // Live CoinGecko ETH on 2026-09-28: $13.12 on a $2,670.87 base is +0.491%, not the +0.469% it
        // reported beside it. The two fields drift apart even when their signs agree.
        val price = 2683.99
        val (change, pct) = consistentChange(price, 13.12, 0.46912)
        assertEquals(0.46912, pct, 1e-9)
        assertEquals(pct, change / (price - change) * 100.0, 1e-9)
    }

    @Test fun `only a dollar change still gives a matching percentage`() {
        val (change, pct) = consistentChange(110.0, 10.0, null)
        assertEquals(10.0, change, 1e-9)
        assertEquals(10.0, pct, 1e-9)
    }

    @Test fun `nothing known is no move`() {
        assertEquals(0.0 to 0.0, consistentChange(100.0, null, null))
        assertEquals(0.0 to 0.0, consistentChange(100.0, null, Double.NaN))
    }
}
