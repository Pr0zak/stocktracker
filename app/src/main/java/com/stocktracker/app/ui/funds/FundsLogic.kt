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
    SAME_FUND("Same fund, twice"),
    MOVE_TOGETHER("Move together"),
    OVERLAP_A_LOT("Overlap a lot"),
    DIFFERENT("Move differently"),
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

internal object FundsLogic {

    /** Below this a fund is small enough that closing is a real possibility. */
    const val SMALL_FUND_DOLLARS = 50e6

    fun corr(c: Double?): String = c?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

    private fun plural(n: Int, word: String) = if (n == 1) word else word + "s"

    /** "9 funds, 4 different bets". */
    fun betsHeadline(funds: Int, bets: Int): String =
        "$funds ${plural(funds, "fund")}, $bets different ${plural(bets, "bet")}"

    /** "+86.9%", "−24.5%" with a real minus sign, "—" when unknown. */
    fun pct(p: Double?): String {
        if (p == null || !p.isFinite()) return "—"
        return (if (p >= 0) "+" else "−") + String.format(Locale.US, "%.1f", abs(p)) + "%"
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
        return "Small fund (${size(n)}). Small funds close more often, and a closing fund pays you out: " +
            "a sale, with tax on any gain."
    }

    /**
     * What one round trip costs in the bid-ask gap, per $10,000. Null when there is no in-session
     * reading. A mutual fund has no spread at all: it is bought and sold at the day's closing price.
     */
    fun tradingCost(spreadPct: Double?, spreadAt: Double?, isMutualFund: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        if (isMutualFund) return "No trading gap: bought and sold at the day's closing price"
        val sp = spreadPct?.takeIf { it.isFinite() && it >= 0 } ?: return null
        val age = spreadAt?.let { age(nowMs - (it * 1000).toLong()) }
        return "Buying and selling \$10,000 once costs about ${FundCostText.dollars(sp * 100.0)} in the bid-ask gap" +
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
                PairVerdict.SAME_FUND -> "Same thing as your $o ($cc): owning both is one bet."
                PairVerdict.MOVE_TOGETHER -> "Moves with your $o ($cc): mostly the same bet."
                PairVerdict.OVERLAP_A_LOT -> "Overlaps a lot with your $o ($cc)."
                PairVerdict.DIFFERENT -> "A different bet: its closest match among your funds is $o, at $cc."
                PairVerdict.UNKNOWN -> "Couldn't measure it against your funds (too little shared price history)."
            }
            // Yahoo lists at most ten holdings, fewer once share classes are merged, so the count is
            // out of what this fund actually lists — and it is a floor on the real overlap.
            val listed = resp.funds[c]?.topHoldings?.size ?: 0
            if (p.sharedTopCount > 0 && listed > 0) {
                lines += "${p.sharedTopCount} of its $listed biggest holdings are also among $o's biggest."
            }
        }
        val tops = resp.funds[c]?.topHoldings.orEmpty()
        val direct = tops.filter { h -> ownedStocks.any { it.equals(h.symbol, ignoreCase = true) } }
        if (direct.isNotEmpty()) {
            val weight = direct.sumOf { it.pct }
            lines += "You already own ${direct.joinToString(", ") { it.symbol }} directly: " +
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
        return "${FundCostText.perTenK(fees.min())}–${FundCostText.perTenK(fees.max())} per \$10,000"
    }

    /** A note for a bitcoin or ether bet when the user also holds the coin itself. */
    fun directCryptoNote(betGroupIds: Collection<String?>, heldCoins: Collection<String>): String? {
        val coins = heldCoins.map { it.uppercase().removeSuffix("-USD") }.toSet()
        return when {
            "bitcoin" in betGroupIds && "BTC" in coins -> "You also own bitcoin directly: same bet."
            "ether" in betGroupIds && "ETH" in coins -> "You also own ether directly: same bet."
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
