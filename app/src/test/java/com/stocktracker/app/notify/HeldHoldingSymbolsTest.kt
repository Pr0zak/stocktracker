package com.stocktracker.app.notify

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Lot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MONEY-7: [heldHoldingSymbols] is what [AiDailyBriefNotifier] sends the daily brief as `holdings` —
 * bare symbols only (never shares/cost/lot dates), filtered to actual POSITIONS (>0 shares), with
 * crypto sent as `<SYM>-USD` to match how the backend's own watchlist already names it.
 */
class HeldHoldingSymbolsTest {

    private fun stock(symbol: String, shares: Double?) = Asset(
        symbol = symbol, type = AssetType.STOCK, displayName = symbol,
        lots = shares?.let { listOf(Lot(shares = it, costPerShare = 10.0)) } ?: emptyList(),
    )

    private fun crypto(symbol: String, shares: Double?, coinGeckoId: String = symbol.lowercase()) = Asset(
        symbol = symbol, type = AssetType.CRYPTO, displayName = symbol, coinGeckoId = coinGeckoId,
        lots = shares?.let { listOf(Lot(shares = it, costPerShare = 10.0)) } ?: emptyList(),
    )

    @Test fun `a stock holding is sent as a bare uppercased symbol`() {
        assertEquals(listOf("AAPL"), heldHoldingSymbols(listOf(stock("aapl", 10.0))))
    }

    @Test fun `a crypto holding is sent with the -USD suffix, matching the backend's own watchlist`() {
        assertEquals(listOf("BTC-USD"), heldHoldingSymbols(listOf(crypto("BTC", 0.5))))
    }

    @Test fun `a watched-but-not-held asset (0 or null shares) is excluded`() {
        assertEquals(
            emptyList<String>(),
            heldHoldingSymbols(listOf(stock("ZERO", 0.0), stock("NONE", null))),
        )
    }

    @Test fun `mixed stock and crypto holdings, watched-only assets dropped`() {
        val assets = listOf(
            stock("AAPL", 10.0),
            stock("WATCHED", null),
            crypto("BTC", 0.5),
            crypto("ETH", 0.0),
        )
        assertEquals(listOf("AAPL", "BTC-USD"), heldHoldingSymbols(assets))
    }

    @Test fun `an empty book sends nothing`() {
        assertEquals(emptyList<String>(), heldHoldingSymbols(emptyList()))
    }
}
