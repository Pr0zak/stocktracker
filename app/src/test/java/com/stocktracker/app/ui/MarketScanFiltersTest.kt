package com.stocktracker.app.ui

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.ui.marketscan.MARKET_SCAN_LIMITS
import com.stocktracker.app.ui.marketscan.MARKET_SCAN_LIMIT_MAX
import com.stocktracker.app.ui.marketscan.MARKET_SCAN_SORTS
import com.stocktracker.app.ui.marketscan.MarketScanFilters
import com.stocktracker.app.ui.marketscan.RANKED_SCAN_METRICS
import com.stocktracker.app.ui.marketscan.SCAN_BOOL_FILTERS
import com.stocktracker.app.ui.marketscan.SCAN_FILTER_NAMES
import com.stocktracker.app.ui.marketscan.ScanRequest
import com.stocktracker.app.ui.marketscan.clampScanLimit
import com.stocktracker.app.ui.marketscan.isWatched
import com.stocktracker.app.ui.marketscan.scanMatchLine
import com.stocktracker.app.ui.marketscan.scanMetricFor
import com.stocktracker.app.ui.marketscan.scanResponseIsStale
import com.stocktracker.app.ui.marketscan.scanSortAscending
import com.stocktracker.app.ui.marketscan.scanSortBase
import com.stocktracker.app.ui.marketscan.scanSortMatches
import com.stocktracker.app.ui.marketscan.scanSortMetricMatches
import com.stocktracker.app.ui.marketscan.scanSortParam
import com.stocktracker.app.ui.marketscan.watchedStockSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The market scan's controls, as pure data.
 *
 * Every one of these guards the same failure: a screen showing a list that does not answer the
 * question its own controls are displaying. A filter name the route refuses is a 422; a filter name
 * the client drops is silence; a stale response painted under new chips is worse than both, because
 * it looks like an answer.
 */
class MarketScanFiltersTest {

    private val rsi = "rsi14"
    private val adx = "adx14"

    // ------------------------------------------------------------------------ query parameters

    @Test
    fun `an empty filter set adds no parameters at all`() {
        val f = MarketScanFilters()
        assertTrue(f.isEmpty)
        assertEquals(emptyList<Pair<String, String>>(), f.queryParams())
        assertEquals("", f.summary())
        assertEquals(0, f.activeCount)
    }

    @Test
    fun `a filter set builds exactly the parameters the route accepts`() {
        val f = MarketScanFilters()
            .withBool("above_sma200", true)
            .withBound(rsi, min = 30.0, max = 70.0)
            .withBound("adr20_pct", min = 2.5, max = null)
        assertEquals(
            listOf(
                "above_sma200" to "true",
                "min_adr20_pct" to "2.5",
                "min_rsi14" to "30",
                "max_rsi14" to "70",
            ).sortedBy { it.first },
            f.queryParams().sortedBy { it.first },
        )
        // A whole-number bound goes out as "30", not "30.0" — both parse server-side, but the one
        // the user typed is the one that should come back at them in the chips.
        assertTrue(f.queryParams().contains("min_rsi14" to "30"))
    }

    @Test
    fun `min and max are separate parameters, in a fixed order`() {
        val f = MarketScanFilters().withBound(adx, 20.0, 40.0)
        assertEquals(listOf("min_adx14" to "20", "max_adx14" to "40"), f.queryParams())
    }

    @Test
    fun `a false boolean is sent as false, not omitted`() {
        // "not above the 200-day" is a real question. Dropping it because it is falsy would answer
        // a different one — the unfiltered market — under a chip that says otherwise.
        val f = MarketScanFilters().withBool("above_sma50", false)
        assertEquals(listOf("above_sma50" to "false"), f.queryParams())
    }

    @Test
    fun `every parameter name is one the server's vocabulary contains`() {
        // The route validates against scan_store.FILTER_NAMES = {min_,max_} x metric columns, plus
        // the three bare booleans, and 422s anything else. Mirrored here so a typo in a metric key
        // fails in this suite rather than on a phone.
        var f = MarketScanFilters()
        SCAN_BOOL_FILTERS.forEach { f = f.withBool(it.key, true) }
        RANKED_SCAN_METRICS.forEach { f = f.withBound(it.key, 1.0, 2.0) }
        val names = f.queryParams().map { it.first }
        assertEquals(names.size, names.toSet().size)
        names.forEach { assertTrue("$it is not in the server's filter vocabulary", it in SCAN_FILTER_NAMES) }
    }

