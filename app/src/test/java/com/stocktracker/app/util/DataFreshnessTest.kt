package com.stocktracker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The rule this file defends: an unknown age must never render as a fresh one.
 *
 * Three separate layers fall back to a cached quote when a fetch fails, so a dead network looks
 * exactly like a calm market — the number is still on screen and nothing about it changed. The
 * timestamp is the only thing that can tell those two apart, which makes its absent/zero case the
 * one that actually matters.
 */
class DataFreshnessTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val now = 1_754_000_000_000L        // fixed; nothing here may read the wall clock

    private fun ago(ms: Long) = now - ms

    // ------------------------------------------------------------------ the absent case

    @Test
    fun `a missing timestamp is stale and says so, never blank`() {
        val f = freshnessOf(0L, now, MarketPhase.REGULAR, zone)
        assertTrue(f.stale)
        assertFalse(f.known)
        assertNull(f.ageMs)
        assertEquals("Never updated", f.label)
    }

    @Test
    fun `an empty list is stale rather than vacuously fresh`() {
        // min() over an empty set has no answer; the tempting shortcut is to skip the line entirely,
        // which reads as "nothing to report" on a watchlist where nothing ever loaded.
        val f = listFreshness(emptyList(), now, MarketPhase.REGULAR, zone)
        assertTrue(f.stale)
        assertEquals("Never updated", f.label)
    }

    @Test
    fun `rows with no timestamp count as stale, not as absent`() {
        assertEquals(2, staleRowCount(listOf(0L, ago(30_000), 0L), now, MarketPhase.REGULAR))
    }

    // ------------------------------------------------------------------ the session governs the line

    @Test
    fun `six minutes is stale while the tape is moving`() {
        assertTrue(freshnessOf(ago(6 * 60_000), now, MarketPhase.REGULAR, zone).stale)
        assertTrue(freshnessOf(ago(6 * 60_000), now, MarketPhase.PRE, zone).stale)
        assertTrue(freshnessOf(ago(6 * 60_000), now, MarketPhase.AFTER, zone).stale)
    }

    @Test
    fun `six minutes is fine once the market is shut`() {
        // The last print IS the current price overnight. Flagging it would cry wolf every evening.
        assertFalse(freshnessOf(ago(6 * 60_000), now, MarketPhase.CLOSED, zone).stale)
    }

    @Test
    fun `a quote from before the last session is stale even though the market is shut`() {
        // The case the closed-market leniency must NOT swallow: 14h old means we have not reached the
        // source since before the close, so "+1.2% Today" beside it is last session's move.
        assertTrue(freshnessOf(ago(14 * 60 * 60_000L), now, MarketPhase.CLOSED, zone).stale)
    }

    @Test
    fun `four minutes is fine while the market is open`() {
        assertFalse(freshnessOf(ago(4 * 60_000), now, MarketPhase.REGULAR, zone).stale)
    }

    // ------------------------------------------------------------------ the list speaks for its rows

    @Test
    fun `a list reports its OLDEST row, not its newest`() {
        // One healthy row must not vouch for the dead ones beneath it.
        val f = listFreshness(
            listOf(ago(20_000), ago(9 * 60_000), ago(60_000)), now, MarketPhase.REGULAR, zone)
        assertTrue(f.stale)
        assertEquals("Updated 9m ago", f.label)
    }

    @Test
    fun `unknown rows do not drag the list timestamp to the epoch`() {
        // A 0L row is stale, but it must not be read as "updated in 1970" and print an absolute date.
        val f = listFreshness(listOf(0L, ago(60_000)), now, MarketPhase.REGULAR, zone)
        assertEquals("Updated 1m ago", f.label)
        assertEquals(1, staleRowCount(listOf(0L, ago(60_000)), now, MarketPhase.REGULAR))
    }

    // ------------------------------------------------------------------ wording

    @Test
    fun `recent reads as just now rather than zero minutes`() {
        assertEquals("Updated just now", freshnessOf(ago(10_000), now, MarketPhase.REGULAR, zone).label)
    }

    @Test
    fun `minutes then hours then an absolute time`() {
        assertEquals("4m ago", agePhrase(4 * 60_000, ago(4 * 60_000), zone))
        assertEquals("3h ago", agePhrase(3 * 60 * 60_000L, ago(3 * 60 * 60_000L), zone))
        // Past a day "31h ago" stops being something anyone can place.
        val old = 31 * 60 * 60_000L
        assertTrue(agePhrase(old, ago(old), zone).contains(","))
    }

    @Test
    fun `since carries the age alone so a failure can lead with the failure`() {
        // "Updated just now · refresh failed" asserts two opposing things; the failure surfaces write
        // "Refresh failed · last read ${since}" instead, which resolves in one reading.
        assertEquals("9m ago", freshnessOf(ago(9 * 60_000), now, MarketPhase.REGULAR, zone).since)
        assertEquals("just now", freshnessOf(ago(5_000), now, MarketPhase.REGULAR, zone).since)
    }

    @Test
    fun `since says never rather than reading as an age we do not have`() {
        assertEquals("never", freshnessOf(0L, now, MarketPhase.REGULAR, zone).since)
        assertEquals("never", listFreshness(emptyList(), now, MarketPhase.REGULAR, zone).since)
    }

    @Test
    fun `a future timestamp is clamped, not rendered as negative`() {
        // A clock change under us must not print "Updated -3m ago".
        val f = freshnessOf(now + 3 * 60_000, now, MarketPhase.REGULAR, zone)
        assertEquals(0L, f.ageMs)
        assertFalse(f.stale)
        assertEquals("Updated just now", f.label)
    }

    @Test
    fun `the boundary is exclusive so exactly-at-the-limit is not yet stale`() {
        assertFalse(freshnessOf(ago(MOVING_STALE_MS), now, MarketPhase.REGULAR, zone).stale)
        assertTrue(freshnessOf(ago(MOVING_STALE_MS + 1), now, MarketPhase.REGULAR, zone).stale)
    }
}
