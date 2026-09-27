package com.stocktracker.app.ui.funds

import com.stocktracker.app.data.remote.FundGroup
import com.stocktracker.app.data.remote.FundOverlapResponse
import com.stocktracker.app.data.remote.FundProfile
import com.stocktracker.app.ui.detail.FundCostText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * How closely two funds move, in the words the Funds screen uses.
 *
 * Correlation of two years of returns decides it, never the sector table: VOO and VXUS came out 72%
 * alike by sector while holding none of the same companies (see fund_overlap.py in the backend).
 */
enum class PairVerdict(val words: String) {
    SAME_FUND("Same fund"),
    MOVE_TOGETHER("Close copies"),
    OVERLAP_A_LOT("Similar"),
    DIFFERENT("Different"),
    UNKNOWN("Not measured");

    companion object {
        fun of(corr: Double?): PairVerdict = when {
            corr == null || !corr.isFinite() -> UNKNOWN
            corr >= 0.98 -> SAME_FUND
            corr >= 0.90 -> MOVE_TOGETHER
            corr >= 0.80 -> OVERLAP_A_LOT
            else -> DIFFERENT
        }
    }
}

/**
 * A switch that would cut fees: [value] dollars from [from] into [to], saving [savesPerYear].
 * [toNote] names what is different about the target, when something is: a Fidelity-only fund can
 * never leave Fidelity without being sold, and a mutual fund trades once a day.
 */
data class FeeSwitch(val from: String, val to: String, val value: Double, val savesPerYear: Double, val toNote: String? = null)

/**
 * What the user's funds cost a year. [cheapestPerYear] is the same money in the cheapest look-alike
 * of each fund (or the fund itself when nothing cheaper holds the same thing); null when the
 * look-alike fees could not be read. [unknownFee] funds are left out of both totals and named.
 */
data class FeesYouPay(
    val perYear: Double,
    val cheapestPerYear: Double?,
    val switches: List<FeeSwitch>,
    val unknownFee: List<String>,
    /** Dollars the totals cover: every held fund whose fee is known. */
    val countedValue: Double = 0.0,
    val knownCount: Int = 0,
    /** Funds with a known fee that had a measured look-alike to compare against. */
    val compared: List<String> = emptyList(),
    /** Funds with a known fee and no measured look-alike: nothing to say about "cheapest". */
    val notCompared: List<String> = emptyList(),
)

/** The funds' groups that move together (two or more), and the funds that move on their own. */
data class OverlapView(val groups: List<List<String>>, val singles: List<String>) {
    /** Funds that sit in a group with at least one other. */
    val overlapping: Int get() = groups.sumOf { it.size }
}

/** One row of the performance ranking. Any figure may be null: unknown, never zero. */
data class RankRow(val symbol: String, val ret: Double?, val worstDrop: Double?, val feePct: Double?)

enum class RankSort { RETURN, DROP, FEE }

/**
 * A cheaper fund that holds the same thing. [savesPer10k] is dollars a year per $10,000;
 * [savesYours] is on the user's own money in [from], when it is known.
 */
data class CheaperCopy(
    val from: String,
    val to: String,
    val toNote: String?,
    val savesPer10k: Double,
    val savesYours: Double?,
)

internal object FundsLogic {

    /** Said under every suggestion to switch funds: a switch is a sale. */
    const val TAX_NOTE = "Switching means selling, which can mean tax. Best for new money."

    /** The before-you-buy answer when the user holds nothing to compare with. */
    const val NOTHING_HELD = "You hold no stocks or funds to compare it with."

    /** Below this a fund is small enough that closing is a real possibility. */
    const val SMALL_FUND_DOLLARS = 50e6

    fun corr(c: Double?): String = c?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

    private fun plural(n: Int, word: String) = if (n == 1) word else word + "s"

