package com.stocktracker.app.ui.detail

import com.stocktracker.app.data.remote.FundCost
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * FC-1 — a fund's yearly fee, said in dollars.
 *
 * An expense ratio is a percentage of what you hold, taken out of the fund's price a sliver at a time,
 * so no bill ever arrives. "0.0945%" reads like nothing; "$9.45 a year for every $10,000" is the same
 * number in a form that can be weighed against the fund beside it. Everything here leads with dollars.
 * The percentage survives as one small reference line, for matching against Fidelity's fund page.
 *
 * Pure functions, so the wording is testable without a screen.
 */
internal object FundCostText {

    /** Dollars a year per $10,000 held: 0.0945 → "$9.45", 0.03 → "$3", 1.5 → "$150", 0.0 → "$0". */
    fun perTenK(pct: Double): String = dollars(pct * 100.0)

    /** What [value] dollars pays a year at [pct]: "$11.72", or "under 1¢" rather than a false "$0". */
    fun yearlyOn(value: Double, pct: Double): String {
        val d = value * pct / 100.0
        return if (d > 0.0 && d < 0.005) "under 1¢" else dollars(d)
    }

    /** "0.0945%": at least two decimals, at most four, no trailing zeros beyond the second. */
    fun percent(pct: Double): String {
        if (!pct.isFinite()) return "—"
        val four = String.format(Locale.US, "%.4f", pct).trimEnd('0')
        val decimals = four.substringAfter('.', "").length
        return (if (decimals < 2) String.format(Locale.US, "%.2f", pct) else four) + "%"
    }

    /** "$3" when whole, "$1.50" otherwise, thousands grouped. Not finite → "—". */
    fun dollars(amount: Double): String {
        if (!amount.isFinite()) return "—"
        val cents = (amount * 100.0).roundToLong()
        return if (cents % 100L == 0L) {
            "$" + String.format(Locale.US, "%,d", cents / 100L)
        } else {
            "$" + String.format(Locale.US, "%,.2f", cents / 100.0)
        }
    }

    /** The big line: "$9.45 a year per $10,000", "No yearly fee", or an unknown said as unknown. */
    fun headline(pct: Double?): String = when {
        pct == null -> "Yearly fee unknown"
        pct == 0.0 -> "No yearly fee"
        else -> "${perTenK(pct)} a year per \$10,000"
    }

    /** Why [f]'s fee is unknown, in the one case the screen can say more than "unknown". */
    fun unknownReason(f: FundCost, live: Boolean): String = when {
        f.listedZero -> "Yahoo lists 0%, which for an ETF is usually a fee waiver that has ended."
        !live -> "Couldn't reach the fee source just now."
        else -> "No fee is listed for this fund."
    }

    /** The fee in the words a first-time reader needs, and the jargon name to match it against. */
    fun explainer(pct: Double): String =
        "${percent(pct)} a year, its “expense ratio”. It comes out of the fund's price bit by " +
            "bit, so you never see a bill."

    /** "Your $12,400: about $11.72 a year". */
    fun holdingLine(value: Double, pct: Double): String =
        if (pct == 0.0) {
            "Your ${dollars(value.roundToLong().toDouble())}: no yearly fee"
        } else {
            "Your ${dollars(value.roundToLong().toDouble())}: about ${yearlyOn(value, pct)} a year"
        }

    /** "Held in FNILX instead: about $11.72 less a year", or null when [other]'s fee is unknown. */
    fun savingLine(value: Double, selfPct: Double, other: FundCost): String? {
        val o = other.expenseRatioPct ?: return null
        return "Held in ${other.symbol} instead: about ${yearlyOn(value, selfPct - o)} less a year"
    }

    /** Funds in [group] that charge strictly less than [self], cheapest first. An unknown fee is
     *  never cheaper — it is unknown. */
    fun cheaper(self: FundCost, group: List<FundCost>): List<FundCost> {
        val fee = self.expenseRatioPct ?: return emptyList()
        return group
            .filter { it.symbol != self.symbol }
            .filter { o -> o.expenseRatioPct?.let { it < fee - 1e-9 } == true }
            .sortedBy { it.expenseRatioPct }
    }