    @Test
    fun `a bound under a name the route does not know never reaches the wire`() {
        // Belt and braces on the same rule: queryParams walks the DECLARED vocabulary, so a bound
        // stashed under a made-up key is unsendable rather than a 422 waiting to happen.
        val f = MarketScanFilters(bounds = mapOf("not_a_metric" to com.stocktracker.app.ui.marketscan.ScanBound(1.0, null)))
        assertEquals(emptyList<Pair<String, String>>(), f.queryParams())
    }

    @Test
    fun `a non-finite bound is refused rather than stored`() {
        // NaN is a 422 server-side, and a client that swallowed it would send a filter under which
        // every comparison is false — an empty cross-section produced by a typo, which on screen is
        // indistinguishable from "nothing in the market qualifies".
        val f = MarketScanFilters().withBound(rsi, Double.NaN, Double.POSITIVE_INFINITY)
        assertTrue(f.isEmpty)
        assertEquals(emptyList<Pair<String, String>>(), f.queryParams())
    }

    @Test
    fun `clearing a bound removes it`() {
        val f = MarketScanFilters().withBound(rsi, 30.0, null).withBound(rsi, null, null)
        assertTrue(f.isEmpty)
        assertEquals("", f.summary())
    }

    // --------------------------------------------------------------------------- the summary

    @Test
    fun `the summary names every active filter`() {
        val f = MarketScanFilters()
            .withBool("above_sma200", true)
            .withBool("ma_stacked", false)
            .withBound(rsi, 30.0, 70.0)
        val chips = f.chips()
        assertEquals(4, chips.size)
        assertEquals(4, f.activeCount)
        assertTrue(chips.contains("Above 200-day"))
        assertTrue(chips.contains("MAs not stacked"))
        assertTrue(chips.contains("RSI (14) ≥ 30"))
        assertTrue(chips.contains("RSI (14) ≤ 70"))
        val line = f.summary()
        assertTrue(line.contains("Above 200-day"))
        assertTrue(line.contains("RSI (14) ≤ 70"))
    }

    @Test
    fun `a false boolean says which side it is filtering for`() {
        // "above_sma50" as a chip label would leave the reader to guess which half they are seeing.
        assertEquals(listOf("Below 50-day"), MarketScanFilters().withBool("above_sma50", false).chips())
        assertEquals(listOf("Above 50-day"), MarketScanFilters().withBool("above_sma50", true).chips())
    }

    @Test
    fun `no filters means an empty summary, not the words 'no filters'`() {
        assertEquals("", MarketScanFilters().summary())
        assertEquals(emptyList<String>(), MarketScanFilters().chips())
    }

    // -------------------------------------------------------------------------- sort direction

    @Test
    fun `ascending is the minus prefix and descending is bare`() {
        assertEquals("-atr14_pct", scanSortParam("atr14_pct", ascending = true))
        assertEquals("atr14_pct", scanSortParam("atr14_pct", ascending = false))
        assertTrue(scanSortAscending("-rsi14"))
        assertFalse(scanSortAscending("rsi14"))
    }

    @Test
    fun `a sort round-trips against what the server says it applied`() {
        // appliedSort is what the rows BELONG to. The chip and the direction toggle both read it, so
        // both have to survive the prefix.
        val asked = scanSortParam("rsi14", ascending = true)
        val applied = "-rsi14"
        assertTrue(scanSortMatches(applied, asked))
        assertTrue(scanSortMatches(applied, "rsi14") == false)
        assertEquals("rsi14", scanSortBase(applied))
        assertTrue(scanSortAscending(applied))
    }

    @Test
    fun `the route's rel_strength alias is the same ordering as its column`() {
        // GET /market_scan defaults to sort=rel_strength and echoes that back; the column is
        // rel_strength_3mo. Compared as strings, the chip for the DEFAULT sort would never light and
        // the headline metric would render as nothing.
        assertTrue(scanSortMatches("rel_strength", "rel_strength_3mo"))
        assertTrue(scanSortMatches("-rel_strength", "-rel_strength_3mo"))
        assertFalse(scanSortMatches("rel_strength", "-rel_strength_3mo"))
        assertEquals("rel_strength_3mo", scanMetricFor("rel_strength")?.key)
    }

    @Test
    fun `a metric chip stays lit when the direction flips`() {
        // The chip names the metric; the toggle names the end. If the chip compared the whole sort
        // string, flipping to ascending would unlight every chip and the list would sit there ranked
        // by nothing the screen admits to.
        assertTrue(scanSortMetricMatches("-rsi14", "rsi14"))
        assertTrue(scanSortMetricMatches("rsi14", "rsi14"))
        assertFalse(scanSortMetricMatches("-rsi14", "adx14"))
        assertTrue(scanSortMetricMatches("rel_strength", "rel_strength_3mo"))
    }

