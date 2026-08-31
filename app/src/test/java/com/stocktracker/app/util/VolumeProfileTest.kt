package com.stocktracker.app.util

import com.stocktracker.app.data.model.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The volume profile — where volume transacted, rather than when.
 *
 * The existing volume rendering is a per-bar histogram along the bottom of the plot; it has no price
 * dimension at all, so it cannot answer "is $47 a shelf, or did price just pass through it".
 *
 * TradingView's own `volume-profile` plugin example is a pure RENDERER: it takes {price, vol} pairs
 * and scales bar widths, and computes neither the point of control nor the value area. The walk
 * below comes from TradingView's published support documentation instead — start at the POC,
 * compare the next row above against the next below, add the larger, advance only that side, stop at
 * 70%.
 */
class VolumeProfileTest {

    private companion object {
        const val DAY = 86_400_000L

        fun bar(i: Int, close: Double, high: Double, low: Double, volume: Double, open: Double? = null) =
            PricePoint(i * DAY, close, volume = volume, high = high, low = low, open = open)
    }

    private fun profile(pts: List<PricePoint>, rows: Int = 64) =
        volumeProfile(pts, 0, pts.lastIndex, rows)

    // --- the measurement -------------------------------------------------------------------------

    /**
     * Twenty bars ranging $100-$110, with one price band traded far more heavily than the rest. The
     * point of control must land in that band — that is the entire claim of the feature.
     */
    @Test fun `the point of control lands where the volume actually traded`() {
        val pts = (0 until 20).map { i ->
            // Every bar spans the full range, but the heavy ones are narrow and sit at $104-$105.
            if (i % 2 == 0) bar(i, 105.0, 110.0, 100.0, volume = 1_000.0)
            else bar(i, 104.5, 105.0, 104.0, volume = 50_000.0)
        }
        val p = profile(pts)!!
        assertTrue("POC ${p.poc} should sit in the heavily traded band", p.poc in 103.5..105.5)
    }

    @Test fun `the value area brackets the point of control`() {
        val pts = (0 until 30).map { i -> bar(i, 100.0 + (i % 10), 105.0 + (i % 10), 95.0 + (i % 10), 10_000.0) }
        val p = profile(pts)!!
        assertTrue(p.valueAreaLow <= p.poc)
        assertTrue(p.valueAreaHigh >= p.poc)
        assertTrue(p.valueAreaLow < p.valueAreaHigh)
    }

    /** The share is the definition, so it is asserted rather than assumed. */
    @Test fun `the value area holds at least seventy percent of the window's volume`() {
        val pts = (0 until 40).map { i ->
            bar(i, 100.0 + (i % 13), 101.0 + (i % 13), 99.0 + (i % 13), 1_000.0 + i * 37.0)
        }
        val p = profile(pts)!!
        val inside = p.rows.filter { it.mid in p.valueAreaLow..p.valueAreaHigh }.sumOf { it.total }
        val total = p.rows.sumOf { it.total }
        assertTrue("value area held ${inside / total} of volume", inside / total >= VALUE_AREA_SHARE - 1e-9)
    }

    @Test fun `a single dominant band makes the value area narrow, not the whole range`() {
        val pts = (0 until 20).map { i ->
            if (i == 0) bar(i, 100.0, 200.0, 50.0, volume = 100.0)   // one wide, thin bar
            else bar(i, 120.0, 120.5, 119.5, volume = 100_000.0)     // the rest, all at $120
        }
        val p = profile(pts)!!
        assertTrue("POC at $120", p.poc in 119.0..121.0)
        assertTrue(
            "the value area should not span the whole $50-$200 range",
            (p.valueAreaHigh - p.valueAreaLow) < 30.0,
        )
    }

    @Test fun `up and down volume split on the close against the open`() {
        val upBars = (0 until 20).map { bar(it, close = 105.0, high = 106.0, low = 104.0, volume = 1_000.0, open = 100.0) }
        val u = profile(upBars)!!
        assertTrue("all volume is buying", u.rows.sumOf { it.up } > 0.0)
        assertEquals(0.0, u.rows.sumOf { it.down }, 1e-9)

        val downBars = (0 until 20).map { bar(it, close = 100.0, high = 106.0, low = 99.0, volume = 1_000.0, open = 105.0) }
        val d = profile(downBars)!!
        assertEquals(0.0, d.rows.sumOf { it.up }, 1e-9)
        assertTrue(d.rows.sumOf { it.down } > 0.0)
    }

