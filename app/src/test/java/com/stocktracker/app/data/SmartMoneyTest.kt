package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.SmartMoneyResponse
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Theme C. Two things the card must carry or it misleads:
 *  - congressional filings LAG up to ~45 days, so the disclosed trade date and the filing date are
 *    both needed to age the evidence;
 *  - with no Finnhub key the insider feed returns null rather than failing, which would render as
 *    "nobody is buying" across the entire watchlist.
 */
class SmartMoneyTest {

    @Test
    fun `the filing lag reaches the app so the card can age the evidence`() {
        val json = """
            {"watchlist_size":24,"insider_feed_configured":true,
             "results":[{"symbol":"LLY","score":7.75,
               "reasons":["Several insiders bought independently","A six-figure congressional buy"],
               "sources_seen":["insider","congress"],
               "congress_newest_trade":"2026-06-10","congress_latest_filing":"2026-07-25"}],
             "no_evidence":["VOO"],"fetch_failed":[],"note":"n"}
        """.trimIndent()
        val r = Http.json.decodeFromString<SmartMoneyResponse>(json)
        val row = r.results.first()
        assertEquals("2026-06-10", row.congressNewestTrade)
        assertEquals("2026-07-25", row.congressLatestFiling)
        assertEquals(2, row.reasons.size)
        assertEquals(listOf("VOO"), r.noEvidence)
    }

    @Test
    fun `a missing insider key is surfaced, not silently read as no buying`() {
        val json = """
            {"watchlist_size":24,"insider_feed_configured":false,
             "warning":"No Finnhub key configured, so insider filings were not consulted.",
             "results":[],"note":"n"}
        """.trimIndent()
        val r = Http.json.decodeFromString<SmartMoneyResponse>(json)
        assertFalse(r.insiderFeedConfigured)
        assertNotNull("the card must be able to say the ranking is partial", r.warning)
    }

    @Test
    fun `a per-row partial read names which half was missing`() {
        val json = """
            {"results":[{"symbol":"X","score":1.0,"sources_seen":["congress"],
                         "unavailable":["insider"]}],"note":"n"}
        """.trimIndent()
        val r = Http.json.decodeFromString<SmartMoneyResponse>(json)
        assertEquals(listOf("insider"), r.results.first().unavailable)
        assertEquals(listOf("congress"), r.results.first().sourcesSeen)
    }

    @Test
    fun `a clean full read carries no warnings`() {
        val json = """{"watchlist_size":10,"insider_feed_configured":true,"results":[],"note":"n"}"""
        val r = Http.json.decodeFromString<SmartMoneyResponse>(json)
        assertTrue(r.warning == null && r.fetchFailed.isEmpty() && r.insiderFeedConfigured)
    }
}
