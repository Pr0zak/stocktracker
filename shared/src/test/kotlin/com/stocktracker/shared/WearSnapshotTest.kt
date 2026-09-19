package com.stocktracker.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [wearContent]'s routing choice, and that it hands off the actual freshness/partial judgment to
 *  the exact same [tickerDisplay]/[portfolioDisplay] the phone widgets use. */
class WearSnapshotTest {

    private fun quote(asOfEpochMs: Long = 0L) =
        Quote(symbol = "AAPL", price = 150.0, change = 1.5, changePercent = 1.0, asOfEpochMs = asOfEpochMs)

    @Test
    fun `no snapshot at all reads as not configured, not as loading`() {
        assertEquals(WearContent.NotConfigured, wearContent(snapshot = null, nowMs = 1_000L))
    }

    @Test
    fun `a ticker with no portfolio placed shows the ticker`() {
        val snapshot = WearSnapshot(ticker = WearTickerSnapshot(quote = quote(500L), displayName = "Apple"))
        val content = wearContent(snapshot, nowMs = 1_000L)
        assertTrue(content is WearContent.Ticker)
        assertEquals("Apple", (content as WearContent.Ticker).displayName)
    }

    @Test
    fun `a portfolio with real holdings wins over a ticker when both are placed`() {
        val snapshot = WearSnapshot(
            ticker = WearTickerSnapshot(quote = quote(500L), displayName = "Apple"),
            portfolio = WearPortfolioSnapshot(
                summary = PortfolioSummary(totalValue = 1000.0, holdingCount = 3),
                loaded = true,
            ),
        )
        assertTrue(wearContent(snapshot, nowMs = 1_000L) is WearContent.Portfolio)
    }

    @Test
    fun `an empty portfolio (nothing held yet) falls back to the ticker`() {
        val snapshot = WearSnapshot(
            ticker = WearTickerSnapshot(quote = quote(500L), displayName = "Apple"),
            portfolio = WearPortfolioSnapshot(
                summary = PortfolioSummary(totalValue = 0.0, holdingCount = 0),
                loaded = true,
            ),
        )
        assertTrue(wearContent(snapshot, nowMs = 1_000L) is WearContent.Ticker)
    }

    @Test
    fun `an empty portfolio with no ticker configured still shows the portfolio message`() {
        val snapshot = WearSnapshot(
            portfolio = WearPortfolioSnapshot(summary = null, loaded = true),
        )
        val content = wearContent(snapshot, nowMs = 1_000L)
        assertTrue(content is WearContent.Portfolio)
        assertEquals(
            PortfolioDisplay.Message("Set shares on a ticker to track value"),
            (content as WearContent.Portfolio).display,
        )
    }

    @Test
    fun `the age is recomputed against the WATCH's clock, not frozen at push time`() {
        // Regression guard for the exact bug this design avoids: if the watch rendered a string the
        // phone had already formatted at push time, a tile viewed long after the last successful
        // push would still read as current. Feeding the raw quote through tickerDisplay with the
        // watch's own nowMs must report the true, much larger age instead.
        val pushedAgeMs = 5_000L                 // fresh as of when the phone pushed
        val viewedMuchLaterMs = 3 * 60 * 60_000L // watch renders the tile 3 hours later
        val asOf = 1_700_000_000_000L            // a real epoch ms -- 0 means "unknown" to tickerDisplay
        val snapshot = WearSnapshot(ticker = WearTickerSnapshot(quote = quote(asOf), displayName = "Apple"))

        val atPushTime = wearContent(snapshot, nowMs = asOf + pushedAgeMs, staleAfterMs = 60_000L)
        assertNull(((atPushTime as WearContent.Ticker).display as TickerDisplay.Priced).ageLabel)

        val atViewTime = wearContent(snapshot, nowMs = asOf + viewedMuchLaterMs, staleAfterMs = 60_000L)
        assertEquals(
            "as of " + widgetAgeLabel(viewedMuchLaterMs),
            ((atViewTime as WearContent.Ticker).display as TickerDisplay.Priced).ageLabel,
        )
    }
}