    /** True when [self] has a known fee, at least one look-alike does too, and none charges less. */
    fun isLowest(self: FundCost, group: List<FundCost>): Boolean =
        self.expenseRatioPct != null &&
            group.any { it.symbol != self.symbol && it.expenseRatioPct != null } &&
            cheaper(self, group).isEmpty()

    /**
     * The one comparison line under the fee: the cheapest ETF that costs less, and the cheapest of
     * Fidelity's own funds that costs less. That pair is the question as it was asked — a generic
     * ETF versus the near-identical fund at Fidelity, where the buying happens. Null when [self]'s
     * fee is unknown or no look-alike has a known fee.
     */
    fun compareLine(self: FundCost, group: List<FundCost>): String? {
        val fee = self.expenseRatioPct ?: return null
        val known = group.filter { it.expenseRatioPct != null }
        if (known.none { it.symbol != self.symbol }) return null
        val cheaper = cheaper(self, group)
        if (cheaper.isEmpty()) {
            val tied = known.any { it.symbol != self.symbol && it.expenseRatioPct == fee }
            return (if (tied) "Tied for the lowest fee of the " else "Lowest fee of the ") +
                "${known.size} look-alike funds"
        }
        val picks = listOfNotNull(
            cheaper.firstOrNull { it.kind == "etf" },
            cheaper.firstOrNull { it.fidelity },
        ).distinctBy { it.symbol }.ifEmpty { listOf(cheaper.first()) }
        return "Cheaper look-alikes: " + picks.joinToString(" · ") {
            (if (it.fidelity) "Fidelity's " else "") + it.symbol + " " + perTenK(it.expenseRatioPct!!)
        }
    }

    /** One comparison row's fee: "$3", "$25*" (the fund company's figure), "$2†" (saved list), "—". */
    fun rowFee(f: FundCost): String {
        val pct = f.expenseRatioPct ?: return "—"
        return perTenK(pct) + when (f.feeSource) {
            "issuer" -> "*"
            "saved" -> "†"
            else -> ""
        }
    }

    /** The small line under a comparison row; "" when there is nothing worth saying. */
    fun tags(f: FundCost, isSelf: Boolean): String = listOfNotNull(
        "this fund".takeIf { isSelf },
        when {
            f.fidelity && f.isMutualFund -> "Fidelity mutual fund"
            f.fidelity -> "Fidelity ETF"
            f.isMutualFund -> "mutual fund"
            else -> null
        },
        "priced once a day".takeIf { f.isMutualFund },
        "Fidelity-only".takeIf { f.fidelityOnly },
        "earns staking rewards".takeIf { f.staking },
        "Yahoo's 0% is out of date".takeIf { f.listedZero && f.expenseRatioPct == null },
    ).joinToString(" · ")

    /** Yahoo's long fund names, trimmed of the words every fund in the list shares. */
    fun shortName(name: String?): String {
        var n = name?.trim().orEmpty()
        for (suffix in listOf(" ETF Shares", " Index Fund", " ETF Trust", " ETF")) {
            if (n.endsWith(suffix)) n = n.removeSuffix(suffix)
        }
        return n
    }

    /** "Sep 26" from "2026-09-26"; the raw text if it does not parse. */
    fun shortDate(iso: String): String = runCatching {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
    }.getOrDefault(iso)

    /**
     * Where the figures came from and how old they are. The OLDEST live read is the one quoted, so
     * "checked 3h ago" is never fresher than any number it sits under.
     */
    fun sourceLine(rows: List<FundCost>, live: Boolean, now: Long = System.currentTimeMillis()): String {
        val parts = mutableListOf<String>()
        if (!live) parts += "Yahoo didn't answer"
        rows.mapNotNull { it.feeCheckedAt }.minOrNull()?.let { checked ->
            parts += "Fees from Yahoo Finance, checked " + age(now - (checked * 1000).toLong())
        }
        rows.firstOrNull { it.feeSource == "issuer" }?.feeDated?.let {
            parts += "* from the fund company, ${shortDate(it)}"
        }
        rows.firstOrNull { it.feeSource == "saved" }?.feeDated?.let {
            parts += "† from a saved list, ${shortDate(it)}"
        }
        return parts.joinToString(" · ")
    }

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
