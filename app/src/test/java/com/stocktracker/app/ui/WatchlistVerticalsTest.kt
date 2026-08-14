package com.stocktracker.app.ui

import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.ui.watchlist.WatchlistVerticals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sorting the watchlist into sector verticals.
 *
 * The interesting decisions here are all about what a MISSING answer means, and those are exactly
 * the ones invisible on a screenshot. A row still appears either way; only the heading differs, and
 * the wrong heading is a confident claim the app is not entitled to make.
 */
class WatchlistVerticalsTest {

    private fun vertical(
        symbol: String,
        type: AssetType = AssetType.STOCK,
        isEtf: Boolean = false,
        sectors: Map<String, String?> = emptyMap(),
    ) = WatchlistVerticals.verticalFor(type, symbol, isEtf, sectors)

    @Test
    fun `a known sector files the row under that sector`() {
        assertEquals("Technology", vertical("AAPL", sectors = mapOf("AAPL" to "Technology")))
    }

    @Test
    fun `classified-with-no-sector and never-looked-up are different headings`() {
        // The server returning null means it LOOKED and the security has no sector -- true of ETFs
        // and warrants at Yahoo. A key absent from the map means nobody has asked yet, which happens
        // on first launch and throughout a backend outage.
        //
        // Merging these would render an outage as a confident classification: every stock the user
        // owns would sit under "Other" with nothing on screen to say the app had simply not checked.
        assertEquals(WatchlistVerticals.OTHER, vertical("GME.WS", sectors = mapOf("GME.WS" to null)))
        assertEquals(WatchlistVerticals.UNCLASSIFIED, vertical("GME.WS", sectors = emptyMap()))
    }

    @Test
    fun `a blank sector string is treated as no sector rather than a heading named empty`() {
        assertEquals(WatchlistVerticals.OTHER, vertical("XYZ", sectors = mapOf("XYZ" to "   ")))
    }

    @Test
    fun `asset type and ETF-ness win over the sector map`() {
        // Both are known locally and both are the more useful split. An S&P fund filed under
        // Financial Services would be technically defensible and practically useless.
        assertEquals(WatchlistVerticals.CRYPTO, vertical("BTC", type = AssetType.CRYPTO))
        assertEquals(
            WatchlistVerticals.ETFS,
            vertical("SPY", isEtf = true, sectors = mapOf("SPY" to "Financial Services")),
        )
    }

    @Test
    fun `sector lookup is case-insensitive on the symbol`() {
        assertEquals("Healthcare", vertical("lly", sectors = mapOf("LLY" to "Healthcare")))
    }

    @Test
    fun `favorites are lifted into one pinned section and never shown twice`() {
        // Showing a favourite under its sector as well would double every count on screen and leave
        // the user checking whether two rows for one ticker meant two positions.
        // Technology deliberately keeps TWO unstarred rows to LLY's one, so the section order below
        // is decided by size rather than by the name tie-break -- the point under test is the
        // lifting, and a 1-1 tie would make this assertion pass or fail for an unrelated reason.
        val rows = listOf("AAPL" to true, "MSFT" to false, "NVDA" to false,
                          "LLY" to true, "UNH" to false)
        val sectors = mapOf("AAPL" to "Technology", "MSFT" to "Technology", "NVDA" to "Technology",
                            "LLY" to "Healthcare", "UNH" to "Healthcare")
        val grouped = WatchlistVerticals.group(
            rows = rows,
            isFavorite = { it.second },
            verticalOf = { vertical(it.first, sectors = sectors) },
        )
        assertEquals(listOf(WatchlistVerticals.FAVORITES, "Technology", "Healthcare"), grouped.keys.toList())
        assertEquals(listOf("AAPL", "LLY"), grouped[WatchlistVerticals.FAVORITES]!!.map { it.first })
        assertEquals(listOf("MSFT", "NVDA"), grouped["Technology"]!!.map { it.first })
        assertEquals(1, grouped.values.flatten().count { it.first == "AAPL" })
    }

    @Test
    fun `no favorites means no favorites section at all`() {
        // Not an empty heading. Nothing on the watchlist is starred by default, so on first run this
        // is the normal case and a permanent empty section would read as a bug.
        val grouped = WatchlistVerticals.group(
            rows = listOf("AAPL" to false),
            isFavorite = { it.second },
            verticalOf = { vertical(it.first, sectors = mapOf("AAPL" to "Technology")) },
        )
        assertTrue(WatchlistVerticals.FAVORITES !in grouped.keys)
    }

    @Test
    fun `real sectors sort largest-first and the catch-alls sink to the bottom`() {
        // Largest-first because the list is read top down and the biggest concentration is the one
        // worth seeing without scrolling. ETFs/Crypto/Other/Unclassified are statements about the
        // shape of our data rather than about what a company does, so they read as an appendix --
        // even when, as here, one of them is the biggest group on the screen.
        val counts = mapOf(
            WatchlistVerticals.ETFS to 9,
            "Healthcare" to 2,
            "Technology" to 5,
            WatchlistVerticals.UNCLASSIFIED to 1,
            "Energy" to 2,
            WatchlistVerticals.CRYPTO to 3,
        )
        assertEquals(
            listOf("Technology", "Energy", "Healthcare",
                   WatchlistVerticals.ETFS, WatchlistVerticals.CRYPTO, WatchlistVerticals.UNCLASSIFIED),
            WatchlistVerticals.sectionOrder(counts),
        )
    }

    @Test
    fun `equal-sized sectors break ties by name so the order does not reshuffle on a price tick`() {
        val order = WatchlistVerticals.sectionOrder(
            mapOf("Utilities" to 3, "Energy" to 3, "Materials" to 3))
        assertEquals(listOf("Energy", "Materials", "Utilities"), order)
    }
}
