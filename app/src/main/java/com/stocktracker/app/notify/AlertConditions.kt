package com.stocktracker.app.notify

import com.stocktracker.app.data.model.AlertCondition
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.util.simpleMovingAverage

/**
 * Evaluates the armed technical conditions on-device, against daily bars.
 *
 * The whole point of this file is the third outcome. A condition is not "true or false": it is true,
 * false, or **unanswerable**, and the app must be able to say which. An alert that quietly stops
 * evaluating — because the fetch failed, because the series is too short, because the bars are
 * corrupt — looks exactly like an alert that has never triggered, and the user has no way to tell a
 * quiet market from a broken one.
 */
sealed interface ConditionResult {
    data object Triggered : ConditionResult
    data object NotTriggered : ConditionResult

    /** Answerable in principle, not today. [reason] is shown to the user, not swallowed. */
    data class CouldNotCheck(val reason: String) : ConditionResult
}

/**
 * The largest single-bar ratio that is a price move rather than a data error.
 *
 * Borrowed from the signals backend, which rejects roughly a dozen names a night at this threshold —
 * BYND has been observed oscillating 0.59 → 17.85 → 0.56 on Yahoo's mixed split basis. The on-device
 * path had no equivalent, so a corrupted series would have produced a confident "closed at a 52-week
 * high" from a pre-split bar.
 */
private const val MAX_BAR_RATIO = 10.0

/** Bars older than this are not a reading about today, whatever the fetch reported. */
private const val MAX_LAST_BAR_AGE_MS = 5L * 24 * 60 * 60 * 1000  // 5 days, to clear a long weekend

object AlertConditions {

    /**
     * The worst single-bar move in the series, as a ratio, or null when there is nothing to measure.
     *
     * Guards against a split break rather than against volatility: a genuine 10x session does not
     * happen in an equity, and when it appears it is two price bases spliced together.
     */
    fun worstBarRatio(points: List<PricePoint>): Double? {
        var worst: Double? = null
        for (i in 1 until points.size) {
            val a = points[i - 1].price
            val b = points[i].price
            if (a <= 0.0 || b <= 0.0 || !a.isFinite() || !b.isFinite()) continue
            val r = if (b > a) b / a else a / b
            if (worst == null || r > worst) worst = r
        }
        return worst
    }

    /**
     * Evaluate one condition against [points], which must be DAILY bars.
     *
     * [nowMs] is passed rather than read so the staleness rule is testable. It is checked against the
     * last bar's own timestamp, not against whether the fetch threw: MarketRepository's cache has an
     * unbounded stale-while-error branch that returns the last good value with no age bound at all,
     * so a successful-looking call can hand back week-old bars.
     */
    fun evaluate(condition: AlertCondition, points: List<PricePoint>, nowMs: Long): ConditionResult {
        if (points.size < condition.minBars) {
            return ConditionResult.CouldNotCheck(
                "needs ${condition.minBars} daily bars, has ${points.size}"
            )
        }
        val last = points.last()
        val ageMs = nowMs - last.epochMs
        if (ageMs > MAX_LAST_BAR_AGE_MS) {
            return ConditionResult.CouldNotCheck("price history is ${ageMs / 86_400_000} days old")
        }
        worstBarRatio(points)?.let { r ->
            if (r >= MAX_BAR_RATIO) {
                return ConditionResult.CouldNotCheck(
                    "price history has a ${"%.0f".format(r)}x single-day break — looks like a split, not a move"
                )
            }
        }

        val closes = points.map { it.price }
        val close = closes.last()

        fun crossesSma(period: Int, above: Boolean): ConditionResult {
            val sma = simpleMovingAverage(closes, period).lastOrNull()
                ?: return ConditionResult.CouldNotCheck("the ${period}-day average is not warmed up yet")
            return if (above == (close > sma)) ConditionResult.Triggered else ConditionResult.NotTriggered
        }

        return when (condition) {
            AlertCondition.CLOSE_ABOVE_SMA50 -> crossesSma(50, above = true)
            AlertCondition.CLOSE_BELOW_SMA50 -> crossesSma(50, above = false)
            AlertCondition.CLOSE_ABOVE_SMA200 -> crossesSma(200, above = true)
            AlertCondition.CLOSE_BELOW_SMA200 -> crossesSma(200, above = false)
            AlertCondition.CLOSE_AT_52W_HIGH -> {
                // Deliberately compared against prior CLOSES, not against barHigh(): the condition is
                // "closed at a 52-week high", and barHigh() falls back to the close on a source that
                // reports no extremes, which would silently switch the question being asked from a
                // closing high to an intraday one depending on where the data came from.
                val window = closes.takeLast(252)
                val priorMax = window.dropLast(1).maxOrNull()
                    ?: return ConditionResult.CouldNotCheck("no prior year to compare against")
                if (close >= priorMax) ConditionResult.Triggered else ConditionResult.NotTriggered
            }
        }
    }
}
