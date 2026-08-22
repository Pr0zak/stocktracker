package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.MarketBreadth
import com.stocktracker.app.data.remote.MarketScanResponse
import com.stocktracker.app.data.remote.MarketScanRow
import com.stocktracker.app.data.remote.MarketScanRunResponse
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SWT-1's payload decoded honestly.
 *
 * `Http.json` sets `coerceInputValues = true`, so a non-nullable `Double = 0.0` swallows BOTH an
 * omitted key and an explicit `null` into a confident zero — the bug that shipped "Stop $0 · target
 * $0". On market-scan data a zero is worse than meaningless, it is a *claim*: `above_sma50: false`
 * says we checked and the stock is below its 50-day average, `pct_above_sma50: 0` says every stock
 * in America is, and `scanned: 0` says we swept the market and found nothing. Each of those must be
 * distinguishable from "the server did not say".
 */
class MarketScanDecodeTest {

    @Test
    fun `a full row decodes with every metric intact`() {
        val json = """
            {"as_of":"20260821","generated_at":1755800000.0,"universe_size":3147,"scanned":3113,
             "fetch_failed":0,"too_short":34,"universe_stale":false,"sort":"rel_strength_3mo",
             "limit":2,"total_matching":3113,"note":"a screen, not a buy list",
             "cached":true,"cached_age_seconds":42,
             "results":[
               {"symbol":"NVDA","price":184.2,"adr20_pct":2.9,"atr14":5.1,"atr14_pct":2.8,
                "adx14":31.4,"clv":0.72,"rel_volume":1.4,"dollar_volume_20d":2.7e10,
                "mom_20d":8.1,"mom_60d":22.5,"rsi14":63.2,"pct_off_52w_high":-1.2,
                "pct_vs_sma50":6.4,"pct_vs_sma200":18.9,"rel_strength_3mo":14.2,
                "ema20_slope_pct":0.9,"above_sma50":true,"above_sma200":true,"ma_stacked":true,
                "bars":504,"unmeasured":[],"d":"20260821","ts":1755800000.0},
               {"symbol":"XYZ","price":4.5,"bars":41,"unmeasured":["sma200","rel_strength_3mo"]}]}
        """.trimIndent()
        val r = Http.json.decodeFromString<MarketScanResponse>(json)

        assertEquals("20260821", r.asOf)
        assertEquals(3147, r.universeSize)
        assertEquals(3113, r.scanned)
        assertEquals(0, r.fetchFailed)
        assertEquals(34, r.tooShort)
        assertEquals(3113, r.totalMatching)
        assertEquals("rel_strength_3mo", r.sort)
        assertEquals(42L, r.cachedAgeSeconds)
        assertTrue("the server's framing must reach the UI", r.note.isNotBlank())

        val nvda = r.results.first()
        assertEquals("NVDA", nvda.symbol)
        assertEquals(2.9, nvda.adr20Pct!!, 1e-9)
        assertEquals(31.4, nvda.adx14!!, 1e-9)
        assertEquals(14.2, nvda.relStrength3mo!!, 1e-9)
        assertEquals(-1.2, nvda.pctOff52wHigh!!, 1e-9)
        assertEquals(504, nvda.bars)
        assertEquals(true, nvda.aboveSma50)
        assertEquals(true, nvda.maStacked)
        assertEquals(emptyList<String>(), nvda.unmeasured)
        assertEquals("20260821", nvda.d)
    }

    @Test
    fun `a metric the scan could not compute stays absent instead of becoming zero`() {
        // "RSI 0" / "ADR 0.0%" would be a reading. There was no reading.
        val row = Http.json.decodeFromString<MarketScanRow>(
            """{"symbol":"IPO","price":22.0,"rsi14":null,"adx14":null,"rel_strength_3mo":null,
                "pct_off_52w_high":null,"atr14_pct":null,"bars":18}""",
        )
        assertNull(row.rsi14)
        assertNull(row.adx14)
        assertNull(row.relStrength3mo)
        assertNull(row.pctOff52wHigh)
        assertNull(row.atr14Pct)
        assertEquals(18, row.bars)
    }

    @Test
    fun `an unmeasured trend flag is null, never false`() {
        // null means there was no 200-day average to be above; false would mean we looked and it is
        // below. On a screen of "names in an uptrend" those two produce opposite rows.
        val row = Http.json.decodeFromString<MarketScanRow>(
            """{"symbol":"NEW","above_sma50":true,"above_sma200":null,"ma_stacked":null}""",
        )
        assertEquals(true, row.aboveSma50)
        assertNull("above_sma200 collapsed to false — an unearned bearish claim", row.aboveSma200)
        assertNull(row.maStacked)

        val omitted = Http.json.decodeFromString<MarketScanRow>("""{"symbol":"NEW"}""")
        assertNull(omitted.aboveSma50)
        assertNull(omitted.aboveSma200)
        assertNull(omitted.maStacked)
        assertNull(omitted.price)
    }

    @Test
    fun `a null unmeasured list is not the empty list`() {
        // [] = the producer checked and everything was measurable. null = no producer ever said.
        // Collapsing the second into the first reports a row of unknown provenance as fully measured.
        val unknown = Http.json.decodeFromString<MarketScanRow>("""{"symbol":"A","unmeasured":null}""")
        assertNull(unknown.unmeasured)
        val clean = Http.json.decodeFromString<MarketScanRow>("""{"symbol":"A","unmeasured":[]}""")
        assertEquals(emptyList<String>(), clean.unmeasured)
        val partial = Http.json.decodeFromString<MarketScanRow>("""{"symbol":"A","unmeasured":["adx14"]}""")
        assertEquals(listOf("adx14"), partial.unmeasured)
    }

