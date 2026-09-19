package com.stocktracker.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Confirms this module's copy of the honesty-rule functions is independently correct and testable
 * without any Android runtime -- the same property [com.stocktracker.app.widget.WidgetDisplayTest]
 * (in `:app`) already relies on for the phone widgets, now also proven from the Wear side's own
 * dependency graph. The full case matrix (including the "kept quote + fresh error" / "as of Xh ago"
 * boundary conditions) lives in that phone-side test since it exercises the exact same functions;
 * this file covers the same functions from `:shared`'s own build so a regression here is caught
 * even if `:app` were not built in a given CI run.
 */
class WidgetDisplayCoreTest {

    private fun quote(asOfEpochMs: Long = 0L) =
        Quote(symbol = "AAPL", price = 100.0, change = 1.0, changePercent = 1.0, asOfEpochMs = asOfEpochMs)

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
    fun `a fresh quote discloses no age`() {
        val now = 1_000_000L
        val d = tickerDisplay(quote(asOfEpochMs = now - 1_000L), error = null, now, staleAfterMs = 60_000L)
        assertNull((d as TickerDisplay.Priced).ageLabel)
    }

    @Test
    fun `a stale quote is labelled with its age, using the shared watch-and-phone clock`() {
        val now = 1_000_000L
        val ageMs = 70_000L
        val d = tickerDisplay(quote(asOfEpochMs = now - ageMs), error = null, now, staleAfterMs = 60_000L)
        assertEquals("as of " + widgetAgeLabel(ageMs), (d as TickerDisplay.Priced).ageLabel)
    }

    @Test
    fun `a kept quote alongside a fresh error says the refresh failed, not merely that time passed`() {
        val now = 1_000_000L
        val d = tickerDisplay(quote(asOfEpochMs = now - 1_000L), error = "429", now, staleAfterMs = 60_000L)
        assertEquals("Update failed", (d as TickerDisplay.Priced).ageLabel)
    }

    @Test
    fun `no portfolio summary and not yet loaded reads as loading`() {
        assertEquals(
            PortfolioDisplay.Message("Loading…"),
            portfolioDisplay(summary = null, loaded = false, error = null, lastSuccessMs = 0L, nowMs = 1L),
        )
    }

    @Test
    fun `every holding missing a quote is not a real total`() {
        val allMissing = PortfolioSummary(totalValue = 0.0, holdingCount = 3, missingCount = 3)
        assertEquals(
            PortfolioDisplay.Message("Couldn't load portfolio"),
            portfolioDisplay(allMissing, loaded = true, error = null, lastSuccessMs = 0L, nowMs = 1L),
        )
    }

    @Test
    fun `a partial portfolio total says how much of it is real`() {
        val partial = PortfolioSummary(totalValue = 500.0, holdingCount = 5, missingCount = 2)
        val d = portfolioDisplay(partial, loaded = true, error = null, lastSuccessMs = 0L, nowMs = 1L)
        assertTrue(d is PortfolioDisplay.Priced)
        assertEquals("3 of 5 priced", (d as PortfolioDisplay.Priced).partialLabel)
    }

    @Test
    fun `widgetAgeLabel buckets minutes, hours, and days the same way everywhere it is called`() {
        assertEquals("3m ago", widgetAgeLabel(3 * 60_000L))
        assertEquals("2h ago", widgetAgeLabel(2 * 60 * 60_000L))
        assertEquals("5d ago", widgetAgeLabel(5L * 24 * 60 * 60_000L))
    }
}
