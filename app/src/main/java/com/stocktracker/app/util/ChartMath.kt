package com.stocktracker.app.util

import com.stocktracker.app.data.model.PricePoint
import java.util.Locale

/**
 * Rebase a price series to percent change from its first point, so series at very different price
 * levels (a $60k BTC vs a $78 SOL, or a whole portfolio) are visually comparable. The first point
 * becomes 0%. Returns the input unchanged if it has no usable baseline.
 */
fun List<PricePoint>.asPercentChange(): List<PricePoint> {
    val base = firstOrNull { it.price != 0.0 }?.price ?: return this
    return map { it.copy(price = (it.price / base - 1.0) * 100.0) }
}

/** Formats a percent-change value for the chart axis / scrub readout, e.g. "+3.42%". */
fun formatPercentChange(value: Double): String =
    (if (value >= 0) "+" else "") + String.format(Locale.US, "%.2f%%", value)
