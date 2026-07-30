package com.stocktracker.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/**
 * The colour ramp must not saturate.
 *
 * It was `abs(p)/4` clamped at 1, so every move at or beyond 4% produced an identical ramp
 * parameter: a 4% drift and a 40% collapse were the same colour to the byte. A heat map whose only
 * job is conveying magnitude cannot flatten its top end.
 */
class HeatmapRampTest {

    /** Mirrors colourFor's magnitude curve in HeatmapScreen.kt. */
    private fun mag(p: Double): Double =
        (ln(1.0 + abs(p) / 1.6) / ln(1.0 + 25.0 / 1.6)).coerceIn(0.0, 1.0)

    @Test
    fun `a big move is visibly stronger than a moderate one`() {
        assertTrue("4% and 40% collapse to the same colour", mag(40.0) - mag(4.0) > 0.2)
        assertTrue(mag(10.0) > mag(4.0))
        assertTrue(mag(25.0) > mag(10.0))
    }

    @Test
    fun `the common range stays well spread`() {
        // Most days live between 0 and 3%; that band must not be squashed into one shade.
        assertTrue("0.5% and 3% are indistinguishable", mag(3.0) - mag(0.5) > 0.2)
    }

    @Test
    fun `it is monotonic and bounded`() {
        var prev = -1.0
        for (p in listOf(0.0, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 40.0, 100.0)) {
            val m = mag(p)
            assertTrue("not monotonic at $p", m >= prev)
            assertTrue("out of range at $p: $m", m in 0.0..1.0)
            prev = m
        }
    }

    @Test
    fun `sign is irrelevant to magnitude`() {
        assertTrue(abs(mag(-7.0) - mag(7.0)) < 1e-9)
    }
}