    /**
     * Groups of two or more funds that move together, biggest (by the user's money, then by size)
     * first, and the funds that move on their own.
     */
    fun overlapView(resp: FundOverlapResponse, values: Map<String, Double> = emptyMap()): OverlapView {
        val groups = resp.sameBets.filter { it.size > 1 }.sortedWith(
            compareByDescending<List<String>> { g -> g.sumOf { values[it] ?: 0.0 } }
                .thenByDescending { it.size }
                .thenBy { it.first() },
        )
        return OverlapView(groups, resp.sameBets.filter { it.size == 1 }.map { it.first() })
    }

    /** "A", "A and B", "A, B and C". */
    fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /** "7 of your 12 funds overlap" — the headline the user picked on 2026-09-26. */
    fun overlapHeadline(total: Int, overlapping: Int): String = when {
        total <= 1 -> "$total ${plural(total, "fund")}, nothing to overlap with"
        overlapping == 0 -> "None of your $total funds overlap"
        overlapping == total && total == 2 -> "Both your funds overlap"
        overlapping == total -> "All $total of your funds overlap"
        else -> "$overlapping of your $total funds overlap"
    }

    /** "SPY, VOO and VTI move almost the same." / "FBTC and IBIT are both bitcoin." */
    fun groupSentence(group: List<String>, funds: Map<String, FundProfile>): String {
        val coin = group.all { funds[it]?.region == "crypto" }
        val label = funds[group.first()]?.regionLabel?.lowercase()
        return if (coin && label != null) {
            "${joinNames(group)} are ${if (group.size == 2) "both" else "all"} $label."
        } else {
            "${joinNames(group)} move almost the same."
        }
    }

    /** A short legend name for a group: "Bitcoin", or "SPY and 4 more". */
    fun groupName(group: List<String>, funds: Map<String, FundProfile>): String {
        val coin = group.all { funds[it]?.region == "crypto" }
        val label = funds[group.first()]?.regionLabel
        return if (coin && label != null) label else "${group.first()} and ${group.size - 1} more"
    }

    /** The longest period at least two funds have a record for, so a ranking ranks something. */
    fun bestPeriod(perf: com.stocktracker.app.data.remote.FundPerformanceResponse?, symbols: Collection<String>): String {
        for (p in listOf("5y", "3y", "1y")) {
            if (symbols.count { perf?.funds?.get(it)?.returns?.get(p) != null } >= 2) return p
        }
        return "1y"
    }