    @Test
    fun `every offered sort is a metric the screen can render`() {
        // A sort with no renderer shows a row with no headline number at all, so the two lists must
        // not drift apart.
        MARKET_SCAN_SORTS.forEach {
            assertTrue("${it.key} has no renderer", scanMetricFor(it.key) != null)
        }
    }

    @Test
    fun `a sort chip carries no baked-in direction`() {
        // The direction is a control now. A chip that hardcoded "-" would be a second, contradictory
        // source of truth for it.
        MARKET_SCAN_SORTS.forEach { assertFalse(it.key.startsWith("-")) }
    }

    // ---------------------------------------------------------------------------------- limit

    @Test
    fun `the limit offered never exceeds what the server will return`() {
        // market_scan_endpoint runs limit = max(1, min(200, limit)) and echoes the clamped value.
        // Offering 500 would put a depth on screen that no response can satisfy.
        assertEquals(200, MARKET_SCAN_LIMIT_MAX)
        MARKET_SCAN_LIMITS.forEach {
            assertTrue("$it exceeds the server cap", it in 1..MARKET_SCAN_LIMIT_MAX)
        }
        assertEquals(MARKET_SCAN_LIMIT_MAX, clampScanLimit(500))
        assertEquals(MARKET_SCAN_LIMIT_MAX, clampScanLimit(99_999))
        assertEquals(1, clampScanLimit(0))
        assertEquals(1, clampScanLimit(-10))
        assertEquals(50, clampScanLimit(50))
    }

    // ------------------------------------------------------------------------------ watchlist

    @Test
    fun `a row already on the watchlist is detected case-insensitively`() {
        val wl = listOf(
            Asset("aapl", AssetType.STOCK, "Apple"),
            Asset(" msft ", AssetType.STOCK, "Microsoft"),
        )
        val watched = watchedStockSymbols(wl)
        assertTrue(isWatched(watched, "AAPL"))
        assertTrue(isWatched(watched, "aapl"))
        assertTrue(isWatched(watched, "MSFT"))
        assertFalse(isWatched(watched, "NVDA"))
    }

    @Test
    fun `a crypto entry sharing a ticker does not mask an equity add`() {
        // WatchlistStore de-duplicates on Asset.id, which is "STOCK:X" vs "CRYPTO:<id>". A scan row
        // is added as an equity, so a coin called ETH must NOT make the row claim "on list" — the
        // add would in fact succeed, and the row would be refusing an action that works.
        val watched = watchedStockSymbols(listOf(Asset("ETH", AssetType.CRYPTO, "Ethereum", "ethereum")))
        assertFalse(isWatched(watched, "ETH"))
    }

    // -------------------------------------------------------------------------- stale responses

    @Test
    fun `a response issued under an old filter set is dropped`() {
        val old = ScanRequest("rsi14", MarketScanFilters().withBound(adx, 25.0, null), 50)
        val now = ScanRequest("rsi14", MarketScanFilters().withBool("above_sma200", true), 50)
        assertTrue(scanResponseIsStale(mySeq = 2, applied = 1, requested = old, current = now))
        // Same query, newer sequence: this one belongs on screen.
        assertFalse(scanResponseIsStale(mySeq = 2, applied = 1, requested = now, current = now))
    }

    @Test
    fun `a response older than one already painted is dropped`() {
        val q = ScanRequest("rsi14", MarketScanFilters(), 50)
        assertTrue(scanResponseIsStale(mySeq = 1, applied = 3, requested = q, current = q))
    }

    @Test
    fun `a change of sort or of limit also invalidates a response in flight`() {
        val q = ScanRequest("rsi14", MarketScanFilters(), 50)
        assertTrue(scanResponseIsStale(4, 3, q, q.copy(sort = "-rsi14")))
        assertTrue(scanResponseIsStale(4, 3, q, q.copy(limit = 200)))
    }

    // ------------------------------------------------------------------------------ match line

    @Test
    fun `total matching is what makes a filter checkable`() {
        assertEquals(
            "Top 50 of 812 matching · 3,101 scanned",
            scanMatchLine(shown = 50, totalMatching = 812, scanned = 3101),
        )
    }

    @Test
    fun `an absent total is never printed as zero`() {
        // "Top 50 of 0 matching" would be a claim about the market assembled out of a missing key.
        assertEquals("Showing 50 · 3,101 scanned", scanMatchLine(50, null, 3101))
        assertEquals("Showing 50", scanMatchLine(50, null, null))
        assertNull(scanMatchLine(0, null, null))
        // A genuine zero from the server IS printed — the filter really did match nothing.
        assertEquals("Top 0 of 0 matching", scanMatchLine(0, 0, null))
    }
}
