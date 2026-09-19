package com.stocktracker.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DATA-9 — the rule behind restoring a persisted scan/VIX reading across a process restart, and
 * the TTL check that decides whether a refresh may skip the network. Both must work on a fixed
 * clock so a real reading's exact age is never in question.
 */
class MarketContextRestoreTest {

    private val now = 1_754_000_000_000L
    private fun ago(ms: Long) = now - ms

    // ------------------------------------------------------------------ restorable()

    @Test
    fun `a reading well within the window is restored`() {
        assertEquals("18.5", MarketContextRestore.restorable("18.5", ago(30 * 60_000), now))
    }

    @Test
    fun `a reading so old it must be discarded rather than shown is not restored`() {
        // Just past MAX_AGE_TO_SHOW_MS (4 days) — an install nobody has opened in a week must not
        // resurrect ancient numbers under a merely-stale label.
        val tooOld = ago(MarketContextRestore.MAX_AGE_TO_SHOW_MS + 60_000)
        assertNull(MarketContextRestore.restorable("18.5", tooOld, now))
    }

    @Test
    fun `a reading exactly at the discard boundary is still restorable`() {
        // The boundary itself belongs to "still worth showing" — only strictly PAST it is discarded.
        assertEquals("18.5", MarketContextRestore.restorable("18.5", ago(MarketContextRestore.MAX_AGE_TO_SHOW_MS), now))
    }

    @Test
    fun `a never-persisted timestamp is never restored, however the age math would read`() {
        assertNull("fetchedAtMs = 0 must not read as age zero", MarketContextRestore.restorable("18.5", 0L, now))
        assertNull(MarketContextRestore.restorable("18.5", -1L, now))
    }

    @Test
    fun `a null stored value has nothing to restore`() {
        assertNull(MarketContextRestore.restorable<String>(null, now, now))
    }

    @Test
    fun `a clock that moved backwards is treated as age zero, not a negative age`() {
        // now is BEFORE fetchedAtMs — a negative subtraction must not accidentally pass every
        // maxAgeMs check (it always would, being < any positive bound) for the wrong reason.
        val future = now + 60_000
        assertEquals("18.5", MarketContextRestore.restorable("18.5", future, now))
    }

    @Test
    fun `a caller-supplied window overrides the default`() {
        assertNull(MarketContextRestore.restorable("18.5", ago(90_000), now, maxAgeMs = 60_000))
        assertEquals("18.5", MarketContextRestore.restorable("18.5", ago(30_000), now, maxAgeMs = 60_000))
    }

    // ------------------------------------------------------------------ withinTtl()

    @Test
    fun `a fresh-enough reading is within its TTL`() {
        assertTrue(MarketContextRestore.withinTtl(ago(10_000), now, maxAgeMs = 30_000))
    }

    @Test
    fun `a reading past its TTL is not`() {
        assertFalse(MarketContextRestore.withinTtl(ago(30_001), now, maxAgeMs = 30_000))
    }

    @Test
    fun `never having fetched anything is never within TTL`() {
        assertFalse(MarketContextRestore.withinTtl(0L, now, maxAgeMs = Long.MAX_VALUE))
    }
}
