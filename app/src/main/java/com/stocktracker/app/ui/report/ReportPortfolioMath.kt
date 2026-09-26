package com.stocktracker.app.ui.report

import com.stocktracker.app.data.model.PricePoint
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** One holding's move over a report's period, priced at the two bounding closes. */
@Serializable
data class ReportHolding(
    val symbol: String,
    val name: String,
    val shares: Double,
    val startPrice: Double,
    val endPrice: Double,
    val changeUsd: Double,
    val changePct: Double,
)

/**
 * The report's "your portfolio" section, computed on the phone (the holdings never leave it).
 *
 * Stored per report id (see [ReportPortfolioStore]) so the report keeps describing the week it was
 * made for. [unpriced] names every holding that could not be priced at one of the two closes; those
 * are OUT of every total, and the screen says so — a total that silently drops a holding shrinks and
 * reads as a loss that did not happen.
 */
@Serializable
data class ReportPortfolio(
    val reportId: String,
    val computedAtMs: Long,
    val startValue: Double? = null,
    val endValue: Double? = null,
    val changeUsd: Double? = null,
    val changePct: Double? = null,
    /** Best move first. */
    val holdings: List<ReportHolding> = emptyList(),
    val unpriced: List<String> = emptyList(),
) {
    val priced: Boolean get() = changePct != null && holdings.isNotEmpty()
    val upCount: Int get() = holdings.count { it.changePct > 0 }
    val best: ReportHolding? get() = holdings.firstOrNull()
    val worst: ReportHolding? get() = holdings.lastOrNull()?.takeIf { holdings.size > 1 }
}

/**
 * The arithmetic, kept pure so it can be tested without a network or a DataStore.
 *
 * Uses the shares held NOW, priced at each close — the same simplification the Portfolio tab's "vs
 * S&P" makes, and the screen says it. Per-lot history would need every purchase and sale date, which
 * the app does not reliably have for older holdings.
 *
 * Prices come from Yahoo's daily chart, whose closes are split-adjusted within one fetch, so a split
 * inside the period does not read as a crash here. Dividends are not added back: this is the price
 * change, the same kind of number the report quotes for the market.
 */
object ReportPortfolioMath {

    /** A daily bar's calendar date. UTC, the backend's convention too: a US bar is stamped at the
     *  13:30 UTC open, a crypto bar at 00:00 UTC, and both land on their own trading day. */
    fun barDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()

    /** The close dated exactly [day], or null. Exact on purpose: the day before is not this close. */
    fun closeOn(points: List<PricePoint>, day: LocalDate): Double? =
        points.lastOrNull { !it.extended && barDate(it.epochMs) == day }
            ?.price?.takeIf { it.isFinite() && it > 0.0 }

    data class Input(val symbol: String, val name: String, val shares: Double, val points: List<PricePoint>)

    /** Null when nothing is held at all — "no portfolio" is not "a flat portfolio". */
    fun compute(reportId: String, startClose: LocalDate, end: LocalDate, inputs: List<Input>, nowMs: Long): ReportPortfolio? {
        val held = inputs.filter { it.shares > 0.0 && it.shares.isFinite() }
        if (held.isEmpty()) return null
        val rows = mutableListOf<ReportHolding>()
        val unpriced = mutableListOf<String>()
        for (h in held) {
            val a = closeOn(h.points, startClose)
            val b = closeOn(h.points, end)
            if (a == null || b == null) {
                unpriced += h.symbol
                continue
            }
            rows += ReportHolding(
                symbol = h.symbol, name = h.name, shares = h.shares, startPrice = a, endPrice = b,
                changeUsd = h.shares * (b - a), changePct = (b / a - 1.0) * 100.0,
            )
        }
        if (rows.isEmpty()) return ReportPortfolio(reportId, nowMs, unpriced = unpriced)
        val start = rows.sumOf { it.shares * it.startPrice }
        val endV = rows.sumOf { it.shares * it.endPrice }
        return ReportPortfolio(
            reportId = reportId, computedAtMs = nowMs, startValue = start, endValue = endV,
            changeUsd = endV - start, changePct = (endV / start - 1.0) * 100.0,
            holdings = rows.sortedByDescending { it.changePct }, unpriced = unpriced,
        )
    }
}
