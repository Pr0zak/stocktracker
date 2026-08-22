package com.stocktracker.app.ui

import com.stocktracker.app.data.remote.DipCounts
import com.stocktracker.app.ui.watchlist.DipEntry
import com.stocktracker.app.ui.watchlist.DipRadar
import com.stocktracker.app.ui.watchlist.DipRadarState
import com.stocktracker.app.ui.watchlist.DipStripTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SWT-14 — the watchlist's dip STRIP, which until now could render exactly one of the radar's four
 * states and simply vanished for the other three.
 *
 * The vanishing is the bug. A strip that isn't there looks identical to a market with nothing on
 * sale, so a user who never opens the full radar screen could not tell "we checked and nothing
 * qualified" from "the scan service has been down for a week". These pin each state to a distinct
 * notice, and — the load-bearing half — pin the reassuring sentence to Ready alone.
 */
class DipStripTest {

    private val ready = DipRadarState.Ready(
        dips = listOf(DipEntry("PYPL", "mega_dip", -31.0, -34.0)),
        nearMiss = emptyList(),
        nowhereNear = emptyList(),
        unmeasured = emptyList(),
        counts = DipCounts(scanned = 34, qualified = 1),
        rejectsAvailable = true,
    )
    private val readyEmpty = ready.copy(dips = emptyList(), counts = DipCounts(scanned = 34, qualified = 0))

    private val nonReady = listOf(
        DipRadarState.Loading,
        DipRadarState.Unreachable("Unable to resolve host"),
        DipRadarState.NotConfigured,
        DipRadarState.NoScan("no scan has been written yet"),
    )

    @Test
    fun `every non-Ready state gets its own notice`() {
        val titles = nonReady.map { DipRadar.strip(it)!!.title }
        assertEquals("two states shared a title", titles.size, titles.toSet().size)
        titles.forEach { assertTrue("a state rendered a blank notice", it.isNotBlank()) }
    }

    @Test
    fun `a Ready scan has no notice - the dips are the content`() {
        assertNull(DipRadar.strip(ready))
        assertNull(DipRadar.strip(readyEmpty))
    }

    @Test
    fun `no non-Ready state may say anything reassuring about dips`() {
        nonReady.forEach { s ->
            val note = DipRadar.strip(s)!!
            val text = (note.title + " " + (note.detail ?: "")).lowercase()
            assertFalse("$s claimed something about dips: $text", text.contains("no dips"))
            assertFalse("$s claimed the market is calm: $text", text.contains("off its highs"))
            // The calming sentence itself is unreachable from here, by construction.
            assertNull("$s produced the reassuring sentence", DipRadar.calm(s))
        }
    }

    @Test
    fun `the reassuring sentence is reachable from Ready and only from Ready`() {
        assertNotNull(DipRadar.calm(readyEmpty))
        assertTrue(DipRadar.calm(readyEmpty)!!.contains("No dips right now"))
        // Ready WITH dips isn't calm either — there is a list to show instead.
        assertNull(DipRadar.calm(ready))
    }

    @Test
    fun `an unreachable scan is a warning and offers a retry`() {
        val note = DipRadar.strip(DipRadarState.Unreachable("Unable to resolve host"))!!
        assertEquals(DipStripTone.WARN, note.tone)
        assertTrue(note.retryable)
        // The exception's own words, not a paraphrase.
        assertEquals("Unable to resolve host", note.detail)
    }

    @Test
    fun `an unreachable scan with no message still renders the state`() {
        val note = DipRadar.strip(DipRadarState.Unreachable(null))!!
        assertEquals(DipStripTone.WARN, note.tone)
        assertNull(note.detail)
        assertTrue(note.title.isNotBlank())
    }

    @Test
    fun `no scan carries the server's own reason and is not a warning`() {
        val note = DipRadar.strip(DipRadarState.NoScan("no scan has been written yet"))!!
        assertEquals("No scan has run yet", note.title)
        assertEquals("No scan has been written yet", note.detail)
        assertEquals(DipStripTone.INFO, note.tone)
        assertTrue("a missing scan can arrive later — the retry has to be there", note.retryable)
    }

    @Test
    fun `an unconfigured radar points at the setting and offers no pointless retry`() {
        val note = DipRadar.strip(DipRadarState.NotConfigured)!!
        assertEquals(DipStripTone.INFO, note.tone)
        assertFalse("retrying a service that was never configured cannot help", note.retryable)
        assertTrue(note.detail!!.contains("Settings"))
    }

