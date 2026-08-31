package com.stocktracker.app.ui.detail

import com.stocktracker.app.data.model.AssetAlerts

/**
 * An armed price alert, resolved for drawing.
 *
 * [offScalePct] is null when the level sits inside the chart's drawable band and is therefore drawn
 * as a line; non-null when it does not, carrying its signed distance from the last plotted close so
 * the caller can say how far away it is instead of silently omitting it.
 */
data class ArmedAlertLevel(
    val label: String,
    val price: Double,
    val rising: Boolean,
    val offScalePct: Double?,
) {
    val isOffScale: Boolean get() = offScalePct != null
}

/**
 * Split the armed PRICE alerts into the ones the chart can draw and the ones it cannot.
 *
 * Two rules, and the second is the reason this function exists.
 *
 * An overlay's value folds into the chart's y-scale, which is why the AI-analyst levels are already
 * clipped to a +/-5% band around the visible series — an armed "below $50" on a $214 stock would
 * otherwise compress the price line into a sliver. Applying that same clip alone, though, makes an
 * armed alert outside the band render as *nothing*: the user set it, and the chart shows no trace.
 * That is the recurring defect this codebase keeps finding — absent data presented as if there were
 * nothing to present — so every clipped level comes back tagged with its distance rather than
 * dropped.
 *
 * `percentUp` / `percentDown` are deliberately absent from the result. They trigger on the day's
 * change, not on a price, and have no position on a price plot; the Alerts card remains the
 * authoritative list of everything armed.
 */
fun armedAlertLevels(
    alerts: AssetAlerts,
    chartMin: Double,
    chartMax: Double,
    lastClose: Double,
    bandPct: Double = 0.05,
    format: (Double) -> String,
): List<ArmedAlertLevel> {
    val lo = chartMin * (1.0 - bandPct)
    val hi = chartMax * (1.0 + bandPct)

    fun level(price: Double, rising: Boolean): ArmedAlertLevel {
        val inBand = price in lo..hi
        val pct = if (!inBand && lastClose > 0.0) (price - lastClose) / lastClose * 100.0 else null
        // A level outside the band with no usable baseline is still off-scale; it just cannot state
        // a distance. Signalling that with 0.0 would claim it sits exactly at the last close.
        val off = if (inBand) null else (pct ?: Double.NaN)
        return ArmedAlertLevel(
            label = (if (rising) "Alert ≥ " else "Alert ≤ ") + format(price),
            price = price,
            rising = rising,
            offScalePct = off,
        )
    }

    return listOfNotNull(
        alerts.priceAbove?.let { level(it, rising = true) },
        alerts.priceBelow?.let { level(it, rising = false) },
    )
}
