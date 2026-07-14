package com.stocktracker.app.util

/** Reduce a series to at most [target] evenly-spaced points for a compact sparkline. */
fun List<Double>.downsample(target: Int): List<Double> {
    if (size <= target || target <= 0) return this
    val step = size.toFloat() / target
    return (0 until target).map { this[(it * step).toInt().coerceIn(0, size - 1)] }
}
