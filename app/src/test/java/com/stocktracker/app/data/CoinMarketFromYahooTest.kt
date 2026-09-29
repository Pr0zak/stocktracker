package com.stocktracker.app.data

import com.stocktracker.app.data.model.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoinMarketFromYahooTest {
    private val now = 1_790_700_000_000L
    private fun bars(vararg p: Pair<Long, Double>) = p.map { (ageMin, price) -> PricePoint(now - ageMin * 60_000L, price) }

    @Test fun `the move is measured from the first bar a day back`() {
        val m = coinMarketFromYahoo("bitcoin", "btc", "Bitcoin", bars(1440L to 80_000.0, 5L to 82_000.0), now)!!
        assertEquals("BTC", m.symbol)
        assertEquals(82_000.0, m.price, 1e-9)
        assertEquals(2_000.0, m.change, 1e-9)
        assertEquals(2.5, m.changePercent, 1e-9)
    }

    @Test fun `an old tape is not a current price`() {
        assertNull(coinMarketFromYahoo("bitcoin", "BTC", "Bitcoin", bars(2000L to 80_000.0, 600L to 81_000.0), now))
    }

    @Test fun `one bar is not a move`() {
        assertNull(coinMarketFromYahoo("bitcoin", "BTC", "Bitcoin", bars(5L to 80_000.0), now))
    }
}
