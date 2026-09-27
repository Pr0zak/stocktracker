package com.stocktracker.app.ui.funds

import com.stocktracker.app.data.remote.ExploreFund
import com.stocktracker.app.data.remote.FundExploreResponse
import com.stocktracker.app.ui.detail.FundCostText

/** The three funds a category leads with: best return, lowest fee, smallest worst drop. */
data class TopPicks(val best: ExploreFund?, val cheapest: ExploreFund?, val calmest: ExploreFund?)

/**
 * FUND-8 — the Explore list's arithmetic, kept off the screen so it can be tested.
 *
 * Every figure can be unknown: a fund too young for a window, a fee nobody lists, a history that
 * failed to load. Unknown sorts last and is never picked as "best" — a missing drop is not a small
 * drop, and a missing fee is not a free fund.
 */
internal object ExploreLogic {

    val PERIODS = listOf("1y", "3y", "5y")

    fun ret(f: ExploreFund, period: String): Double? = f.returns[period]?.takeIf { f.available && it.isFinite() }

    fun drop(f: ExploreFund, period: String): Double? = f.drops[period]?.takeIf { f.available && it.isFinite() }

    /** Funds of [category] (all when null) matching [query] by ticker start, plain name or fund name. */
    fun filter(funds: List<ExploreFund>, category: String?, query: String): List<ExploreFund> {
        val q = query.trim()
        return funds.filter { f ->
            (category == null || f.category == category) &&
                (q.isEmpty() || f.symbol.startsWith(q, ignoreCase = true) ||
                    f.name.contains(q, ignoreCase = true) || f.longName?.contains(q, ignoreCase = true) == true)
        }
    }

    /** The longest window at least half of [funds] have a return for, so a list sorts on something. */
    fun defaultPeriod(funds: List<ExploreFund>): String {
        if (funds.isEmpty()) return "5y"
        for (p in listOf("5y", "3y", "1y")) {
            if (funds.count { ret(it, p) != null } * 2 >= funds.size) return p
        }
        return "1y"
    }

    /** [sorted] with a fund whose ticker is exactly [query] moved to the top: "SPY" finds SPY first, not SPYG. */
    fun exactFirst(sorted: List<ExploreFund>, query: String): List<ExploreFund> {
        val q = query.trim()
        val hit = sorted.indexOfFirst { it.symbol.equals(q, ignoreCase = true) }
        return if (hit <= 0) sorted else listOf(sorted[hit]) + sorted.filterIndexed { i, _ -> i != hit }
    }

    fun sort(funds: List<ExploreFund>, period: String, sort: RankSort): List<ExploreFund> = when (sort) {
        RankSort.RETURN -> funds.sortedWith(
            compareBy<ExploreFund> { ret(it, period) == null }.thenByDescending { ret(it, period) ?: 0.0 }.thenBy { it.symbol },
        )
        // Drops are ≤ 0, so the smallest fall is the largest number.
        RankSort.DROP -> funds.sortedWith(
            compareBy<ExploreFund> { drop(it, period) == null }.thenByDescending { drop(it, period) ?: 0.0 }
                .thenByDescending { ret(it, period) ?: Double.NEGATIVE_INFINITY },
        )
        RankSort.FEE -> funds.sortedWith(
            compareBy<ExploreFund> { it.expenseRatioPct == null }.thenBy { it.expenseRatioPct ?: 0.0 }
                .thenByDescending { it.netAssets ?: 0.0 },
        )
    }

    fun topPicks(funds: List<ExploreFund>, period: String): TopPicks = TopPicks(
        best = funds.filter { ret(it, period) != null }.maxByOrNull { ret(it, period)!! },
        cheapest = funds.filter { it.expenseRatioPct != null }
            .minWithOrNull(compareBy<ExploreFund> { it.expenseRatioPct!! }.thenByDescending { it.netAssets ?: 0.0 }),
        calmest = funds.filter { drop(it, period) != null }
            .maxWithOrNull(compareBy<ExploreFund> { drop(it, period)!! }.thenBy { ret(it, period) ?: Double.NEGATIVE_INFINITY }),
    )

    /** The other funds measured to hold the same thing as [f], cheapest first. Empty outside a group. */
    fun sameThing(f: ExploreFund, all: List<ExploreFund>): List<ExploreFund> {
        val g = f.groupId ?: return emptyList()
        return all.filter { it.groupId == g && it.symbol != f.symbol }
            .sortedWith(compareBy<ExploreFund> { it.expenseRatioPct == null }.thenBy { it.expenseRatioPct ?: 0.0 })
    }

    /**
     * Funds of the same type that are NOT copies of [f]: the same plain name first (XLK beside VGT,
     * alike but holding different stocks), then the rest by return. One fund per look-alike group,
     * its cheapest ETF, so the list is not five versions of the S&P 500.
     */
    fun similar(f: ExploreFund, all: List<ExploreFund>, period: String, limit: Int = 4): List<ExploreFund> {
        val pool = all.filter { it.symbol != f.symbol && it.category == f.category && (f.groupId == null || it.groupId != f.groupId) }
        val reps = pool.groupBy { it.groupId ?: it.symbol }.values.map { members ->
            members.minWithOrNull(
                compareBy<ExploreFund> { it.isMutualFund }.thenBy { it.expenseRatioPct == null }.thenBy { it.expenseRatioPct ?: 0.0 },
            )!!
        }
        return reps.sortedWith(
            compareBy<ExploreFund> { it.name != f.name }.thenBy { ret(it, period) == null }
                .thenByDescending { ret(it, period) ?: 0.0 },
        ).take(limit)
    }

    /** "$3" a year per $10,000, or "—" when unknown. */
    fun fee(f: ExploreFund): String = f.expenseRatioPct?.let { FundCostText.perTenK(it) } ?: "—"

    /** "181 funds · to Sep 25 close", or "Dividends · 10 funds · to Sep 25 close" for one type. */
    fun subtitle(resp: FundExploreResponse?, category: String? = null): String? = resp?.let {
        val cat = category?.let { c -> it.categories.firstOrNull { x -> x.id == c } }
        val n = if (cat != null) it.funds.count { f -> f.category == cat.id } else it.funds.size
        listOfNotNull(cat?.label, "$n funds", FundsLogic.asOfLine(it.alignedTo)?.let { d -> "to $d" }).joinToString(" · ")
    }

    /** "Updated 2d ago" once a build is more than a day old; null while it is fresh. */
    fun staleLine(builtAt: Double?, nowMs: Long = System.currentTimeMillis()): String? {
        val b = builtAt ?: return null
        if (nowMs - b * 1000 < 24 * 3600 * 1000L) return null
        return "Updated ${FundsLogic.ago(b, nowMs)}"
    }

    /** The short tag after a fund's plain name, or null. */
    fun tag(f: ExploreFund): String? = when {
        f.fidelityOnly -> "Fidelity only"
        f.fidelity && f.isMutualFund -> "Fidelity fund"
        f.fidelity -> "Fidelity"
        f.isMutualFund -> "Mutual fund"
        else -> null
    }
}
