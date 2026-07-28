package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.ValueScreenResponse
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 200-week value screen is CONTEXT, not a buy list — the backend's own note says the historical
 * touch study found below-the-line dips underperformed the S&P. If the app drops that note, or
 * silently swallows the symbols that couldn't be scored, the list reads as the opposite of what it is.
 */
class ValueScreenTest {

    @Test
    fun `the ranking, the caveat and the unscoreable names all survive decoding`() {
        val json = """
            {"universe_size":58,"scored":54,
             "skipped":["FBTC","IBIT","GME.WS","VG"],
             "results":[
               {"symbol":"CHTR","value_score":89.8,"below_line":true,
                "price_vs_200w_sma_pct":-58.5,"rsi_14w":35.1,"direction":"recovering","zone":"deep_value"},
               {"symbol":"SMCI","value_score":43.0,"below_line":true,
                "price_vs_200w_sma_pct":-20.9,"rsi_14w":48.1,"direction":"deepening"}],
             "note":"context, not a buy signal","cached":true,"cached_age_seconds":25}
        """.trimIndent()
        val r = Http.json.decodeFromString<ValueScreenResponse>(json)

        assertEquals(listOf("CHTR", "SMCI"), r.results.map { it.symbol })
        assertEquals(-58.5, r.results.first().priceVs200wPct!!, 0.01)
        assertEquals("deepening", r.results[1].direction)
        assertTrue("the caveat must reach the UI", r.note.isNotBlank())
        assertEquals(4, r.skipped.size)
        assertEquals(25L, r.cachedAgeSeconds)
    }

    @Test
    fun `an empty screen decodes without inventing rows`() {
        val r = Http.json.decodeFromString<ValueScreenResponse>(
            """{"universe_size":40,"scored":40,"results":[],"note":"n"}""")
        assertTrue(r.results.isEmpty())
        assertTrue(r.skipped.isEmpty())
        assertNull(r.cachedAgeSeconds)
    }

    @Test
    fun `a row with absent optional metrics stays absent rather than becoming zero`() {
        // A missing RSI or direction must not render as "RSI 0" / a confident blank direction.
        val r = Http.json.decodeFromString<ValueScreenResponse>(
            """{"results":[{"symbol":"X","value_score":10.0,"below_line":true}],"note":"n"}""")
        val row = r.results.first()
        assertNull(row.rsi14w)
        assertNull(row.direction)
        assertNull(row.priceVs200wPct)
    }

    @Test
    fun `the pool it ran over, its staleness and fetch failures all reach the app`() {
        // A fallback-sampled or stale run looked identical to a fresh curated one, and a fetch
        // failure was indistinguishable from "not enough history" — a claim about the network
        // rendered as a claim about the company.
        val json = """
            {"universe_size":58,"universe_source":"yahoo_screens","universe_stale":true,
             "scored":50,"skipped":["FBTC"],"fetch_failed":["RATELIMITED"],
             "results":[{"symbol":"X","value_score":10.0,"below_line":true,
                         "unmeasured":["rsi_14w","direction"]}],
             "note":"n"}
        """.trimIndent()
        val r = Http.json.decodeFromString<ValueScreenResponse>(json)
        assertEquals("yahoo_screens", r.universeSource)
        assertTrue(r.universeStale)
        assertEquals(listOf("RATELIMITED"), r.fetchFailed)
        assertEquals(listOf("FBTC"), r.skipped)
        assertEquals(listOf("rsi_14w", "direction"), r.results.first().unmeasured)
    }

    @Test
    fun `a curated fresh run carries no warning fields`() {
        val json = """
            {"universe_size":600,"universe_source":"curated","universe_stale":false,
             "results":[],"note":"n"}
        """.trimIndent()
        val r = Http.json.decodeFromString<ValueScreenResponse>(json)
        assertEquals("curated", r.universeSource)
        assertTrue(!r.universeStale && r.fetchFailed.isEmpty())
    }
}
