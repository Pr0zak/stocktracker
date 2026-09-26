package com.stocktracker.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire models for the weekly and monthly report (RPT-1) — GET /reports, /report/latest and
 * /report/{id} on the Signals service.
 *
 * EVERY NUMBER IS NULLABLE. Http.json decodes with `coerceInputValues = true`, so a non-nullable
 * `Double = 0.0` would turn "this index could not be measured" into "flat this week". A null here is
 * rendered as "—" or the row is left out; it is never drawn as zero.
 *
 * The report carries the market and the AI sandbox. The user's own portfolio is NOT in it — the
 * holdings never leave the phone — and is computed on-device (see ui/report/ReportPortfolioMath.kt).
 */

@Serializable
data class ReportList(val reports: List<ReportSummary> = emptyList())

/** One row of the Reports list, and what the notifier needs to word an alert. */
@Serializable
data class ReportSummary(
    val id: String? = null,
    /** "week" | "month". */
    val kind: String? = null,
    /** "Sep 21 – 25" or "September 2026". */
    val label: String? = null,
    /** yyyy-MM-dd of the close the period is measured FROM (the session before it began). */
    @SerialName("start_close") val startClose: String? = null,
    /** yyyy-MM-dd of the period's last session. */
    val end: String? = null,
    /** Epoch seconds the backend built the report. */
    @SerialName("made_at") val madeAt: Double? = null,
    val headline: String? = null,
    @SerialName("sp500_pct") val sp500Pct: Double? = null,
    @SerialName("sandbox_pct") val sandboxPct: Double? = null,
    @SerialName("sandbox_change_usd") val sandboxChangeUsd: Double? = null,
)

/** A whole report. Read [available] first: false carries [reason] and nothing else. */
@Serializable
data class Report(
    val available: Boolean = false,
    val reason: String? = null,
    val version: Int? = null,
    val id: String? = null,
    val kind: String? = null,
    @SerialName("start_close") val startClose: String? = null,
    val end: String? = null,
    val label: String? = null,
    /** Trading sessions inside the period (4 in a holiday week). */
    val sessions: Int? = null,
    @SerialName("made_at") val madeAt: Double? = null,
    /** One plain sentence, built from measured numbers only (never model-written). */
    val headline: String? = null,
    val market: ReportMarket? = null,
    @SerialName("daily_pick") val dailyPick: ReportDailyPick? = null,
    val sandbox: ReportSandbox? = null,
) {
    val isMonth: Boolean get() = kind == "month"
    fun index(key: String): ReportIndex? = market?.indexes?.firstOrNull { it.key == key }
    val sp500Pct: Double? get() = index("sp500")?.pct
}

@Serializable
data class ReportMarket(
    val indexes: List<ReportIndex> = emptyList(),
    /** The equal-weight S&P (RSP): how the typical big company did, next to the S&P itself. */
    @SerialName("typical_stock") val typicalStock: ReportIndex? = null,
    val breadth: ReportBreadth? = null,
    /** Best first; an unmeasured sector has a null [ReportSector.pct] and sorts last. */
    val sectors: List<ReportSector> = emptyList(),
    val stocks: ReportMovers? = null,
    val etfs: ReportMovers? = null,
    /** The S&P's move each session — monthly reports only. */
    val daily: List<ReportDay>? = null,
    /** Named rows (indexes, sectors, funds) that could not be loaded. */
    val failed: List<String> = emptyList(),
)

/** One index/market row. [unit] "pct" quotes [pct]; "pts" quotes [change] in the level's own units. */
@Serializable
data class ReportIndex(
    val key: String? = null,
    val symbol: String? = null,
    val name: String? = null,
    val note: String? = null,
    val unit: String? = null,
    val measured: Boolean = false,
    val close: Double? = null,
    val start: Double? = null,
    val pct: Double? = null,
    val change: Double? = null,
)

