package com.stocktracker.app.ui.sandbox

import com.stocktracker.app.data.remote.SandboxArm
import org.junit.Assert.assertEquals
import org.junit.Test

class LeaderboardMathTest {
    private fun a(id: String, v: Double?, label: String = id) = SandboxArm(arm = id, label = label, vsBenchmarkPct = v)

    @Test fun `best first, and an account with no figure sinks rather than ranking as zero`() {
        val r = LeaderboardMath.ranked(listOf(a("x", -2.13), a("new", null), a("y", 1.92), a("z", 0.0)))
        assertEquals(listOf("y", "z", "x", "new"), r.map { it.arm })
    }

    @Test fun `one scale, never under a point`() {
        assertEquals(2.97, LeaderboardMath.scale(listOf(a("x", -2.97), a("y", 1.92))), 1e-9)
        assertEquals(1.0, LeaderboardMath.scale(listOf(a("x", 0.2))), 1e-9)
    }

    @Test fun `labels drop the parenthetical`() {
        assertEquals("Regime gate on", LeaderboardMath.shortLabel(a("g", 0.0, "Regime gate on (blocks buys when shut)")))
        assertEquals("+ Second opinion", LeaderboardMath.shortLabel(a("r", 0.0, "+ Second opinion")))
        assertEquals("Baseline", LeaderboardMath.shortLabel(a("m", 0.0, "Baseline (AI, no review)")))
        assertEquals("ETFs only (AI)", LeaderboardMath.shortLabel(a("e", 0.0, "ETFs only (AI)")))
        assertEquals("ETFs only (no AI)", LeaderboardMath.shortLabel(a("f", 0.0, "ETFs only (no AI)")))
        assertEquals("Higher conviction bar (70)", LeaderboardMath.shortLabel(a("p", 0.0, "Higher conviction bar (70)")))
    }
}