    @Test
    fun `unreported coverage counters stay null rather than reading as a swept-and-empty market`() {
        val r = Http.json.decodeFromString<MarketScanResponse>("""{"results":[],"note":"n"}""")
        assertNull(r.scanned)
        assertNull(r.universeSize)
        assertNull(r.fetchFailed)
        assertNull(r.tooShort)
        assertNull(r.universeStale)
        assertNull(r.asOf)
        assertNull(r.generatedAt)
        assertTrue(r.results.isEmpty())
        // An explicit null must survive the same way an omission does.
        val explicit = Http.json.decodeFromString<MarketScanResponse>(
            """{"scanned":null,"universe_size":null,"fetch_failed":null,"too_short":null,
                "universe_stale":null,"as_of":null,"generated_at":null,"results":[]}""",
        )
        assertNull(explicit.scanned)
        assertNull(explicit.fetchFailed)
        assertNull(explicit.tooShort)
        assertNull(explicit.universeStale)
    }

    @Test
    fun `the two shortfall counters are separate fields and are never merged`() {
        val r = Http.json.decodeFromString<MarketScanResponse>(
            """{"universe_size":3147,"scanned":3113,"fetch_failed":12,"too_short":22,"results":[]}""",
        )
        assertEquals(12, r.fetchFailed)
        assertEquals(22, r.tooShort)
        // A network failure and a young listing are different facts; the sum is not a field.
        assertTrue(r.fetchFailed != r.tooShort)
    }

    @Test
    fun `breadth with no scan behind it reports nothing, not zero percent`() {
        // pct_above_sma50 = 0 is the most bearish breadth reading that exists. It must never be
        // manufactured out of an empty table.
        val b = Http.json.decodeFromString<MarketBreadth>(
            """{"available":false,"as_of":null,"n":0,"pct_above_sma50":null,"pct_above_sma200":null,
                "advancers":null,"decliners":null,"new_52w_highs":null,"near_52w_high":null,
                "near_52w_high_pct":-1.0,"new_52w_lows":null,"age_hours":null}""",
        )
        assertFalse(b.available)
        assertNull(b.pctAboveSma50)
        assertNull(b.pctAboveSma200)
        assertNull(b.advancers)
        assertNull(b.new52wHighs)
        assertNull(b.ageHours)
        // The threshold is a property of the server module, not of the scan, so it stays populated.
        assertEquals(-1.0, b.near52wHighPct!!, 1e-9)
    }

    @Test
    fun `a live breadth reading decodes, including the deliberately absent low side`() {
        val b = Http.json.decodeFromString<MarketBreadth>(
            """{"available":true,"as_of":"20260821","n":3113,"pct_above_sma50":58.4,
                "pct_above_sma200":67.6,"advancers":null,"decliners":null,"new_52w_highs":0,
                "near_52w_high":87,"near_52w_high_pct":1.0,"new_52w_lows":null,"age_hours":0.03}""",
        )
        assertTrue(b.available)
        assertEquals(3113, b.n)
        assertEquals(58.4, b.pctAboveSma50!!, 1e-9)
        // 0 new highs is a measured zero. null lows is "we cannot know" — the store keeps 90 nights,
        // not 52 weeks. Rendering the second as 0 would be a bullish all-clear out of a missing column.
        assertEquals(0, b.new52wHighs)
        assertNull(b.new52wLows)
        assertNull(b.advancers)
        assertEquals(0.03, b.ageHours!!, 1e-9)
    }

    @Test
    fun `a refused run reports null counters, not a scan of nothing`() {
        val r = Http.json.decodeFromString<MarketScanRunResponse>(
            """{"job":"market_scan","status":"refused","reason":"universe unavailable",
                "generated_at":1755800000.0,"as_of":"2026-08-21T22:00:00+00:00","session":"20260821",
                "session_is_holiday":false,"universe_symbols":null,"attempted":null,"scanned":null,
                "fetch_failed":null,"too_short":null,"suspect_series":null,"rows_written":null,
                "duration_s":null,"pruned":0}""",
        )
        assertEquals("refused", r.status)
        assertEquals("universe unavailable", r.reason)
        assertNull(r.scanned)
        assertNull(r.attempted)
        assertNull(r.rowsWritten)
        assertEquals(false, r.sessionIsHoliday)
    }

    @Test
    fun `a completed run reports its four counters separately`() {
        val r = Http.json.decodeFromString<MarketScanRunResponse>(
            """{"job":"market_scan","status":"ok","reason":null,"session":"20260821",
                "universe_symbols":3147,"attempted":3147,"scanned":3113,"fetch_failed":0,
                "too_short":34,"suspect_series":0,"rows_written":3113,"duration_s":49.9}""",
        )
        assertEquals("ok", r.status)
        assertNull(r.reason)
        assertEquals(3147, r.attempted)
        assertEquals(3113, r.scanned)
        assertEquals(0, r.fetchFailed)
        assertEquals(34, r.tooShort)
        assertEquals(0, r.suspectSeries)
        assertEquals(49.9, r.durationSeconds!!, 1e-9)
    }

    @Test
    fun `an unexpected run payload decodes to all-unknown instead of throwing`() {
        // The route may answer with a shape we did not model. Nothing here may crash the screen, and
        // nothing may invent a success.
        val r = Http.json.decodeFromString<MarketScanRunResponse>("""{"queued":true}""")
        assertNotNull(r)
        assertNull(r.status)
        assertNull(r.scanned)
    }
}
