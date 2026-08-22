package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.MarketBreadth
import com.stocktracker.app.data.remote.MarketScanResponse
import com.stocktracker.app.data.remote.MarketScanRow
import com.stocktracker.app.data.remote.MarketScanRunResponse
import com.stocktracker.app.data.remote.MarketScanSymbolResponse
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

    // ------------------------------------------------- SWT-4: the ranks that ride on those rows

    @Test
    fun `a ranked row carries every percentile beside the number it ranks`() {
        val r = Http.json.decodeFromString<MarketScanResponse>(
            """{"as_of":"20260821","percentiles_over":3101,"total_matching":812,"results":[
                 {"symbol":"NVDA","rel_volume":0.78,"rsi14":63.2,"dollar_volume_20d":2.7e10,
                  "rel_strength_3mo_pctile":86.0,"rel_volume_pctile":59.3,"adr20_pct_pctile":71.2,
                  "adx14_pctile":88.8,"mom_20d_pctile":90.1,"mom_60d_pctile":93.4,
                  "rsi14_pctile":77.7,"pct_off_52w_high_pctile":99.1,
                  "ema20_slope_pct_pctile":81.0,"dollar_volume_20d_pctile":100.0}]}""",
        )
        // The denominator is the WHOLE night, not the filtered slice — 3,101, not 812.
        assertEquals(3101, r.percentilesOver)
        val nvda = r.results.first()
        assertEquals(59.3, nvda.relVolumePctile!!, 1e-9)
        assertEquals(86.0, nvda.relStrength3moPctile!!, 1e-9)
        assertEquals(71.2, nvda.adr20PctPctile!!, 1e-9)
        assertEquals(88.8, nvda.adx14Pctile!!, 1e-9)
        assertEquals(90.1, nvda.mom20dPctile!!, 1e-9)
        assertEquals(93.4, nvda.mom60dPctile!!, 1e-9)
        assertEquals(77.7, nvda.rsi14Pctile!!, 1e-9)
        assertEquals(99.1, nvda.pctOff52wHighPctile!!, 1e-9)
        assertEquals(81.0, nvda.ema20SlopePctPctile!!, 1e-9)
        assertEquals(100.0, nvda.dollarVolume20dPctile!!, 1e-9)
        // The raw measurement survives alongside its rank: the rank is a rank OF something, and one
        // without the other is half a reading.
        assertEquals(0.78, nvda.relVolume!!, 1e-9)
    }

    @Test
    fun `an unranked night stays null instead of putting every name at the bottom of the market`() {
        // A night stored before the ranking pass existed, or one whose backfill has not run. Both
        // send no percentile at all, and 0.0 would say "worst in the market" about all 3,101 names.
        val omitted = Http.json.decodeFromString<MarketScanRow>(
            """{"symbol":"NVDA","rel_volume":0.78,"rsi14":63.2}""",
        )
        assertNull(omitted.relVolumePctile)
        assertNull(omitted.rsi14Pctile)
        assertNull(omitted.dollarVolume20dPctile)
        // An explicit null must survive exactly as an omission does — `coerceInputValues = true`
        // would swallow both into a zero if any of these fields were non-nullable.
        val explicit = Http.json.decodeFromString<MarketScanRow>(
            """{"symbol":"NVDA","rel_volume":0.78,"rel_volume_pctile":null,"rsi14_pctile":null,
                "adx14_pctile":null,"mom_20d_pctile":null,"mom_60d_pctile":null,
                "rel_strength_3mo_pctile":null,"adr20_pct_pctile":null,
                "pct_off_52w_high_pctile":null,"ema20_slope_pct_pctile":null,
                "dollar_volume_20d_pctile":null}""",
        )
        assertNull(explicit.relVolumePctile)
        assertNull(explicit.rsi14Pctile)
        assertNull(explicit.adx14Pctile)
        assertNull(explicit.mom20dPctile)
        assertNull(explicit.mom60dPctile)
        assertNull(explicit.relStrength3moPctile)
        assertNull(explicit.adr20PctPctile)
        assertNull(explicit.pctOff52wHighPctile)
        assertNull(explicit.ema20SlopePctPctile)
        assertNull(explicit.dollarVolume20dPctile)
        assertEquals(0.78, explicit.relVolume!!, 1e-9)
        // A row measured on a metric the whole night was too sparse to rank: the number is there,
        // the rank is not, and they must not be confused for each other.
        assertNotNull(explicit.relVolume)
    }

    @Test
    fun `a measured rank of zero is kept, because it is a reading`() {
        // 0.0 = the lowest measured value in the night's cross-section. Only the ABSENT case is
        // null; dropping a real zero would hide a reading as surely as inventing one fabricates it.
        val row = Http.json.decodeFromString<MarketScanRow>(
            """{"symbol":"DEAD","rel_volume":0.01,"rel_volume_pctile":0.0}""",
        )
        assertEquals(0.0, row.relVolumePctile!!, 1e-9)
    }

    @Test
    fun `an unreported population is null rather than a count borrowed from the slice`() {
        val r = Http.json.decodeFromString<MarketScanResponse>(
            """{"total_matching":812,"limit":50,"results":[],"percentiles_over":null}""",
        )
        assertNull(r.percentilesOver)
        assertEquals(812, r.totalMatching)
    }

    @Test
    fun `the symbol route is an envelope, and decoding it as a row loses every measurement`() {
        val json = """
            {"symbol":"NVDA","as_of":"20260819","latest_scan_date":"20260821","is_latest_night":false,
             "percentiles_over":3098,"generated_at":1755800000.0,
             "row":{"symbol":"NVDA","price":184.2,"rsi14":63.2,"rel_volume":0.78,
                    "rsi14_pctile":77.7,"rel_volume_pctile":59.3},
             "percentiles":{"rsi14":77.7,"rel_volume":59.3,"adx14":null},
             "note":"a rank, not a score"}
        """.trimIndent()
        val r = Http.json.decodeFromString<MarketScanSymbolResponse>(json)

        assertEquals("NVDA", r.symbol)
        // The row is from an older night than the latest scan — the ranks belong to THAT night's
        // cross-section, so both dates have to survive the decode.
        assertEquals("20260819", r.asOf)
        assertEquals("20260821", r.latestScanDate)
        assertEquals(false, r.isLatestNight)
        assertEquals(3098, r.percentilesOver)
        assertEquals(63.2, r.row!!.rsi14!!, 1e-9)
        assertEquals(77.7, r.row!!.rsi14Pctile!!, 1e-9)
        assertEquals(77.7, r.percentile("rsi14")!!, 1e-9)
        // A metric the night could not rank: present as a key, null as a value. Never a zero, and
        // never absent in a way that a caller could read as "the key was there and it was low".
        assertTrue(r.percentiles!!.containsKey("adx14"))
        assertNull(r.percentile("adx14"))
        assertNull(r.percentile("mom_20d"))

        // Why the envelope has its own type: the flat decode this client used to do matches only the
        // top-level "symbol" and reports a name the scan measured nothing about.
        val flat = Http.json.decodeFromString<MarketScanRow>(json)
        assertEquals("NVDA", flat.symbol)
        assertNull("the flat decode silently drops every measurement", flat.rsi14)
        assertNull(flat.rsi14Pctile)
    }

    @Test
    fun `a symbol envelope with no percentiles at all reports absence, not an empty ranking`() {
        val r = Http.json.decodeFromString<MarketScanSymbolResponse>(
            """{"symbol":"NVDA","row":{"symbol":"NVDA","rsi14":63.2}}""",
        )
        assertNull("null percentiles must not collapse into an empty map", r.percentiles)
        assertNull(r.percentile("rsi14"))
        assertNull(r.percentilesOver)
        assertNull(r.isLatestNight)
        assertEquals(63.2, r.row!!.rsi14!!, 1e-9)
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
