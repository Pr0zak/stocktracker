package com.stocktracker.app.ui.marketscan

import com.stocktracker.app.data.remote.MarketScanRow
import com.stocktracker.app.util.Formatting
import java.util.Locale

/**
 * One metric of the nightly scan, with the three things a screen needs to show it honestly: how to
 * read it off a row, how to write it down, and where its rank lives (or that it has none).
 *
 * [percentile] is a function rather than a column name because the ranks arrive as typed fields on
 * [MarketScanRow], and it returns null for the metrics the server deliberately does not rank — see
 * [rankedByServer]. That null then flows through [MetricRank] and comes out as the raw value with
 * no rank beside it, which is the correct rendering for "there is no rank", whatever the reason.
 */
class ScanMetric(
    /** The server's own column name, so a sort key and a metric are the same string. */
    val key: String,
    val label: String,
    val value: (MarketScanRow) -> Double?,
    val percentile: (MarketScanRow) -> Double?,
    /**
     * False for a metric the scan measures but never ranks. Not every unranked number is a gap in
     * the data: per-share dollar levels rank share price rather than the company, and a couple of
     * metrics duplicate an axis that is already ranked. A screen may say "not ranked" for these; it
     * must NOT say "0th percentile", which is what any zero-default would produce.
     */
    val rankedByServer: Boolean = true,
    private val format: (Double) -> String,
) {
    /** The raw measurement as text, or an em dash. Never a zero standing in for a missing number. */
    fun text(row: MarketScanRow): String {
        val v = value(row) ?: return MetricRank.NA
        return if (v.isFinite()) format(v) else MetricRank.NA
    }

    /** "1.40× (96th percentile of 3,101 scanned)" — or just "1.40×" when there is no rank. */
    fun line(row: MarketScanRow, scannedOver: Int? = null): String =
        MetricRank.line(text(row), percentile(row), scannedOver)
}

private fun signed1(v: Double): String = String.format(Locale.US, "%+.1f%%", v)
private fun pct1(v: Double): String = String.format(Locale.US, "%.1f%%", v)
private fun num1(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun ratio(v: Double): String = String.format(Locale.US, "%.2f×", v)
private fun dollars(v: Double): String = "$" + Formatting.compact(v)

/**
 * The metrics worth showing, in the order a reader meets them.
 *
 * The first ten are exactly the ten the server ranks (SWT-4's `PCT_METRICS`), spelled to match its
 * column names. `atr14_pct` is here because a sort offers it and it therefore has to be renderable —
 * with [rankedByServer] false, because the server ranks the volatility axis through `adr20_pct` and
 * inventing a rank for its near-duplicate on the client would be manufacturing a number.
 */
val RANKED_SCAN_METRICS: List<ScanMetric> = listOf(
    ScanMetric("rel_strength_3mo", "Rel. strength (3mo)", { it.relStrength3mo }, { it.relStrength3moPctile }, format = ::signed1),
    ScanMetric("mom_20d", "Momentum (20d)", { it.mom20d }, { it.mom20dPctile }, format = ::signed1),
    ScanMetric("mom_60d", "Momentum (60d)", { it.mom60d }, { it.mom60dPctile }, format = ::signed1),
    ScanMetric("adx14", "Trend strength (ADX)", { it.adx14 }, { it.adx14Pctile }, format = ::num1),
    ScanMetric("rsi14", "RSI (14)", { it.rsi14 }, { it.rsi14Pctile }, format = ::num1),
    ScanMetric("adr20_pct", "Avg daily range", { it.adr20Pct }, { it.adr20PctPctile }, format = ::pct1),
    ScanMetric("rel_volume", "Relative volume", { it.relVolume }, { it.relVolumePctile }, format = ::ratio),
    ScanMetric("dollar_volume_20d", "Dollar volume (20d)", { it.dollarVolume20d }, { it.dollarVolume20dPctile }, format = ::dollars),
    ScanMetric("pct_off_52w_high", "Off 52-week high", { it.pctOff52wHigh }, { it.pctOff52wHighPctile }, format = ::signed1),
    ScanMetric("ema20_slope_pct", "20-EMA slope", { it.ema20SlopePct }, { it.ema20SlopePctPctile }, format = ::signed1),
    // Measured, sortable, and NOT ranked by the server. The null is the point: it renders as a bare
    // number, not as a rank of zero.
    ScanMetric("atr14_pct", "ATR (14) %", { it.atr14Pct }, { null }, rankedByServer = false, format = ::pct1),
)

/**
 * The metric a sort key refers to, or null if we do not know how to render it.
 *
 * Strips the "-" that means ascending: "-atr14_pct" and "atr14_pct" are the same measurement shown
 * from opposite ends, and the direction belongs to the ordering, never to the metric's own reading.
 * Null rather than a fallback to the first metric — showing "Rel. strength" for a list sorted by
 * something else would attach the wrong label to a real number.
 */
fun scanMetricFor(sort: String?): ScanMetric? {
    val key = sort?.trim()?.removePrefix("-")?.takeIf { it.isNotEmpty() } ?: return null
    return RANKED_SCAN_METRICS.firstOrNull { it.key == key }
}
