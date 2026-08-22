package com.stocktracker.app.ui

import com.stocktracker.app.data.remote.DipCounts
import com.stocktracker.app.data.remote.DipReject
import com.stocktracker.app.data.remote.DipRejects
import com.stocktracker.app.data.remote.ScanLatest
import com.stocktracker.app.data.remote.ScanResult
import com.stocktracker.app.ui.watchlist.DipRadar
import com.stocktracker.app.ui.watchlist.DipRadarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The dip radar is only allowed to say "there are no dips" when it is holding a scan that ran.
 *
 * The screen had ONE empty state, reached by `scan?.results ?: emptyList()`, so a dead scan service
 * produced "No dips right now — nothing you track is notably off its highs". That is a claim about
 * the market manufactured out of a network error, and it is the worst possible direction for the
 * error to point: it stops the user looking. These pin the four outcomes apart.
 */
class DipRadarStateTest {

    private fun ran(
        results: List<ScanResult> = emptyList(),
        rejects: DipRejects? = null,
        counts: DipCounts? = null,
    ) = ScanLatest(
        generatedAt = 1.0, scanAvailable = true, results = results,
        dipRejects = rejects, dipCounts = counts,
    )

    @Test
    fun `a failed fetch is an error state, never an empty market`() {
        val s = DipRadar.state(null, IOException("Unable to resolve host"), configured = true)
        assertTrue(s is DipRadarState.Unreachable)
        assertEquals("Unable to resolve host", (s as DipRadarState.Unreachable).message)
    }

    @Test
    fun `a null response with no exception is still not a scan`() {
        // latestScan() returns null rather than throwing in some paths; treating that as "no dips"
        // is the same lie arriving by a quieter route.
        assertTrue(DipRadar.state(null, null, configured = true) is DipRadarState.Unreachable)
    }

    @Test
    fun `an unconfigured service is its own state`() {
        assertTrue(DipRadar.state(null, null, configured = false) is DipRadarState.NotConfigured)
    }

    @Test
    fun `the server saying it has no scan is not the server saying the market is calm`() {
        val payload = ScanLatest(
            scanAvailable = false, unavailableReason = "the stored scan could not be read", results = null,
        )
        val s = DipRadar.state(payload, null, configured = true)
        assertTrue(s is DipRadarState.NoScan)
        assertEquals("the stored scan could not be read", (s as DipRadarState.NoScan).reason)
    }

    @Test
    fun `a scan that claims to exist but hands over no results is not usable either`() {
        val s = DipRadar.state(ScanLatest(scanAvailable = true, results = null), null, configured = true)
        assertTrue(s is DipRadarState.NoScan)
    }

    @Test
    fun `only a scan that ran reaches Ready, and it carries the counters`() {
        val s = DipRadar.state(
            ran(
                results = listOf(ScanResult(symbol = "AAPL", dip = "mega_dip")),
                counts = DipCounts(scanned = 34, qualified = 1, nearMiss = 3, nowhereNear = 29, unmeasured = 1),
            ),
            null, configured = true,
        ) as DipRadarState.Ready
        assertEquals(1, s.dips.size)
        assertEquals("1 of 34 scanned qualified", DipRadar.coverage(s.counts))
        assertEquals("3 near miss · 29 nowhere near · 1 unmeasured", DipRadar.breakdown(s.counts))
    }

    @Test
    fun `dips come out most-severe first and unknown tiers sort last, not away`() {
        val s = DipRadar.state(
            ran(
                results = listOf(
                    ScanResult(symbol = "A", dip = "pullback_5"),
                    ScanResult(symbol = "B", dip = "some_future_tier"),
                    ScanResult(symbol = "C", dip = "mega_dip"),
                    ScanResult(symbol = "D", dip = null),
                ),
            ),
            null, configured = true,
        ) as DipRadarState.Ready
        assertEquals(listOf("C", "A", "B"), s.dips.map { it.symbol })
    }