    @Test
    fun `a fetch in flight is never a warning`() {
        val note = DipRadar.strip(DipRadarState.Loading)!!
        assertEquals(DipStripTone.WORKING, note.tone)
        assertNotEquals(DipStripTone.WARN, note.tone)
        assertFalse(note.retryable)
    }

    @Test
    fun `the collapsed summary chip never claims calm without a scan`() {
        assertEquals("1 dip", DipRadar.chip(ready))
        assertEquals("2 dips", DipRadar.chip(ready.copy(dips = ready.dips + DipEntry("NKE", "oversold", -12.0, -20.0))))
        // "no dips" is a claim, and it is only available from a scan that ran.
        assertEquals("no dips", DipRadar.chip(readyEmpty))
        assertEquals("dips unavailable", DipRadar.chip(DipRadarState.Unreachable(null)))
        assertEquals("no scan yet", DipRadar.chip(DipRadarState.NoScan(null)))
        // Nothing honest fits in two words for these, so they contribute nothing to the line.
        assertNull(DipRadar.chip(DipRadarState.Loading))
        assertNull(DipRadar.chip(DipRadarState.NotConfigured))
    }

    // ---- holding a good scan through a blip

    private fun ready(vararg syms: String) = DipRadarState.Ready(
        dips = syms.map { DipEntry(it, "pullback_5", -6.0, -8.0) },
        nearMiss = emptyList(), nowhereNear = emptyList(), unmeasured = emptyList(),
        counts = DipCounts(), rejectsAvailable = true,
    )

    @Test
    fun `a failed refresh keeps the scan we already hold and says it is stale`() {
        // One dropped request must not discard a working dip list. Before this, the view model
        // reassigned the state unconditionally, so Ready became Unreachable and the dips vanished —
        // information the app already had, thrown away over a blip.
        val held = ready("AAPL", "MSFT")
        val upd = DipRadar.holdThroughBlip(held, DipRadarState.Unreachable("timeout"))

        assertEquals(held, upd.state)
        assertEquals("timeout", upd.stale)
    }

    @Test
    fun `a failure with no message still marks the held scan stale`() {
        val upd = DipRadar.holdThroughBlip(ready("AAPL"), DipRadarState.Unreachable(null))
        assertNotNull(upd.stale)
    }

    @Test
    fun `a successful refresh replaces the held scan and clears the staleness`() {
        val upd = DipRadar.holdThroughBlip(ready("AAPL"), ready("NVDA"))
        assertEquals(listOf("NVDA"), (upd.state as DipRadarState.Ready).dips.map { it.symbol })
        assertNull(upd.stale)
    }

    @Test
    fun `the server saying it has no scan WINS over the scan we are holding`() {
        // NoScan is the server ANSWERING, not failing: it is newer information than the scan we
        // hold, so continuing to show ours would be preferring stale data to a fresh fact.
        val upd = DipRadar.holdThroughBlip(ready("AAPL"), DipRadarState.NoScan("rebuilding"))
        assertTrue(upd.state is DipRadarState.NoScan)
        assertNull(upd.stale)
    }

    @Test
    fun `disconnecting the backend drops the dips rather than stranding them`() {
        // Not a failure — the user removed the URL. Keeping dips from a service they have
        // disconnected would strand data with no way to refresh it.
        val upd = DipRadar.holdThroughBlip(ready("AAPL"), DipRadarState.NotConfigured)
        assertEquals(DipRadarState.NotConfigured, upd.state)
        assertNull(upd.stale)
    }

    @Test
    fun `a refresh in flight does not blank a list that is still readable`() {
        val held = ready("AAPL")
        val upd = DipRadar.holdThroughBlip(held, DipRadarState.Loading)
        assertEquals(held, upd.state)
        assertNull("still loading is not staleness — nothing has failed yet", upd.stale)
    }

    @Test
    fun `holding nothing yet means the incoming state simply wins`() {
        for (incoming in listOf(
            DipRadarState.Unreachable("x"), DipRadarState.NotConfigured,
            DipRadarState.NoScan(null), ready("AAPL"),
        )) {
            val upd = DipRadar.holdThroughBlip(DipRadarState.Loading, incoming)
            assertEquals(incoming, upd.state)
            assertNull(upd.stale)
        }
    }
}
