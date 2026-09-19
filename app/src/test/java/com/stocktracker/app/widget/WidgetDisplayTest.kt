package com.stocktracker.app.widget

import com.stocktracker.app.data.model.Quote
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
}