@Serializable
data class ReportBreadth(
    val up: Int? = null,
    val down: Int? = null,
    val flat: Int? = null,
    val measured: Int? = null,
    @SerialName("up_share") val upShare: Double? = null,
    val universe: String? = null,
)

@Serializable
data class ReportSector(val symbol: String? = null, val name: String? = null, val pct: Double? = null)

@Serializable
data class ReportMover(
    val symbol: String? = null,
    /** A plain name: "Moderna" for a stock, "Chip makers" for a fund. */
    val name: String? = null,
    val pct: Double? = null,
    val close: Double? = null,
    /** True when a split inside the period meant the move was measured on split-adjusted prices. */
    val split: Boolean? = null,
)

@Serializable
data class ReportMovers(
    val best: List<ReportMover> = emptyList(),
    val worst: List<ReportMover> = emptyList(),
    /** What the list was drawn from, in words: "Companies worth $10B or more". */
    val universe: String? = null,
    val measured: Int? = null,
    val attempted: Int? = null,
)

@Serializable
data class ReportDay(val date: String? = null, val pct: Double? = null)

@Serializable
data class ReportDailyPick(
    val runs: Int? = null,
    val picks: Int? = null,
    val symbols: List<String> = emptyList(),
    val failed: Int? = null,
    /** Why there were no picks, in words — only when there were none. */
    val reason: String? = null,
)

@Serializable
data class ReportSandbox(
    val available: Boolean = false,
    val reason: String? = null,
    /** When the books are measured — the sandbox marks them at its 3:35 PM ET check. */
    val note: String? = null,
    val main: ReportBook? = null,
    val trades: ReportTrades? = null,
    /** Every test book, best first. */
    val arms: List<ReportBook> = emptyList(),
)

/** One sandbox book over the period, deposits taken out. */
@Serializable
data class ReportBook(
    val arm: String? = null,
    val label: String? = null,
    val main: Boolean = false,
    val measured: Boolean = false,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("start_equity") val startEquity: Double? = null,
    @SerialName("end_equity") val endEquity: Double? = null,
    val deposits: Double? = null,
    /** Dollars the market moved the book, deposits excluded. */
    @SerialName("change_usd") val changeUsd: Double? = null,
    /** Time-weighted percent, deposits excluded. */
    @SerialName("change_pct") val changePct: Double? = null,
    /** The book's own "same money in the S&P" shadow, measured at the same moments. */
    @SerialName("bench_pct") val benchPct: Double? = null,
    @SerialName("bench_change_usd") val benchChangeUsd: Double? = null,
    @SerialName("vs_pts") val vsPts: Double? = null,
    val cash: Double? = null,
    @SerialName("cash_pct") val cashPct: Double? = null,
    /** False when the book's last check before the period ended was on an earlier day. */
    @SerialName("measured_through_end") val measuredThroughEnd: Boolean? = null,
)

@Serializable
data class ReportTrades(
    val fills: List<ReportFill> = emptyList(),
    val blocked: List<ReportBlocked> = emptyList(),
    val buys: Int? = null,
    val sells: Int? = null,
    @SerialName("blocked_count") val blockedCount: Int? = null,
    val interest: Double? = null,
)

@Serializable
data class ReportFill(
    val date: String? = null,
    /** "buy" | "sell". */
    val side: String? = null,
    val symbol: String? = null,
    val shares: Double? = null,
    val price: Double? = null,
    val gross: Double? = null,
    @SerialName("realized_pl") val realizedPl: Double? = null,
    /** Days the oldest shares sold were held (first in, first out). */
    @SerialName("held_days") val heldDays: Int? = null,
    /** "quick_loss" when a sale at a loss came within 30 days of buying. */
    val flag: String? = null,
)

@Serializable
data class ReportBlocked(
    val symbol: String? = null,
    val side: String? = null,
    val count: Int? = null,
    val dates: List<String> = emptyList(),
    /** Why, in words: "Not enough cash for one share". */
    val reason: String? = null,
)
