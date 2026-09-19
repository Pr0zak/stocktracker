package com.stocktracker.app.widget

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.remote.Http
import com.stocktracker.shared.PortfolioDisplay
import com.stocktracker.shared.TickerDisplay
import com.stocktracker.shared.portfolioDisplay
import com.stocktracker.shared.tickerDisplay
import com.stocktracker.shared.widgetAgeLabel
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The label/branch decisions behind WGT-1/2/3: never render an absent, failed, or stale value as a
 * confident number. These are pulled out of the Glance composables specifically so they can run
 * under a plain JVM test -- Glance needs an Android runtime.
 */
class WidgetDisplayTest {

    private fun quote(symbol: String = "AAPL", asOfEpochMs: Long = 0L) =
        Quote(symbol = symbol, price = 100.0, change = 1.0, changePercent = 1.0, asOfEpochMs = asOfEpochMs)

    // ---------------------------------------------------------------------------------------
    // tickerDisplay (WGT-3)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `no quote and no error reads as still loading`() {
        val d = tickerDisplay(quote = null, error = null, nowMs = 1_000L)
        assertEquals(TickerDisplay.NoData(tapToOpen = false), d)
    }

    @Test
    fun `no quote with an error invites a tap rather than showing loading forever`() {
        val d = tickerDisplay(quote = null, error = "boom", nowMs = 1_000L)
        assertEquals(TickerDisplay.NoData(tapToOpen = true), d)
    }

    @Test
    fun `a fresh quote with no error discloses no age`() {
        val now = 1_000_000L
        val d = tickerDisplay(quote(asOfEpochMs = now - 1_000L), error = null, now, staleAfterMs = 60_000L)
        assertTrue(d is TickerDisplay.Priced)
        assertNull((d as TickerDisplay.Priced).ageLabel)
    }

    @Test
    fun `a quote older than the threshold is labelled with its age`() {
        val now = 1_000_000L
        val ageMs = 70_000L
        val d = tickerDisplay(quote(asOfEpochMs = now - ageMs), error = null, now, staleAfterMs = 60_000L)
        assertEquals("as of " + widgetAgeLabel(ageMs), (d as TickerDisplay.Priced).ageLabel)
    }

    @Test
    fun `a kept quote alongside a fresh error says the refresh failed, not merely that time passed`() {
        val now = 1_000_000L
        // Even a quote that is still WITHIN the staleness window must say "Update failed" when the
        // refresh that would have confirmed it just failed -- the number on screen didn't just get
        // old, it stopped being verified.
        val d = tickerDisplay(quote(asOfEpochMs = now - 1_000L), error = "429", now, staleAfterMs = 60_000L)
        assertEquals("Update failed", (d as TickerDisplay.Priced).ageLabel)
    }

    @Test
    fun `keeping the stored quote on failure requires the same symbol`() {
        assertTrue(shouldKeepQuoteOnFailure(quote(symbol = "AAPL"), "aapl"))
        assertTrue(shouldKeepQuoteOnFailure(quote(symbol = "aapl"), "AAPL"))
        assertEquals(false, shouldKeepQuoteOnFailure(quote(symbol = "AAPL"), "MSFT"))
        assertEquals(false, shouldKeepQuoteOnFailure(null, "AAPL"))
    }

    @Test
    fun `no repaint is forced when there is nothing stored or no timestamp on it`() {
        assertEquals(false, shouldRepaintForStaleness(null, nowMs = 1_000L))
        assertEquals(false, shouldRepaintForStaleness(quote(asOfEpochMs = 0L), nowMs = 1_000L))
    }

    @Test
    fun `a repaint is forced once the stored quote crosses the staleness threshold`() {
        val now = 1_000_000L
        assertEquals(false, shouldRepaintForStaleness(quote(asOfEpochMs = now - 30_000L), now, staleAfterMs = 60_000L))
        assertTrue(shouldRepaintForStaleness(quote(asOfEpochMs = now - 90_000L), now, staleAfterMs = 60_000L))
    }

    // ---------------------------------------------------------------------------------------
    // portfolioDisplay (WGT-1)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `no summary yet reads as loading, then falls back to the empty-portfolio hint`() {
        assertEquals(
            PortfolioDisplay.Message("Loading…"),
            portfolioDisplay(summary = null, loaded = false, error = null, lastSuccessMs = 0L, nowMs = 1L),
        )
        assertEquals(
            PortfolioDisplay.Message("Set shares on a ticker to track value"),
            portfolioDisplay(summary = null, loaded = true, error = null, lastSuccessMs = 0L, nowMs = 1L),
        )
    }

    @Test
    fun `a summary with no holdings is treated the same as no summary`() {
        val empty = PortfolioSummary(holdingCount = 0)
        assertEquals(
            PortfolioDisplay.Message("Set shares on a ticker to track value"),
            portfolioDisplay(empty, loaded = true, error = null, lastSuccessMs = 0L, nowMs = 1L),
        )
    }

    @Test
    fun `every holding failing shows no total at all, not a confident zero`() {
        val allMissing = PortfolioSummary(totalValue = 0.0, holdingCount = 3, missingCount = 3)
        assertEquals(
            PortfolioDisplay.Message("Couldn't load portfolio"),
            portfolioDisplay(allMissing, loaded = true, error = null, lastSuccessMs = 0L, nowMs = 1L),
        )
    }

    @Test
    fun `a partial summary renders the total plus how much of it is real`() {
        val partial = PortfolioSummary(totalValue = 500.0, holdingCount = 5, missingCount = 2)
        val d = portfolioDisplay(partial, loaded = true, error = null, lastSuccessMs = 0L, nowMs = 1L)
        assertTrue(d is PortfolioDisplay.Priced)
        assertEquals("3 of 5 priced", (d as PortfolioDisplay.Priced).partialLabel)
    }

    @Test
    fun `a fully priced summary carries no partial label`() {
        val full = PortfolioSummary(totalValue = 500.0, holdingCount = 5, missingCount = 0)
        val d = portfolioDisplay(full, loaded = true, error = null, lastSuccessMs = 0L, nowMs = 1L)
        assertNull((d as PortfolioDisplay.Priced).partialLabel)
    }

    @Test
    fun `a later failure keeps the old total but discloses that the refresh failed`() {
        val summary = PortfolioSummary(totalValue = 500.0, holdingCount = 5, missingCount = 0)
        val d = portfolioDisplay(summary, loaded = true, error = "Couldn't load portfolio", lastSuccessMs = 1_000L, nowMs = 2_000L)
        assertEquals("Update failed", (d as PortfolioDisplay.Priced).ageLabel)
    }

    @Test
    fun `an old but not-yet-stale total discloses nothing extra`() {
        val summary = PortfolioSummary(totalValue = 500.0, holdingCount = 5, missingCount = 0)
        val now = 1_000_000L
        val d = portfolioDisplay(summary, loaded = true, error = null, lastSuccessMs = now - 1_000L, nowMs = now, staleAfterMs = 60_000L)
        assertNull((d as PortfolioDisplay.Priced).ageLabel)
    }

    @Test
    fun `a total sitting past the staleness window says how old it is`() {
        val summary = PortfolioSummary(totalValue = 500.0, holdingCount = 5, missingCount = 0)
        val now = 1_000_000L
        val ageMs = 90_000L
        val d = portfolioDisplay(summary, loaded = true, error = null, lastSuccessMs = now - ageMs, nowMs = now, staleAfterMs = 60_000L)
        assertEquals("as of " + widgetAgeLabel(ageMs), (d as PortfolioDisplay.Priced).ageLabel)
    }

    // ---------------------------------------------------------------------------------------
    // watchlistRowBudget + watchlistDisplay (WGT-2)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an unreported height falls back to the old fixed cap`() {
        assertEquals(WATCHLIST_FALLBACK_ROWS, watchlistRowBudget(0f, reserveFooter = false))
        assertEquals(WATCHLIST_FALLBACK_ROWS, watchlistRowBudget(-5f, reserveFooter = true))
    }

    @Test
    fun `row budget grows with height and shrinks when a footer is reserved`() {
        val heightDp = WATCHLIST_HEADER_DP + WATCHLIST_ROW_DP * 6
        assertEquals(6, watchlistRowBudget(heightDp, reserveFooter = false))
        assertEquals(5, watchlistRowBudget(heightDp, reserveFooter = true))
    }

    @Test
    fun `row budget never drops below one row even on a tiny widget`() {
        assertEquals(1, watchlistRowBudget(10f, reserveFooter = true))
    }

    private fun rows(n: Int) = (1..n).map { WatchlistRow(symbol = "S$it", name = "S$it", price = 1.0, changePercent = 1.0) }

    @Test
    fun `a full, untruncated list draws no footer at all`() {
        val heightDp = WATCHLIST_HEADER_DP + WATCHLIST_ROW_DP * 6
        val d = watchlistDisplay(rows(4), expectedCount = 4, error = null, loaded = true, heightDp = heightDp)
        val r = d as WatchlistDisplay.Rows
        assertEquals(4, r.visible.size)
        assertNull(r.footerLabel)
    }

    @Test
    fun `truncation alone is disclosed as plus N more`() {
        val heightDp = WATCHLIST_HEADER_DP + WATCHLIST_ROW_DP * 6
        val d = watchlistDisplay(rows(8), expectedCount = 8, error = null, loaded = true, heightDp = heightDp)
        val r = d as WatchlistDisplay.Rows
        assertEquals(5, r.visible.size)
        assertEquals("+3 more", r.footerLabel)
    }

    @Test
    fun `a partial fetch is disclosed as N of M loaded even when everything fits`() {
        val heightDp = WATCHLIST_HEADER_DP + WATCHLIST_FOOTER_DP + WATCHLIST_ROW_DP * 6
        val d = watchlistDisplay(rows(6), expectedCount = 9, error = null, loaded = true, heightDp = heightDp)
        val r = d as WatchlistDisplay.Rows
        assertEquals(6, r.visible.size)
        assertEquals("6 of 9 loaded", r.footerLabel)
    }

    @Test
    fun `a partial fetch that also overflows the widget discloses both`() {
        val heightDp = WATCHLIST_HEADER_DP + WATCHLIST_ROW_DP * 6
        val d = watchlistDisplay(rows(10), expectedCount = 15, error = null, loaded = true, heightDp = heightDp)
        val r = d as WatchlistDisplay.Rows
        assertEquals(5, r.visible.size)
        assertEquals("10 of 15 loaded · +5 more", r.footerLabel)
    }

    @Test
    fun `no rows falls back to loading, then the empty-watchlist hint`() {
        assertEquals(
            WatchlistDisplay.Message("Loading…"),
            watchlistDisplay(emptyList(), expectedCount = 0, error = null, loaded = false, heightDp = 300f),
        )
        assertEquals(
            WatchlistDisplay.Message("Add tickers in the app"),
            watchlistDisplay(emptyList(), expectedCount = 0, error = null, loaded = true, heightDp = 300f),
        )
    }

    @Test
    fun `a total failure with no prior success shows the bare error`() {
        val d = watchlistDisplay(emptyList(), expectedCount = 3, error = "Couldn't load prices", loaded = true, heightDp = 300f)
        assertEquals(WatchlistDisplay.Message("Couldn't load prices"), d)
    }

    @Test
    fun `a total failure after a prior success discloses how old that success was`() {
        val now = 1_000_000L
        val ageMs = 90_000L
        val d = watchlistDisplay(
            emptyList(), expectedCount = 3, error = "Couldn't load prices", loaded = true,
            heightDp = 300f, lastSuccessMs = now - ageMs, nowMs = now,
        )
        assertEquals(WatchlistDisplay.Message("Couldn't load prices (last updated ${widgetAgeLabel(ageMs)})"), d)
    }

    @Test
    fun `a row with no timestamp is not treated as stale`() {
        assertEquals(false, watchlistRowIsStale(WatchlistRow("A", "A", 1.0, 1.0, asOfEpochMs = 0L), nowMs = 1_000_000L))
    }

    @Test
    fun `a row's colour stops asserting a direction once it crosses the staleness window`() {
        val now = 10_000_000L
        val fresh = WatchlistRow("A", "A", 1.0, 1.0, asOfEpochMs = now - 1_000L)
        val stale = WatchlistRow("A", "A", 1.0, 1.0, asOfEpochMs = now - 3_600_000L)
        assertEquals(false, watchlistRowIsStale(fresh, now, staleAfterMs = 60_000L))
        assertTrue(watchlistRowIsStale(stale, now, staleAfterMs = 60_000L))
    }

    // ---------------------------------------------------------------------------------------
    // Watchlist widget per-instance configuration (WGT-5)
    // ---------------------------------------------------------------------------------------

    private fun asset(symbol: String, type: AssetType = AssetType.STOCK, groups: List<String> = emptyList()) =
        Asset(symbol = symbol, type = type, displayName = symbol, groups = groups)

    @Test
    fun `an unconfigured watchlist widget defaults to the whole list, manual order, percent`() {
        val default = WatchlistWidgetConfig()
        assertEquals(WatchlistWidgetConfig.LIST_ALL, default.listName)
        assertEquals(WatchlistSortOrder.MANUAL, default.sortOrder)
        assertEquals(WatchlistValueMode.PERCENT, default.valueMode)
        assertEquals(15, default.refreshMinutes)
        // Same fallback as TickerWidgetState.readConfig -- a widget with nothing stored yet (placed
        // before WGT-5, or a CONFIG value that fails to parse) gets sensible defaults, not a crash
        // or a blank widget.
        assertEquals(default, WatchlistWidgetState.readConfig(emptyPreferences()))
    }

    @Test
    fun `two configured instances decode independently -- config does not bleed across widgets`() {
        val cryptoInstance = WatchlistWidgetConfig(
            listName = WatchlistWidgetConfig.LIST_CRYPTO,
            sortOrder = WatchlistSortOrder.ALPHABETICAL,
            valueMode = WatchlistValueMode.DOLLAR,
            refreshMinutes = 60,
        )
        val namedListInstance = WatchlistWidgetConfig(listName = "Retirement", sortOrder = WatchlistSortOrder.CHANGE_DESC)

        val prefsA = preferencesOf(WatchlistWidgetState.CONFIG to Http.json.encodeToString(cryptoInstance))
        val prefsB = preferencesOf(WatchlistWidgetState.CONFIG to Http.json.encodeToString(namedListInstance))

        // Each instance's own Glance state decodes back to exactly its own config -- reading one
        // instance's prefs must never see the other's, which is the shape of the original bug (one
        // shared write clobbering every placed widget).
        assertEquals(cryptoInstance, WatchlistWidgetState.readConfig(prefsA))
        assertEquals(namedListInstance, WatchlistWidgetState.readConfig(prefsB))
        assertTrue(WatchlistWidgetState.readConfig(prefsA) != WatchlistWidgetState.readConfig(prefsB))
    }

    @Test
    fun `filtering to All keeps every asset regardless of type or group`() {
        val assets = listOf(asset("AAPL"), asset("BTC", AssetType.CRYPTO), asset("MSFT", groups = listOf("Tech")))
        assertEquals(assets, filterWatchlistAssets(assets, WatchlistWidgetConfig.LIST_ALL))
    }

    @Test
    fun `filtering to Stocks or Crypto splits by asset type`() {
        val aapl = asset("AAPL")
        val btc = asset("BTC", AssetType.CRYPTO)
        val assets = listOf(aapl, btc)
        assertEquals(listOf(aapl), filterWatchlistAssets(assets, WatchlistWidgetConfig.LIST_STOCKS))
        assertEquals(listOf(btc), filterWatchlistAssets(assets, WatchlistWidgetConfig.LIST_CRYPTO))
    }

    @Test
    fun `filtering to a named list keeps only assets tagged with that group`() {
        val tagged = asset("MSFT", groups = listOf("Tech", "Retirement"))
        val untagged = asset("F")
        val assets = listOf(tagged, untagged)
        assertEquals(listOf(tagged), filterWatchlistAssets(assets, "Retirement"))
    }

    @Test
    fun `a named list the user has since deleted or renamed filters to nothing, not to All`() {
        val assets = listOf(asset("AAPL"), asset("BTC", AssetType.CRYPTO))
        assertEquals(emptyList<Asset>(), filterWatchlistAssets(assets, "No Longer Exists"))
    }

    private fun row(symbol: String, changePercent: Double, changeAbs: Double = 0.0) =
        WatchlistRow(symbol = symbol, name = symbol, price = 1.0, changePercent = changePercent, changeAbs = changeAbs)

    @Test
    fun `manual sort order leaves the fetch order untouched`() {
        val rows = listOf(row("C", -1.0), row("A", 2.0), row("B", 0.0))
        assertEquals(rows, sortWatchlistRows(rows, WatchlistSortOrder.MANUAL))
    }

    @Test
    fun `alphabetical sort ignores case`() {
        val rows = listOf(row("msft", 1.0), row("AAPL", 1.0), row("Ford", 1.0))
        assertEquals(listOf("AAPL", "Ford", "msft"), sortWatchlistRows(rows, WatchlistSortOrder.ALPHABETICAL).map { it.symbol })
    }

    @Test
    fun `change-desc sorts top gainers first, change-asc sorts top losers first`() {
        val rows = listOf(row("A", 1.0), row("B", -5.0), row("C", 3.0))
        assertEquals(listOf("C", "A", "B"), sortWatchlistRows(rows, WatchlistSortOrder.CHANGE_DESC).map { it.symbol })
        assertEquals(listOf("B", "A", "C"), sortWatchlistRows(rows, WatchlistSortOrder.CHANGE_ASC).map { it.symbol })
    }

    @Test
    fun `the change column honors the dollar-vs-percent toggle`() {
        val up = row("A", changePercent = 1.20, changeAbs = 2.71)
        val down = row("B", changePercent = -0.50, changeAbs = -1.10)
        assertEquals("▲ +1.20%", watchlistChangeText(up, WatchlistValueMode.PERCENT, hideZeroCents = false))
        assertEquals("▲ +2.71", watchlistChangeText(up, WatchlistValueMode.DOLLAR, hideZeroCents = false))
        assertEquals("▼ -0.50%", watchlistChangeText(down, WatchlistValueMode.PERCENT, hideZeroCents = false))
        assertEquals("▼ -1.10", watchlistChangeText(down, WatchlistValueMode.DOLLAR, hideZeroCents = false))
    }

    @Test
    fun `the All list draws no subtitle, any other list draws its own name`() {
        assertNull(watchlistListLabel(WatchlistWidgetConfig.LIST_ALL))
        assertEquals("Crypto", watchlistListLabel(WatchlistWidgetConfig.LIST_CRYPTO))
        assertEquals("Retirement", watchlistListLabel("Retirement"))
    }

    @Test
    fun `a scoped instance's subtitle eats into the row budget the same way the footer does`() {
        val heightDp = WATCHLIST_HEADER_DP + WATCHLIST_ROW_DP * 6
        assertEquals(6, watchlistRowBudget(heightDp, reserveFooter = false, showSubtitle = false))
        assertEquals(5, watchlistRowBudget(heightDp, reserveFooter = false, showSubtitle = true))
        // Reserving both the subtitle and the footer eats even further into the row count.
        val heightForBoth = WATCHLIST_HEADER_DP + WATCHLIST_SUBTITLE_DP + WATCHLIST_FOOTER_DP + WATCHLIST_ROW_DP * 6
        assertEquals(6, watchlistRowBudget(heightForBoth, reserveFooter = true, showSubtitle = true))
    }

    @Test
    fun `a full list scoped to a named list still draws no footer, just the subtitle`() {
        val heightDp = WATCHLIST_HEADER_DP + WATCHLIST_SUBTITLE_DP + WATCHLIST_ROW_DP * 4
        val d = watchlistDisplay(
            rows(4), expectedCount = 4, error = null, loaded = true, heightDp = heightDp, listLabel = "Crypto",
        )
        val r = d as WatchlistDisplay.Rows
        assertEquals(4, r.visible.size)
        assertNull(r.footerLabel)
    }

    @Test
    fun `an empty named list is disclosed as empty, not as an invitation to add tickers`() {
        val d = watchlistDisplay(emptyList(), expectedCount = 0, error = null, loaded = true, heightDp = 300f, listLabel = "Retirement")
        assertEquals(WatchlistDisplay.Message("No tickers in \"Retirement\""), d)
    }

    @Test
    fun `omitting listLabel keeps the original unscoped behaviour unchanged`() {
        val heightDp = WATCHLIST_HEADER_DP + WATCHLIST_ROW_DP * 6
        assertEquals(6, watchlistRowBudget(heightDp, reserveFooter = false))
        val d = watchlistDisplay(emptyList(), expectedCount = 0, error = null, loaded = true, heightDp = 300f)
        assertEquals(WatchlistDisplay.Message("Add tickers in the app"), d)
    }

    @Test
    fun `an instance's staleness repaint is driven by its own last-success timestamp`() {
        val now = 1_000_000L
        assertEquals(false, shouldRepaintWatchlistForStaleness(0L, now))
        assertEquals(false, shouldRepaintWatchlistForStaleness(now - 30_000L, now, staleAfterMs = 60_000L))
        assertTrue(shouldRepaintWatchlistForStaleness(now - 90_000L, now, staleAfterMs = 60_000L))
    }
}
