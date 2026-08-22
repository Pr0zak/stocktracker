package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.ScanLatest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "There is no scan" must not decode into "the scan found nothing".
 *
 * `Http.json` sets `coerceInputValues = true`, so a non-nullable `List<ScanResult> = emptyList()`
 * swallows an explicit `null` from the server into an empty list — and the dip radar then printed
 * "No dips right now — nothing you track is notably off its highs" at a user whose scan service was
 * down. The null has to survive the deserializer for the screen to have anything to branch on.
 */
class ScanLatestNullsTest {

    /** Exactly what GET /scan/latest returns when the server holds no scan. */
    private val unavailable = """
        {"generated_at":null,"scan_available":false,"unavailable_reason":"no scan has run yet",
         "results":null,"flips":null,"crossed_below_200wma":null,"dip_alerts":null,
         "dip_rejects":null,"dip_counts":null,"date_alerts":null,"total_cost_usd":null}
    """.trimIndent()

    @Test
    fun `an unavailable scan keeps its nulls instead of becoming empty lists`() {
        val scan = Http.json.decodeFromString<ScanLatest>(unavailable)
        assertNull("a null results list was coerced to empty — the exact bug", scan.results)
        assertNull(scan.flips)
        assertNull(scan.dipAlerts)
        assertNull(scan.dipCounts)
        assertEquals(false, scan.scanAvailable)
        assertEquals("no scan has run yet", scan.unavailableReason)
        assertFalse("an absent scan claimed to be a scan", scan.hasScan)
    }

    @Test
    fun `a scan that ran and found nothing is a different answer`() {
        val scan = Http.json.decodeFromString<ScanLatest>(
            """{"generated_at":1.0,"scan_available":true,"results":[],
                "dip_counts":{"scanned":34,"qualified":0,"near_miss":3,"nowhere_near":30,"unmeasured":1}}""",
        )
        assertTrue(scan.hasScan)
        assertNotNull(scan.results)
        assertEquals(0, scan.results!!.size)
        assertEquals(34, scan.dipCounts?.scanned)
        assertEquals(0, scan.dipCounts?.qualified)
        assertEquals(1, scan.dipCounts?.unmeasured)
    }

    @Test
    fun `an older backend that sends no flag is still a scan`() {
        // Pre-SWT-5 servers send results with no `scan_available`. Decoding that as "unavailable"
        // would break the radar for anyone who hasn't redeployed the backend.
        val scan = Http.json.decodeFromString<ScanLatest>(
            """{"generated_at":1.0,"results":[{"symbol":"AAPL","dip":"pullback_5"}]}""",
        )
        assertNull(scan.scanAvailable)
        assertTrue(scan.hasScan)
        assertEquals(1, scan.results?.size)
    }

    @Test
    fun `a stale scan file reports its missing counters as absent, never as zeros`() {
        // The deployed CT keeps serving yesterday's file until the next nightly run. It has results
        // but no reject audit, and "0 near misses" would be an answer to a question nobody asked.
        val scan = Http.json.decodeFromString<ScanLatest>(
            """{"generated_at":1.0,"scan_available":true,"results":[{"symbol":"AAPL"}],
                "dip_rejects":null,"dip_counts":null}""",
        )
        assertTrue(scan.hasScan)
        assertNull(scan.dipRejects)
        assertNull(scan.dipCounts)
    }

    @Test
    fun `reject rows decode with their reason and a nullable gap`() {
        val scan = Http.json.decodeFromString<ScanLatest>(
            """{"scan_available":true,"results":[],
                "dip_rejects":{
                  "near_miss":[{"symbol":"MSFT","reason":"4.2% off its 3-month high — needs 5%, 0.8 points short",
                                "gap_pp":0.8,"pct_off_recent_high":-4.2,"pct_off_52w_high":-6.0}],
                  "nowhere_near":[],
                  "unmeasured":[{"symbol":"XYZ","reason":"not measured — an upstream source failed",
                                 "gap_pp":null,"pct_off_recent_high":null,"pct_off_52w_high":null}]}}""",
        )
        val near = scan.dipRejects?.nearMiss?.single()
        assertEquals("MSFT", near?.symbol)
        assertTrue(near!!.reason.contains("0.8 points short"))
        assertEquals(0.8, near.gapPp!!, 1e-9)
        val un = scan.dipRejects?.unmeasured?.single()
        assertNull("an unmeasurable gap became a real-looking 0.0", un?.gapPp)
        assertNull(un?.pctOffRecentHigh)
    }

    @Test
    fun `a per-row unmeasured symbol does not decode as measured`() {
        val scan = Http.json.decodeFromString<ScanLatest>(
            """{"scan_available":true,"results":[
                 {"symbol":"AAPL","dip":null,"dip_measured":false,
                  "dip_reject_reason":"no usable price history — this symbol was not measured for a dip",
                  "dip_near_miss":null,"dip_gap_pp":null,"pct_off_recent_high":null}]}""",
        )
        val row = scan.results!!.single()
        assertEquals(false, row.dipMeasured)
        assertNull("a near-miss verdict was invented for an unmeasured name", row.dipNearMiss)
        assertNull("a missing high decoded as 'sitting exactly on its 3-month high'", row.pctOffRecentHigh)
        assertTrue(row.dipRejectReason!!.contains("not measured"))
    }
}
