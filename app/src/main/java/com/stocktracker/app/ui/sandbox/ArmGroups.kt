package com.stocktracker.app.ui.sandbox

import com.stocktracker.app.data.remote.SandboxArm
import com.stocktracker.app.data.remote.SandboxArmsNav

/**
 * The Sandbox arms come in two groups: the originals, which may buy stocks and funds, and the
 * ETF-only pair. Each group is compared within itself — they started on different days and trade
 * different things — so the switcher, scoreboard and trend chart show one group at a time.
 *
 * Pure, so the grouping rules are tested without a screen.
 */
internal object ArmGroups {
    const val ALL = "all"
    const val ETF = "etf"

    fun groupOf(arm: String, arms: List<SandboxArm>): String =
        arms.firstOrNull { it.arm == arm }?.universe?.takeIf { it == ETF } ?: ALL

    fun hasEtf(arms: List<SandboxArm>): Boolean = arms.any { it.universe == ETF }

    fun armsIn(group: String, arms: List<SandboxArm>): List<SandboxArm> =
        arms.filter { (if (it.universe == ETF) ETF else ALL) == group }

    /** The arm to open when the reader switches group: main for the originals, the AI ETF arm
     *  (not the mechanical one) for the ETF pair. */
    fun defaultArm(group: String, arms: List<SandboxArm>): String? = when (group) {
        ETF -> armsIn(ETF, arms).let { g -> (g.firstOrNull { it.engine == "llm" } ?: g.firstOrNull())?.arm }
        else -> "main"
    }

    /** Which arms the trend chart draws for a group, and the index its lines are based at. The ETF
     *  group carries main as a reference line. Falls back to the single server-wide start for the
     *  originals when the server predates groups; an ETF group with no data has no chart. */
    fun trend(group: String, nav: SandboxArmsNav): Pair<Set<String>, Int?> {
        val c = nav.cohorts[group]
        if (c != null) return c.arms.toSet() to c.commonStartIndex
        return if (group == ALL) nav.arms.filter { it.universe != ETF }.map { it.arm }.toSet() to nav.commonStartIndex
        else emptySet<String>() to null
    }
}
