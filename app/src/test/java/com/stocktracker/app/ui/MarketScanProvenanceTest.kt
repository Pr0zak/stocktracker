package com.stocktracker.app.ui

import com.stocktracker.app.ui.marketscan.MarketScanProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A nightly sweep must never read as a live market view.
 *
 * The scan is produced once, after the close, over ~3,100 names. Everything downstream of it is
 * hours old by construction, and the recurring defect in this app is exactly that: absent or stale
 * data rendered as a confident number. These are the three things the provenance line owes the
 * reader — when the data is from, how much of the market is behind it, and what was missed — plus
 * the rule that "missed" is never one number.
 */
class MarketScanProvenanceTest {

    private val now = 1_755_810_000_000L // epoch ms

    @Test
    fun `coverage names both halves`() {
        assertEquals("3,113 of 3,147 scanned", MarketScanProvenance.coverage(3113, 3147))
    }

    @Test
    fun `an unknown count is never printed as a number`() {
        // "0 of 3,147 scanned" would say the sweep ran and measured nothing — a market claim built
        // out of a missing key.
        assertEquals("3,147 in the universe", MarketScanProvenance.coverage(null, 3147))
        assertEquals("3,113 scanned", MarketScanProvenance.coverage(3113, null))
        assertNull(MarketScanProvenance.coverage(null, null))
    }

    @Test
    fun `fetch failures and short histories are reported side by side, never summed`() {
        val line = MarketScanProvenance.shortfall(12, 34)!!
        assertTrue(line.contains("12 fetch-failed"))
        assertTrue(line.contains("34 too short"))
        assertFalse("the two counters were merged into one number", line.contains("46"))
    }

    @Test
    fun `a known zero is stated and an absent counter is dropped`() {
        // "0 fetch-failed" is the evidence that the run was healthy. Hiding it makes a clean run and
        // an unreported one look the same.
        assertEquals("0 fetch-failed · 34 too short", MarketScanProvenance.shortfall(0, 34))
        assertEquals("34 too short", MarketScanProvenance.shortfall(null, 34))
        assertEquals("12 fetch-failed", MarketScanProvenance.shortfall(12, null))
        assertNull(MarketScanProvenance.shortfall(null, null))
    }

    @Test
    fun `the night key is rendered as a date`() {
        assertEquals("2026-08-21", MarketScanProvenance.asOfLabel("20260821"))
        assertEquals("2026-08-21T22:00:00+00:00", MarketScanProvenance.asOfLabel("2026-08-21T22:00:00+00:00"))
        assertNull(MarketScanProvenance.asOfLabel(null))
        assertNull(MarketScanProvenance.asOfLabel("   "))
    }

    @Test
    fun `age comes from the server timestamp and a missing one produces no age at all`() {
        val threeHoursAgo = (now - 3 * 3600_000L) / 1000.0
        assertEquals("3h ago", MarketScanProvenance.age(threeHoursAgo, now))
        assertNull(MarketScanProvenance.age(null, now))
        assertNull(MarketScanProvenance.age(0.0, now))
        assertNull(MarketScanProvenance.age(Double.NaN, now))
    }

    @Test
    fun `a timestamp from the future is clamped, not rendered as a negative age`() {
        val ahead = (now + 6 * 3600_000L) / 1000.0
        assertEquals("just now", MarketScanProvenance.age(ahead, now))
    }

    @Test
    fun `the summary carries the date, the age and both shortfall counters`() {
        val s = MarketScanProvenance.summary(
            asOf = "20260821",
            generatedAtEpochSeconds = (now - 3 * 3600_000L) / 1000.0,
            scanned = 3113, universeSize = 3147, fetchFailed = 0, tooShort = 34,
            universeStale = false, nowMs = now,
        )
        assertTrue(s.startsWith("Nightly scan"))
        assertTrue(s.contains("2026-08-21"))
        assertTrue(s.contains("3h ago"))
        assertTrue(s.contains("3,113 of 3,147 scanned"))
        assertTrue(s.contains("0 fetch-failed"))
        assertTrue(s.contains("34 too short"))
        assertFalse(s.contains("universe stale"))
    }

    @Test
    fun `a stale universe is called out separately from a stale scan`() {
        // The sweep can be minutes old and still be ranking a month-old idea of what is listed.
        val s = MarketScanProvenance.summary(
            asOf = "20260821", generatedAtEpochSeconds = now / 1000.0,
            scanned = 3113, universeSize = 3147, fetchFailed = 0, tooShort = 34,
            universeStale = true, nowMs = now,
        )
        assertTrue(s.contains("universe stale"))
    }

    @Test
    fun `with nothing known the line says so instead of going blank`() {
        // A blank where the provenance should be is precisely how absent data passes for current data.
        val s = MarketScanProvenance.summary(null, null, null, null, null, null, null, now)
        assertEquals("Nightly scan · coverage unknown", s)
    }
}
