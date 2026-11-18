package com.stocktracker.app.ui.sandbox

import com.stocktracker.app.data.remote.SandboxArm
import com.stocktracker.app.data.remote.SandboxContract
import com.stocktracker.app.data.remote.SandboxSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SBX-4 (group chips, matchups) and SBX-5 (options account), on the 14 live arms of 2026-11-13. */
class SandboxRevampTest {
    private fun arm(name: String, vs: Double?, engine: String = "llm", universe: String = "all", single: Boolean = false, ran: Boolean = true) =
        SandboxArm(arm = name, label = name, engine = engine, universe = universe, singleShare = single,
            vsBenchmarkPct = vs, lastTickDate = if (ran) "2026-11-13" else null)

    private val arms = listOf(
        arm("main", 0.05), arm("etf", 1.47, universe = "etf"), arm("etf-rules", 0.46, "rules", "etf"),
        arm("fast", -0.77), arm("gated", 0.19), arm("oneetf", -0.37, universe = "etf", single = true),
        arm("oneshare", 0.47, single = true), arm("oneshare-rules", 1.34, "rules", single = true),
        arm("options", 0.0, universe = "options", ran = false), arm("options-rules", 0.0, "rules", "options", ran = false),
        arm("picky", 1.64), arm("rejects", 3.40, "rejects"), arm("reviewed", 0.88), arm("rules", -0.03, "rules"),
    )

    @Test fun an_account_that_has_not_run_shows_no_figure_and_ranks_last() {
        assertNull(ArmGroups.vsShown(arms.first { it.arm == "options" }))
        val ranked = LeaderboardMath.ranked(ArmGroups.armsIn("options", arms) + arms.first { it.arm == "fast" })
        assertEquals("fast", ranked.first().arm)
    }

    @Test fun groups_split_9_3_2() {
        assertEquals(listOf(9, 3, 2), ArmGroups.ORDER.map { ArmGroups.armsIn(it.first, arms).size })
    }

    @Test fun twins_pair_each_rules_account_with_its_ai_account_in_chip_order() {
        val t = Matchups.twins(arms)
        assertEquals(listOf("main", "oneshare", "etf", "options"), t.map { it.ai.arm })
        assertEquals(listOf("rules", "oneshare-rules", "etf-rules", "options-rules"), t.map { it.rules.arm })
        assertEquals(listOf("Stocks", "One share a day", "ETFs", "Calls + puts"), t.map { it.title })
        assertEquals(0.08, Matchups.gap(t[0].ai, t[0].rules)!!, 1e-9)
        assertNull("a pair that has not run has no gap", Matchups.gap(t[3].ai, t[3].rules))
    }

    @Test fun the_reviewer_pair_and_the_tweaks() {
        val (kept, cut) = Matchups.reviewer(arms)!!
        assertEquals("reviewed" to "rejects", kept.arm to cut.arm)
        assertEquals(setOf("fast", "gated", "picky", "reviewed"), Matchups.tweaks(arms).map { it.arm }.toSet())
        assertEquals(1.59, Matchups.overBaseline(arms.first { it.arm == "picky" }, arms)!!, 1e-9)
    }

    private val exits = ContractMath.exits(SandboxSettings())
    private fun call(pl: Double?, stale: Boolean = false) = SandboxContract(
        underlying = "NVDA", right = "call", strike = 150.0, premiumPaid = 5.90, count = 2,
        plPct = pl, markStale = stale, dte = 61.4, heldDays = 4, spot = 158.10, expiry = "2027-01-15")

    @Test fun exits_default_to_the_arm_rules() {
        assertEquals(OptionExits(21, -50.0, 100.0), exits)
        assertEquals(OptionExits(14, -40.0, 80.0),
            ContractMath.exits(SandboxSettings(optExitDte = 14, optStopPct = -40.0, optTargetPct = 80.0)))
    }

    @Test fun gauge_places_cost_a_third_of_the_way_and_a_stale_mark_nowhere() {
        assertEquals(1.0 / 3, ContractMath.costFraction(exits), 1e-9)
        assertEquals(0.5, ContractMath.gaugeFraction(call(25.0), exits)!!, 1e-9)
        assertEquals(0.0, ContractMath.gaugeFraction(call(-80.0), exits)!!, 1e-9)
        assertNull(ContractMath.gaugeFraction(call(25.0, stale = true), exits))
        assertNull(ContractMath.pointsToStop(call(-36.0, stale = true), exits))
        assertEquals(14.0, ContractMath.pointsToStop(call(-36.0), exits)!!, 1e-9)
        assertEquals(40, ContractMath.daysToExit(call(0.0), exits))
    }

    @Test fun break_even_is_strike_plus_premium_for_a_call_and_minus_for_a_put() {
        assertEquals(155.90, ContractMath.breakEven(call(0.0))!!, 1e-9)
        assertEquals("needs NVDA above $155.90 by Jan 15", ContractMath.needsLine(call(0.0)))
        assertEquals("now $158.10 · past break-even", ContractMath.nowLine(call(0.0)))
        val put = SandboxContract(underlying = "IWM", right = "put", strike = 230.0, premiumPaid = 6.40, spot = 228.90, expiry = "2027-01-15")
        assertEquals(223.60, ContractMath.breakEven(put)!!, 1e-9)
        assertEquals("needs IWM below $223.60 by Jan 15", ContractMath.needsLine(put))
        assertEquals("now $228.90 · −2.3% to go", ContractMath.nowLine(put))
        assertNull(ContractMath.breakEven(put.copy(premiumPaid = null)))
    }

    @Test fun the_weekly_budget_line() {
        fun w(b: Double, s: Double, f: String?) = com.stocktracker.app.data.remote.SandboxWeeklyBudget(b, s, maxOf(b - s, 0.0), f)
        assertNull(ContractMath.weekLine(null))
        assertEquals("This week: $1,500 left to spend", ContractMath.weekLine(w(1500.18, 0.0, null)))
        assertEquals("This week: $1,410 of $1,487 spent · more from Nov 23", ContractMath.weekLine(w(1486.65, 1410.0, "2026-11-23")))
        assertEquals("This week: budget used ($2,640 of $1,487) · more from Nov 23", ContractMath.weekLine(w(1486.65, 2640.0, "2026-11-23")))
    }
}
