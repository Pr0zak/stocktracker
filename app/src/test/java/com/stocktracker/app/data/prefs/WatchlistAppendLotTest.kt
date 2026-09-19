package com.stocktracker.app.data.prefs

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Lot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching rule behind [WatchlistStore.addLot] (MONEY-2) — the ONE path a recorded journal fill
 * and an exercised call both append a purchase lot through. Pulled into a pure function so it is
 * testable without a Context/DataStore.
 */
class WatchlistAppendLotTest {

    private val aapl = Asset(symbol = "AAPL", type = AssetType.STOCK, displayName = "Apple Inc.")
    private val btc = Asset(symbol = "BTC", type = AssetType.CRYPTO, displayName = "Bitcoin", coinGeckoId = "bitcoin")

    @Test fun `appends a lot to the matching stock by exact symbol`() {
        val lot = Lot(shares = 10.0, costPerShare = 150.0, acquiredDateIso = "2025-01-01")
        val (next, matched) = WatchlistStore.appendLot(listOf(aapl, btc), "AAPL", lot)
        assertTrue(matched)
        assertEquals(listOf(lot), next.first { it.symbol == "AAPL" }.lots)
        assertEquals(emptyList<Lot>(), next.first { it.symbol == "BTC" }.lots) // untouched
    }

    @Test fun `matching is case-insensitive`() {
        val lot = Lot(shares = 1.0, costPerShare = 100.0, acquiredDateIso = "2025-01-01")
        val (next, matched) = WatchlistStore.appendLot(listOf(aapl), "aapl", lot)
        assertTrue(matched)
        assertEquals(listOf(lot), next.single().lots)
    }

    @Test fun `a trailing -USD is stripped so a journal crypto fill lands on the plain ticker`() {
        // The verdict journal stores crypto symbols Yahoo-style for replay ("BTC-USD"), which must
        // still resolve to the plain "BTC" watchlist entry.
        val lot = Lot(shares = 0.25, costPerShare = 60000.0, acquiredDateIso = "2025-02-02")
        val (next, matched) = WatchlistStore.appendLot(listOf(aapl, btc), "BTC-USD", lot)
        assertTrue(matched)
        assertEquals(listOf(lot), next.first { it.symbol == "BTC" }.lots)
    }

    @Test fun `appending preserves any existing lots rather than replacing them`() {
        val existing = aapl.copy(lots = listOf(Lot(shares = 5.0, costPerShare = 120.0, acquiredDateIso = "2024-01-01")))
        val newLot = Lot(shares = 5.0, costPerShare = 140.0, acquiredDateIso = "2025-01-01")
        val (next, matched) = WatchlistStore.appendLot(listOf(existing), "AAPL", newLot)
        assertTrue(matched)
        val updated = next.single()
        assertEquals(2, updated.lots.size)
        assertEquals(10.0, updated.shares!!, 0.0001)
    }

    @Test fun `no matching symbol leaves the list untouched and reports no match`() {
        val (next, matched) = WatchlistStore.appendLot(
            listOf(aapl),
            "NVDA",
            Lot(shares = 1.0, costPerShare = 500.0, acquiredDateIso = "2025-01-01"),
        )
        assertFalse(matched)
        assertEquals(listOf(aapl), next)
    }
}
