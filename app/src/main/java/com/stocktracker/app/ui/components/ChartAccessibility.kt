package com.stocktracker.app.ui.components

/**
 * The sentence a sighted user gets for free by looking at [PriceChart]: what's plotted, over what
 * range, ending at what value — the summary this app already prints above the chart as separate
 * Text (the big price, the "Today" change line), just assembled into one string for the Canvas that
 * otherwise carries none of it to a screen reader.
 *
 * [changeLine] is the caller's already-formatted "▲ +$2.10 (+1.42%)"-style string (or null when
 * there's nothing to report, e.g. a benchmark/equity curve with no day-over-day quote). Passed
 * through rather than recomputed here so this stays a pure function of already-known display text,
 * not a second copy of Formatting's sign/arrow logic.
 *
 * PLAT-4.
 */
fun priceChartDescription(
    symbol: String,
    rangeLabel: String,
    percentMode: Boolean,
    currentValueText: String,
    changeLine: String? = null,
): String {
    val kind = if (percentMode) "percent change" else "price"
    val head = "$symbol $kind chart, $rangeLabel, currently $currentValueText"
    return if (changeLine.isNullOrBlank()) head else "$head, $changeLine"
}