    /**
     * A bar with no open cannot be attributed a direction. Its volume is real and must still count,
     * but splitting it evenly keeps the colouring neutral instead of asserting who was buying.
     */
    @Test fun `a bar with no open contributes volume without claiming a direction`() {
        val pts = (0 until 20).map { bar(it, 100.0, 101.0, 99.0, volume = 1_000.0, open = null) }
        val p = profile(pts)!!
        assertEquals(p.rows.sumOf { it.up }, p.rows.sumOf { it.down }, 1e-9)
        assertTrue(p.rows.sumOf { it.total } > 0.0)
    }

    @Test fun `volume is conserved — the profile redistributes it, it does not create it`() {
        val pts = (0 until 25).map { i -> bar(i, 100.0 + i, 101.0 + i, 99.0 + i, volume = 1_234.0) }
        val p = profile(pts)!!
        assertEquals(25 * 1_234.0, p.rows.sumOf { it.total }, 1e-6)
    }

    // --- refusal, rather than a degenerate profile ---------------------------------------------

    /**
     * The gate is per BAR, not per source. barHigh()/barLow() fall back to the close, which would
     * collapse every bar onto one bucket and draw a confident single-row profile from a series that
     * reports no ranges at all.
     */
    @Test fun `a close-only series produces no profile`() {
        val pts = (0 until 30).map { PricePoint(it * DAY, 100.0 + it, volume = 1_000.0) }
        assertNull(profile(pts))
    }

    @Test fun `one bar missing its range refuses the whole window`() {
        val pts = (0 until 30).map { i ->
            if (i == 15) PricePoint(i * DAY, 100.0, volume = 1_000.0)
            else bar(i, 100.0 + i, 101.0 + i, 99.0 + i, 1_000.0)
        }
        assertNull("a partial window profiles some sessions and presents them as all of them", profile(pts))
    }

    @Test fun `a bar with no volume refuses the window`() {
        val pts = (0 until 30).map { i ->
            if (i == 3) bar(i, 100.0, 101.0, 99.0, volume = 0.0) else bar(i, 100.0, 101.0, 99.0, 1_000.0)
        }
        assertNull(profile(pts))
    }

    @Test fun `an incoherent bar with high below low refuses the window`() {
        val pts = (0 until 30).map { i ->
            if (i == 7) bar(i, 100.0, 98.0, 102.0, 1_000.0) else bar(i, 100.0, 101.0, 99.0, 1_000.0)
        }
        assertNull(profile(pts))
    }

    @Test fun `a window with no price range at all refuses rather than dividing by zero`() {
        val pts = (0 until 20).map { bar(it, 100.0, 100.0, 100.0, 1_000.0) }
        assertNull(profile(pts))
    }

    @Test fun `a degenerate window refuses`() {
        val pts = (0 until 20).map { bar(it, 100.0 + it, 101.0 + it, 99.0 + it, 1_000.0) }
        assertNull("a single bar is not a profile", volumeProfile(pts, 5, 5))
        assertNull("an inverted window", volumeProfile(pts, 9, 3))
        assertNull("too few rows", volumeProfile(pts, 0, 19, rows = 1))
        assertNull("empty", volumeProfile(emptyList(), 0, 0))
    }

    @Test fun `the profile is scoped to the window, not the whole series`() {
        val pts = (0 until 40).map { i ->
            if (i < 20) bar(i, 50.0, 51.0, 49.0, 1_000.0) else bar(i, 200.0, 201.0, 199.0, 1_000.0)
        }
        val early = volumeProfile(pts, 0, 19)!!
        val late = volumeProfile(pts, 20, 39)!!
        assertTrue(early.poc < 60.0)
        assertTrue(late.poc > 190.0)
    }

    /**
     * The numbers will not match a TradingView chart, because that method uses lower-timeframe
     * intrabar data this app does not fetch. The profile carries that on itself so the difference is
     * disclosed rather than discovered.
     */
    @Test fun `the profile states the method it was built with`() {
        val pts = (0 until 20).map { bar(it, 100.0 + it, 101.0 + it, 99.0 + it, 1_000.0) }
        val p = profile(pts)!!
        assertNotNull(p.method)
        assertTrue(p.method.contains("not TradingView's"))
    }
}
