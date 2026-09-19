package com.stocktracker.app.ui.portfolio

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MONEY-7: the Portfolio screen marks a holding as covered-call ("income") eligible without
 * fetching an option premium — a share-count fact, not a quote. [Holding.coveredCallEligible] must
 * reuse the exact >=100-FREE-shares gate DetailScreen's CoveredCallCard already uses, including the
 * [Holding.committedShares] exclusion (MONEY-3): shares already promised away by an OPEN short call
 * are not free, and a holding that already sold its 100 shares away must not be offered a second one.
 */
class HoldingCoveredCallEligibleTest {

    private fun stock(symbol: String = "AAPL") =
        Asset(symbol = symbol, type = AssetType.STOCK, displayName = symbol)

    private fun crypto(symbol: String = "BTC") =
        Asset(symbol = symbol, type = AssetType.CRYPTO, displayName = symbol, coinGeckoId = "bitcoin")

    private fun holding(asset: Asset, shares: Double, committedShares: Int = 0) = Holding(
        asset = asset, shares = shares, price = 100.0, value = shares * 100.0, dayChange = 0.0,
        committedShares = committedShares,
    )

    @Test fun `100 free shares of a stock is eligible -- the exact boundary`() {
        assertTrue(holding(stock(), shares = 100.0).coveredCallEligible)
    }

    @Test fun `99 shares is not eligible -- one short of a contract`() {
        assertFalse(holding(stock(), shares = 99.0).coveredCallEligible)
    }

    @Test fun `well over 100 shares is eligible`() {
        assertTrue(holding(stock(), shares = 250.0).coveredCallEligible)
    }

    @Test fun `shares already committed to an open short call are excluded from eligibility`() {
        // The MONEY-3 incident, re-checked from the Portfolio screen's own gate: 100 shares held, one
        // covered call already sold against all of them, so 0 are free -- must not be offered again.
        assertFalse(holding(stock(), shares = 100.0, committedShares = 100).coveredCallEligible)
    }

    @Test fun `a partial commitment can still leave enough free shares to be eligible`() {
        // 250 held, one contract (100 sh) already sold away -> 150 free, still clears the 100 bar.
        assertTrue(holding(stock(), shares = 250.0, committedShares = 100).coveredCallEligible)
    }

    @Test fun `a partial commitment can drop a holding below the bar`() {
        // 150 held, one contract already sold away -> 50 free, below 100.
        assertFalse(holding(stock(), shares = 150.0, committedShares = 100).coveredCallEligible)
    }

    @Test fun `over-committed shares (more promised than held) read as ineligible, not a crash`() {
        // Should never happen (the store keeps these in sync), but the gate must fail safe rather
        // than throw on a negative free-share count.
        assertFalse(holding(stock(), shares = 100.0, committedShares = 200).coveredCallEligible)
    }

    @Test fun `fractional shares truncate toward zero, same as DetailScreen's own gate`() {
        assertFalse(holding(stock(), shares = 99.9).coveredCallEligible)
        assertTrue(holding(stock(), shares = 100.9).coveredCallEligible)
    }

    @Test fun `crypto is never eligible regardless of quantity -- there is no options chain`() {
        assertFalse(holding(crypto(), shares = 10_000.0).coveredCallEligible)
    }

    @Test fun `zero shares is not eligible`() {
        assertFalse(holding(stock(), shares = 0.0).coveredCallEligible)
    }
}