    /** "Sep 25 close" from the day every return is measured to; null when there is none. */
    fun asOfLine(iso: String?): String? = iso?.let {
        runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("MMM d", Locale.US)) + " close" }.getOrNull()
    }

    fun periodWords(p: String): String = when (p) {
        "5y" -> "5 years"
        "3y" -> "3 years"
        else -> "1 year"
    }

    /** The ranking, with every unknown figure sorted last — never treated as zero. */
    fun ranking(
        symbols: Collection<String>,
        perf: com.stocktracker.app.data.remote.FundPerformanceResponse?,
        funds: Map<String, FundProfile>,
        period: String,
        sort: RankSort,
    ): List<RankRow> {
        val rows = symbols.map { s ->
            val p = perf?.funds?.get(s)?.takeIf { it.available }
            RankRow(s, p?.returns?.get(period), p?.worstDropPct, funds[s]?.expenseRatioPct)
        }
        return when (sort) {
            RankSort.RETURN -> rows.sortedWith(compareBy<RankRow> { it.ret == null }.thenByDescending { it.ret ?: 0.0 })
            RankSort.DROP -> rows.sortedWith(compareBy<RankRow> { it.worstDrop == null }.thenByDescending { it.worstDrop ?: 0.0 })
            RankSort.FEE -> rows.sortedWith(compareBy<RankRow> { it.feePct == null }.thenBy { it.feePct ?: 0.0 })
        }
    }

    /**
     * For each fund, the cheapest ETF measured to hold the same thing (a Fidelity mutual fund only
     * when no cheaper ETF exists), biggest saving first — on the user's money when it is known.
     */
    fun cheaperCopies(
        symbols: Collection<String>,
        funds: Map<String, FundProfile>,
        groups: Map<String, FundGroup>?,
        values: Map<String, Double> = emptyMap(),
    ): List<CheaperCopy> {
        val out = mutableListOf<CheaperCopy>()
        for (s in symbols) {
            val f = funds[s] ?: continue
            val fee = f.expenseRatioPct ?: continue
            val peers = f.groupId?.let { groups?.get(it) }?.funds.orEmpty()
                .filter { it.symbol != s && it.expenseRatioPct != null && it.expenseRatioPct < fee - 1e-9 }
            val alt = peers.filter { it.kind == "etf" }.minByOrNull { it.expenseRatioPct!! }
                ?: peers.minByOrNull { it.expenseRatioPct!! } ?: continue
            val gap = fee - alt.expenseRatioPct!!
            out += CheaperCopy(
                from = s,
                to = alt.symbol,
                toNote = when {
                    alt.fidelityOnly -> "Fidelity-only"
                    alt.isMutualFund -> "mutual fund"
                    else -> null
                },
                savesPer10k = gap * 100.0,
                savesYours = values[s]?.let { it * gap / 100.0 },
            )
        }
        return if (values.isNotEmpty()) out.sortedByDescending { it.savesYours ?: -1.0 } else out.sortedByDescending { it.savesPer10k }
    }

    /** Fee range across [symbols] per $10,000, the cheapest names and the priciest; null with < 2 known. */
    fun feeRange(symbols: Collection<String>, funds: Map<String, FundProfile>): Triple<String, List<String>, String>? {
        val known = symbols.mapNotNull { s -> funds[s]?.expenseRatioPct?.let { s to it } }
        if (known.size < 2) return null
        val min = known.minOf { it.second }
        val max = known.maxOf { it.second }
        val cheapest = known.filter { it.second - min < 1e-9 }.map { it.first }
        val priciest = known.first { max - it.second < 1e-9 }.first
        return Triple("${FundCostText.perTenK(min)} – ${FundCostText.perTenK(max)}", cheapest, priciest)
    }

    /** "+86.9%", "−24.5%" with a real minus sign, "—" when unknown. */
    fun pct(p: Double?): String {
        if (p == null || !p.isFinite()) return "—"
        return (if (p >= 0) "+" else "−") + String.format(Locale.US, "%.1f", abs(p)) + "%"
    }

    /** "+108%", "−21%": whole percents for a tile, where a decimal only crowds the bar. */
    fun pctShort(p: Double?): String {
        if (p == null || !p.isFinite()) return "—"
        return (if (p >= 0) "+" else "−") + String.format(Locale.US, "%.0f", abs(p)) + "%"
    }

    /** "$1.76 trillion", "$104 billion", "$38 million". Null for unknown. */
    fun size(dollars: Double?): String? {
        val d = dollars?.takeIf { it.isFinite() && it > 0 } ?: return null
        fun fmt(v: Double) = if (v >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.2f", v)
            .trimEnd('0').trimEnd('.')
        return when {
            d >= 1e12 -> "$" + fmt(d / 1e12) + " trillion"
            d >= 1e9 -> "$" + fmt(d / 1e9) + " billion"
            d >= 1e6 -> "$" + fmt(d / 1e6) + " million"
            else -> "$" + String.format(Locale.US, "%,.0f", d)
        }
    }

    /** A warning for a fund small enough to close, or null. Says what a closure actually does. */
    fun smallFundWarning(netAssets: Double?): String? {
        val n = netAssets?.takeIf { it.isFinite() && it > 0 } ?: return null
        if (n >= SMALL_FUND_DOLLARS) return null
        return "Small fund (${size(n)}). Small funds close more often. A closing fund sells you out, which can mean tax."
    }

    /**
     * What one round trip costs in the bid-ask gap, per $10,000. Null when there is no in-session
     * reading. A mutual fund has no spread at all: it is bought and sold at the day's closing price.
     */
    fun tradingCost(spreadPct: Double?, spreadAt: Double?, isMutualFund: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        if (isMutualFund) return "No trading gap: it trades once a day, at the close"
        val sp = spreadPct?.takeIf { it.isFinite() && it >= 0 } ?: return null
        val age = spreadAt?.let { age(nowMs - (it * 1000).toLong()) }
        return "Trading gap: about ${FundCostText.dollars(sp * 100.0)} to buy and sell \$10,000" +
            (age?.let { " (checked $it)" } ?: "")
    }

    /**
     * The extra a pricier fund costs over [years] on [amount], if both grow [growthPct] a year before
     * fees: SPY over VOO on $10,000 for 20 years at 7% is about $461. Fees compound, which is the
     * point of saying it in dollars.
     */
    fun feeDrag(feePct: Double, basePct: Double, amount: Double = 10_000.0, years: Int = 20, growthPct: Double = 7.0): Double =
        amount * ((1 + (growthPct - basePct) / 100).pow(years) - (1 + (growthPct - feePct) / 100).pow(years))

    /** "US stocks · Tech 39% · Finance 12% · Communication 10%". */
    fun covers(p: FundProfile, sectors: Int = 3): String {
        if (!p.profileOk) return listOfNotNull(p.regionLabel, "holdings unavailable right now").joinToString(" · ")
        val top = p.sectors.orEmpty().take(sectors).map { "${it.label} ${String.format(Locale.US, "%.0f", it.pct)}%" }
        return (listOfNotNull(p.regionLabel) + top).joinToString(" · ").ifBlank { "—" }
    }

    /** "−24.5% (Jan–Oct 2022)", "−53.4% (Oct 2025–Jun 2026)", or "—". */
    fun worstDrop(pct: Double?, from: String?, to: String?): String {
        if (pct == null || !pct.isFinite()) return "—"
        if (pct == 0.0 || from == null || to == null) return pct(pct)
        val f = runCatching { LocalDate.parse(from) }.getOrNull()
        val t = runCatching { LocalDate.parse(to) }.getOrNull()
        if (f == null || t == null) return pct(pct)
        val mon = DateTimeFormatter.ofPattern("MMM", Locale.US)
        val span = if (f.year == t.year) "${f.format(mon)}–${t.format(mon)} ${t.year}"
        else "${f.format(mon)} ${f.year}–${t.format(mon)} ${t.year}"
        return "${pct(pct)} ($span)"
    }

    /**
     * The "before you buy" read: [candidate] against the funds and stocks the user already owns.
     *
     * Leads with the closest owned fund by correlation, then the top-ten holdings they share, then
     * any of the candidate's biggest holdings the user already owns directly. Empty when there is
     * nothing to compare against — the caller says so.
     */
    fun beforeYouBuy(
        candidate: String,
        resp: FundOverlapResponse,
        ownedFunds: Collection<String>,
        ownedStocks: Collection<String>,
        alreadyOwned: Boolean = false,
    ): List<String> {
        val c = candidate.uppercase()
        val lines = mutableListOf<String>()
        if (alreadyOwned) lines += "You already own $c."
        val others = ownedFunds.map { it.uppercase() }.filter { it != c && it in resp.funds }
        val best = others.mapNotNull { o -> resp.pair(c, o)?.let { o to it } }
            .maxByOrNull { it.second.corr ?: Double.NEGATIVE_INFINITY }
        if (best != null) {
            val (o, p) = best
            val cc = corr(p.corr)
            lines += when (PairVerdict.of(p.corr)) {
                PairVerdict.SAME_FUND -> "Same as your $o ($cc). Owning both doubles up."
                PairVerdict.MOVE_TOGETHER -> "Moves almost the same as your $o ($cc)."
                PairVerdict.OVERLAP_A_LOT -> "Similar to your $o ($cc)."
                PairVerdict.DIFFERENT -> "Moves its own way. Closest of yours: $o ($cc)."
                PairVerdict.UNKNOWN -> "Too new to compare with your funds."
            }
            // Yahoo lists at most ten holdings, fewer once share classes are merged, so the count is
            // out of what this fund actually lists — and it is a floor on the real overlap.
            val listed = resp.funds[c]?.topHoldings?.size ?: 0
            if (p.sharedTopCount > 0 && listed > 0) {
                lines += "Shares ${p.sharedTopCount} of its top $listed holdings with $o."
            }
        }
        val tops = resp.funds[c]?.topHoldings.orEmpty()
        val direct = tops.filter { h -> ownedStocks.any { it.equals(h.symbol, ignoreCase = true) } }
        if (direct.isNotEmpty()) {
            val weight = direct.sumOf { it.pct }
            lines += "You already own ${direct.joinToString(", ") { it.symbol }}: " +
                "${String.format(Locale.US, "%.0f", weight)}% of this fund."
        }
        return lines
    }

    /**
     * What the user's funds cost a year, and what the same money would cost in each fund's cheapest
     * look-alike. [values] is dollars per symbol, priced on the phone.
     */
    fun feesYouPay(values: Map<String, Double>, funds: Map<String, FundProfile>, groups: Map<String, FundGroup>?): FeesYouPay {
        var perYear = 0.0
        var cheapest = 0.0
        var counted = 0.0
        var known = 0
        val switches = mutableListOf<FeeSwitch>()
        val unknown = mutableListOf<String>()
        val compared = mutableListOf<String>()
        val notCompared = mutableListOf<String>()
        for ((sym, value) in values) {
            val f = funds[sym] ?: continue
            val fee = f.expenseRatioPct
            if (fee == null) {
                unknown += sym
                continue
            }
            counted += value
            known += 1
            perYear += value * fee / 100.0
            val peers = f.groupId?.let { groups?.get(it) }?.funds
                ?.filter { it.symbol != sym && it.expenseRatioPct != null }.orEmpty()
            if (peers.isEmpty()) notCompared += sym else compared += sym
            val alt = peers.filter { it.expenseRatioPct!! < fee - 1e-9 }.minByOrNull { it.expenseRatioPct!! }
            if (alt != null) {
                cheapest += value * alt.expenseRatioPct!! / 100.0
                val note = when {
                    alt.fidelityOnly -> "Fidelity-only mutual fund"
                    alt.isMutualFund -> "mutual fund"
                    else -> null
                }
                switches += FeeSwitch(sym, alt.symbol, value, value * (fee - alt.expenseRatioPct) / 100.0, note)
            } else {
                cheapest += value * fee / 100.0
            }
        }
        val grouped = values.keys.mapNotNull { funds[it]?.groupId }
        return FeesYouPay(
            perYear = perYear,
            // Without the group list the cheaper side cannot be known for grouped funds.
            cheapestPerYear = if (groups == null && grouped.isNotEmpty()) null else cheapest,
            switches = switches.sortedByDescending { it.savesPerYear },
            unknownFee = unknown,
            countedValue = counted,
            knownCount = known,
            compared = compared,
            notCompared = notCompared,
        )
    }

    /** "$0–$9.45 per $10,000", or null when fewer than two fees are known. */
    fun costRange(g: FundGroup): String? {
        val fees = g.funds.mapNotNull { it.expenseRatioPct }
        if (fees.size < 2) return null
        val lo = FundCostText.perTenK(fees.min())
        val hi = FundCostText.perTenK(fees.max())
        return if (lo == hi) "All $lo per \$10,000" else "$lo–$hi per \$10,000"
    }

    /** A note for a bitcoin or ether bet when the user also holds the coin itself. */
    fun directCryptoNote(betGroupIds: Collection<String?>, heldCoins: Collection<String>): String? {
        val coins = heldCoins.map { it.uppercase().removeSuffix("-USD") }.toSet()
        return when {
            "bitcoin" in betGroupIds && "BTC" in coins -> "You also own bitcoin itself, so this doubles up."
            "ether" in betGroupIds && "ETH" in coins -> "You also own ether itself, so this doubles up."
            else -> null
        }
    }

    /** "2h ago" for an epoch-seconds stamp, or null when there is none. */
    fun ago(epochSec: Double?, nowMs: Long = System.currentTimeMillis()): String? =
        epochSec?.let { age(nowMs - (it * 1000).toLong()) }

    private fun age(ms: Long): String {
        val mins = ms / 60_000
        return when {
            mins < 2 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            else -> "${mins / (60 * 24)}d ago"
        }
    }
}