    @Test
    fun `unmeasured names are surfaced even when there ARE dips to show`() {
        // The whole point of the separate counter: the list in front of the user is incomplete, and
        // that stays true when it is non-empty.
        val counts = DipCounts(scanned = 10, qualified = 2, nearMiss = 1, nowhereNear = 6, unmeasured = 1)
        assertEquals(
            "1 name couldn't be measured — this list may be incomplete.",
            DipRadar.incompleteNote(counts),
        )
        assertEquals(
            "2 names couldn't be measured — this list may be incomplete.",
            DipRadar.incompleteNote(counts.copy(unmeasured = 2)),
        )
    }

    @Test
    fun `a complete scan says nothing about unmeasured names, and an absent counter invents none`() {
        assertNull(DipRadar.incompleteNote(DipCounts(scanned = 10, qualified = 0, unmeasured = 0)))
        assertNull("a missing counter was read as a warning", DipRadar.incompleteNote(DipCounts(scanned = 10)))
        assertNull(DipRadar.incompleteNote(null))
    }

    @Test
    fun `a scan older than the counters derives only what it can prove`() {
        // scanned and qualified are recoverable from the rows themselves. The other three are not,
        // and printing "0 near miss" about a scan that never measured near-misses is a fabrication.
        val scan = ran(results = listOf(ScanResult(symbol = "A", dip = "oversold"), ScanResult(symbol = "B")))
        val s = DipRadar.state(scan, null, configured = true) as DipRadarState.Ready
        assertEquals(2, s.counts.scanned)
        assertEquals(1, s.counts.qualified)
        assertNull(s.counts.nearMiss)
        assertNull(s.counts.nowhereNear)
        assertNull(s.counts.unmeasured)
        assertEquals("1 of 2 scanned qualified", DipRadar.coverage(s.counts))
        assertNull("absent reject counters were printed as zeros", DipRadar.breakdown(s.counts))
    }

    @Test
    fun `a scan predating the reject audit says so instead of showing an empty audit`() {
        val s = DipRadar.state(ran(results = listOf(ScanResult(symbol = "A"))), null, configured = true)
                as DipRadarState.Ready
        assertTrue(s.nearMiss.isEmpty() && s.nowhereNear.isEmpty() && s.unmeasured.isEmpty())
        assertEquals(false, s.rejectsAvailable)
        assertTrue(DipRadar.staleNote(false)!!.contains("before the reject audit"))
        assertNull("a scan WITH an audit was labelled stale", DipRadar.staleNote(true))
    }

    @Test
    fun `a real reject audit comes through split three ways`() {
        val s = DipRadar.state(
            ran(
                results = listOf(ScanResult(symbol = "A")),
                rejects = DipRejects(
                    nearMiss = listOf(DipReject("MSFT", "4.2% off its 3-month high — needs 5%, 0.8 points short", 0.8)),
                    nowhereNear = listOf(DipReject("NVDA", "1.0% off its 3-month high — no dip on any measure", 4.0)),
                    unmeasured = listOf(DipReject("XYZ", "not measured — an upstream source failed")),
                ),
            ),
            null, configured = true,
        ) as DipRadarState.Ready
        assertEquals(true, s.rejectsAvailable)
        assertEquals(listOf("MSFT"), s.nearMiss.map { it.symbol })
        assertEquals(listOf("NVDA"), s.nowhereNear.map { it.symbol })
        // Kept out of "nowhere near": these were not judged dip-free, they were never judged at all.
        assertEquals(listOf("XYZ"), s.unmeasured.map { it.symbol })
    }

    @Test
    fun `the coverage line degrades instead of printing a zero it does not know`() {
        assertEquals("7 qualified", DipRadar.coverage(DipCounts(qualified = 7)))
        assertEquals("34 scanned", DipRadar.coverage(DipCounts(scanned = 34)))
        assertNull(DipRadar.coverage(DipCounts()))
        assertNull(DipRadar.coverage(null))
    }

    @Test
    fun `a known zero reject count is still printed`() {
        // "0 unmeasured" is the evidence the scan was complete; hiding it makes a complete scan and
        // an unreported one look the same.
        assertEquals("0 unmeasured", DipRadar.breakdown(DipCounts(unmeasured = 0)))
    }
}
