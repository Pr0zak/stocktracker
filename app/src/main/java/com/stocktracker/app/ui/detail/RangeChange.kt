package com.stocktracker.app.ui.detail

import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import java.util.Locale
import kotlin.math.abs

/**
 * How much the price moved across the range on screen — first plotted point to last.
 *
 * The chart used to take its colour from TODAY's change, so a month that rose 3.3% was drawn red on
 * a down day. The colour and this line now both describe the range the reader is looking at; today's
 * move keeps its own line above the chart.
 */
object RangeChange {

    /** (change, percent) from the first to the last point, or null when it cannot be measured. */
    fun of(points: List<PricePoint>): Pair<Double, Double>? {
        val first = points.firstOrNull()?.price ?: return null
        val last = points.lastOrNull()?.price ?: return null
        if (points.size < 2 || !first.isFinite() || !last.isFinite() || first <= 0.0) return null
        return (last - first) to (last / first - 1.0) * 100.0
    }

    /** "the past month", or null for 1D — today's move already has its own line. */
    fun phrase(range: ChartRange): String? = when (range) {
        ChartRange.DAY -> null
        ChartRange.WEEK -> "the past week"
        ChartRange.MONTH -> "the past month"
        ChartRange.QUARTER -> "the past 3 months"
        ChartRange.YEAR -> "the past year"
        ChartRange.THREE_YEAR -> "the past 3 years"
        ChartRange.ALL -> "all the history shown"
    }

    /** "▲ +3.35% over the past month". Null when there is no range line to show. */
    fun line(points: List<PricePoint>, range: ChartRange): String? {
        val span = phrase(range) ?: return null
        val (_, pct) = of(points) ?: return null
        val arrow = if (pct >= 0) "▲" else "▼"
        return String.format(Locale.US, "%s %+.2f%% over %s", arrow, pct, span).replace("-", "−").let {
            if (abs(pct) < 0.005) "No change over $span" else it
        }
    }
}
