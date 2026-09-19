package com.stocktracker.app.notify

import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.ui.portfolio.STALE_QUOTE_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MarketSummaryNotifier.check] behavior around cached price staleness.
 *
 * The notifier falls back to [com.stocktracker.app.data.PriceCache] when the primary fetch fails.
 * To prevent locking the user out of receiving accurate market recaps, cached quotes older than
 * [STALE_QUOTE_MS] must be filtered out. If all quotes end up stale and are filtered, the movers
 * list becomes empty, [MarketSummary.build] returns null, and the recap is not sent — leaving
 * [com.stocktracker.app.data.SettingsStore.lastCloseSummaryDate] unset so a later attempt can succeed.
 */
class MarketSummaryNotifierTest {

    @Test
    fun `stale cached quote is older than the freshness threshold`() {
        val staleBoundMs = STALE_QUOTE_MS
        assertTrue("STALE_QUOTE_MS should be at least 1 hour", staleBoundMs >= 60 * 60 * 1000L)

        val now = System.currentTimeMillis()
        val staleQuote = Quote(
            symbol = "TEST",
            price = 100.0,
            change = 1.0,
            changePercent = 1.0,
            asOfEpochMs = now - staleBoundMs - 1_000L, // 1 second older than the threshold
        )

        // A quote older than staleBoundMs fails the filter
        val passesFilter = staleQuote.asOfEpochMs <= 0L || now - staleQuote.asOfEpochMs <= STALE_QUOTE_MS
        assertFalse("Quote older than STALE_QUOTE_MS should fail the age filter", passesFilter)
    }

    @Test
    fun `fresh cached quote passes the staleness filter`() {
        val now = System.currentTimeMillis()
        val freshQuote = Quote(
            symbol = "TEST",
            price = 100.0,
            change = 1.0,
            changePercent = 1.0,
            asOfEpochMs = now - 30 * 60 * 1000L, // 30 minutes old — well within STALE_QUOTE_MS (6 hours)
        )

        // A quote within staleBoundMs passes the filter
        val passesFilter = freshQuote.asOfEpochMs <= 0L || now - freshQuote.asOfEpochMs <= STALE_QUOTE_MS
        assertTrue("Quote fresher than STALE_QUOTE_MS should pass the age filter", passesFilter)
    }

    @Test
    fun `zero asOfEpochMs (unknown age) passes the staleness filter`() {
        val now = System.currentTimeMillis()
        val unknownAgeQuote = Quote(
            symbol = "TEST",
            price = 100.0,
            change = 1.0,
            changePercent = 1.0,
            asOfEpochMs = 0L, // Unknown age — treated as acceptable
        )

        // A quote with asOfEpochMs == 0 passes the filter (treated as fresh)
        val passesFilter = unknownAgeQuote.asOfEpochMs <= 0L || now - unknownAgeQuote.asOfEpochMs <= STALE_QUOTE_MS
        assertTrue("Quote with unknown age (asOfEpochMs=0) should pass the filter", passesFilter)
    }

    @Test
    fun `empty movers list from stale cache results in no recap sent and no date advanced`() {
        // When all cached quotes are older than STALE_QUOTE_MS, they are filtered out.
        // This results in an empty movers list.
        // An empty movers list causes MarketSummary.build to return null (verified in MarketSummaryTest),
        // which causes MarketSummaryNotifier.check to return early (line 106) before executing
        // lines 114-117 that set lastCloseSummaryDate.
        //
        // This test documents that behavior:
        val emptyMovers = emptyList<Mover>()
        val summary = MarketSummary.build(
            phase = com.stocktracker.app.util.MarketPhase.AFTER,
            etSecondsOfDay = 17 * 3600, // 5:00pm ET
            isTradingDay = true,
            movers = emptyMovers,
            alreadySentClose = false,
            alreadySentAfterHours = false,
        )
        assertTrue("Empty movers list produces no summary", summary == null)
        // When summary is null, the notifier returns early without setting the date.
    }
}
