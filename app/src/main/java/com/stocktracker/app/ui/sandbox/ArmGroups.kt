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

    /**
     * Every arm as percentage points ahead of (or behind) its OWN "same money in the S&P" shadow,
     * one value per date, for charting all of them together.
     *
     * Raw equity cannot share a chart: the ETF arms began on 2026-09-28, weeks after the rest, and
     * every arm's equity steps up on deposit days. Rebasing to 100 on a common start fixed that only
     * by splitting the arms into two charts. Excess over the arm's own shadow is deposit-neutral (the
     * shadow gets the same money on the same day) and start-neutral (it is 0 on the day an arm is
     * funded), so all arms line up on one axis, and each line's last value is the "vs S&P" figure the
     * scoreboard prints.
     *
     * Rows are the same length as [SandboxArmsNav.dates]; null where the arm had no value or no
     * benchmark that day.
     */
    fun vsShadow(nav: SandboxArmsNav): List<Pair<com.stocktracker.app.data.remote.SandboxArmSeries, List<Double?>>> =
        nav.arms.map { s ->
            s to nav.dates.indices.map { i ->
                val e = s.equity.getOrNull(i)
                val b = s.benchmarkValue.getOrNull(i)
                if (e == null || b == null || b <= 0.0) null else (e / b - 1.0) * 100.0
            }
        }.filter { (_, v) -> v.count { it != null } >= 1 }

    /** The series that sets the chart's x axis: the one with the most days, `main` on a tie, since
     *  PriceChart draws overlays against the main line's points. */
    fun axisArm(rows: List<Pair<com.stocktracker.app.data.remote.SandboxArmSeries, List<Double?>>>): String? =
        rows.maxWithOrNull(compareBy<Pair<com.stocktracker.app.data.remote.SandboxArmSeries, List<Double?>>> { r ->
            r.second.count { it != null }
        }.thenBy { if (it.first.arm == "main") 1 else 0 })?.first?.arm
}

