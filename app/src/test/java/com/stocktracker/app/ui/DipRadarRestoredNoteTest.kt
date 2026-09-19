package com.stocktracker.app.ui

import com.stocktracker.app.data.remote.ScanLatest
import com.stocktracker.app.ui.watchlist.DipRadar
import com.stocktracker.app.ui.watchlist.DipRadarState
import com.stocktracker.app.util.RESTORED_DISCLOSURE_AFTER_MS
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DATA-9 — the dip strip's disclosure for a [DipRadarState.Ready] reading that has not been
 * reconfirmed by a successful fetch in a long while: restored from disk on a cold start, or one
 * that has simply failed to refresh for a long offline stretch. Distinct from
 * [DipRadarState.Unreachable]'s own message, which [DipRadar.holdThroughBlip] already produces for
 * a same-session failure — this fires even before any refresh has been attempted this session.
 */
class DipRadarRestoredNoteTest {

    private val now = 1_754_000_000_000L
    private fun ago(ms: Long) = now - ms

    private val ready = DipRadar.state(
        scan = ScanLatest(scanAvailable = true, results = emptyList()),
        error = null,
        configured = true,
    )

    @Test
    fun `a recently confirmed reading needs no note`() {
        assertNull(DipRadar.restoredNote(ready, ago(60_000), now))
    }

    @Test
    fun `a reading unconfirmed past the disclosure window says its age`() {
        val note = DipRadar.restoredNote(ready, ago(RESTORED_DISCLOSURE_AFTER_MS + 60_000), now)
        assertTrue(note!!.contains("46m ago"))
        assertTrue(note.contains("not yet refreshed this session"))
    }

    @Test
    fun `never having been confirmed at all is not a restored reading to caveat`() {
        // fetchedAtMs = 0 means this session has genuinely never held a scan — that is Loading's
        // job to say, not a stale-age note's.
        assertNull(DipRadar.restoredNote(ready, 0L, now))
    }

    @Test
    fun `a non-Ready state has no dips to caveat the age of`() {
        assertNull(DipRadar.restoredNote(DipRadarState.Loading, ago(RESTORED_DISCLOSURE_AFTER_MS * 10), now))
        assertNull(DipRadar.restoredNote(DipRadarState.NotConfigured, ago(RESTORED_DISCLOSURE_AFTER_MS * 10), now))
    }
}
