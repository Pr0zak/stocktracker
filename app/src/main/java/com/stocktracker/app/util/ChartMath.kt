package com.stocktracker.app.util

import com.stocktracker.app.data.model.PricePoint
import java.util.Locale
import kotlin.math.sqrt

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

/**
 * Simple moving average over [period] points. Returns a list the same length as [values]; the first
 * [period]-1 entries are null (not enough history yet). Returns all-null if [period] exceeds the data.
 */
fun simpleMovingAverage(values: List<Double>, period: Int): List<Double?> {
    if (period < 1 || values.size < period) return List(values.size) { null }
    val out = ArrayList<Double?>(values.size)
    var sum = 0.0
    for (i in values.indices) {
        sum += values[i]
        if (i >= period) sum -= values[i - period]
        out.add(if (i >= period - 1) sum / period else null)
    }
    return out
}

/** Exponential moving average; first [period]-1 entries null, seeded with the SMA of the first window. */
fun exponentialMovingAverage(values: List<Double>, period: Int): List<Double?> {
    if (period < 1 || values.size < period) return List(values.size) { null }
    val out = MutableList<Double?>(values.size) { null }
    val k = 2.0 / (period + 1)
    var ema = 0.0
    for (i in 0 until period) ema += values[i]
    ema /= period
    out[period - 1] = ema
    for (i in period until values.size) {
        ema = values[i] * k + ema * (1 - k)
        out[i] = ema
    }
    return out
}

/** Bollinger Bands: upper/mid/lower = [period]-SMA ± [mult]·(population stdev over the window). */
data class Bands(val upper: List<Double?>, val mid: List<Double?>, val lower: List<Double?>)

fun bollingerBands(values: List<Double>, period: Int = 20, mult: Double = 2.0): Bands {
    val mid = simpleMovingAverage(values, period)
    val upper = MutableList<Double?>(values.size) { null }
    val lower = MutableList<Double?>(values.size) { null }
    for (i in values.indices) {
        val m = mid[i] ?: continue
        var sumSq = 0.0
        for (j in (i - period + 1)..i) {
            val d = values[j] - m
            sumSq += d * d
        }
        val sd = sqrt(sumSq / period)
        upper[i] = m + mult * sd
        lower[i] = m - mult * sd
    }
    return Bands(upper, mid, lower)
}

/** Anchored (cumulative) volume-weighted average price; null until some volume accrues. */
fun vwap(prices: List<Double>, volumes: List<Double?>): List<Double?> {
    val out = MutableList<Double?>(prices.size) { null }
    var cumPV = 0.0
    var cumV = 0.0
    for (i in prices.indices) {
        val v = volumes.getOrNull(i) ?: 0.0
        cumPV += prices[i] * v
        cumV += v
        out[i] = if (cumV > 0.0) cumPV / cumV else null
    }
    return out
}

/** Wilder's Relative Strength Index over [period]; values in [0,100], first [period] entries null. */
fun rsi(values: List<Double>, period: Int = 14): List<Double?> {
    val out = MutableList<Double?>(values.size) { null }
    if (values.size <= period) return out
    var gain = 0.0
    var loss = 0.0
    for (i in 1..period) {
        val ch = values[i] - values[i - 1]
        if (ch >= 0) gain += ch else loss -= ch
    }
    var avgGain = gain / period
    var avgLoss = loss / period
    out[period] = rsiFrom(avgGain, avgLoss)
    for (i in (period + 1) until values.size) {
        val ch = values[i] - values[i - 1]
        avgGain = (avgGain * (period - 1) + if (ch > 0) ch else 0.0) / period
        avgLoss = (avgLoss * (period - 1) + if (ch < 0) -ch else 0.0) / period
        out[i] = rsiFrom(avgGain, avgLoss)
    }
    return out
}

private fun rsiFrom(avgGain: Double, avgLoss: Double): Double = when {
    avgLoss == 0.0 && avgGain == 0.0 -> 50.0 // flat window: RSI is neutral, not 100
    avgLoss == 0.0 -> 100.0
    else -> 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
}

/** MACD: line = EMA(fast) − EMA(slow); signal = EMA(signalP) of the line; histogram = line − signal. */
data class MacdResult(val macd: List<Double?>, val signal: List<Double?>, val histogram: List<Double?>)

/** Stochastic oscillator on close prices: %K over [period], %D = SMA([smoothD]) of %K. Both 0..100. */
fun stochastic(values: List<Double>, period: Int = 14, smoothD: Int = 3): Pair<List<Double?>, List<Double?>> {
    val k = MutableList<Double?>(values.size) { null }
    for (i in values.indices) {
        if (i < period - 1) continue
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (j in (i - period + 1)..i) {
            if (values[j] < lo) lo = values[j]
            if (values[j] > hi) hi = values[j]
        }
        k[i] = if (hi > lo) 100.0 * (values[i] - lo) / (hi - lo) else 50.0
    }
    val firstIdx = k.indexOfFirst { it != null }
    val d = MutableList<Double?>(values.size) { null }
    if (firstIdx >= 0) {
        val tail = k.subList(firstIdx, k.size).map { it ?: 0.0 }
        val dTail = simpleMovingAverage(tail, smoothD)
        for (i in dTail.indices) d[firstIdx + i] = dTail[i]
    }
    return k to d
}

fun macd(values: List<Double>, fast: Int = 12, slow: Int = 26, signalP: Int = 9): MacdResult {
    val emaFast = exponentialMovingAverage(values, fast)
    val emaSlow = exponentialMovingAverage(values, slow)
    val line = MutableList<Double?>(values.size) { null }
    for (i in values.indices) {
        val f = emaFast[i]
        val s = emaSlow[i]
        if (f != null && s != null) line[i] = f - s
    }
    // Signal = EMA over the contiguous non-null tail of the MACD line.
    val firstIdx = line.indexOfFirst { it != null }
    val signal = MutableList<Double?>(values.size) { null }
    if (firstIdx >= 0) {
        val tail = line.subList(firstIdx, line.size).map { it ?: 0.0 }
        val sigTail = exponentialMovingAverage(tail, signalP)
        for (i in sigTail.indices) signal[firstIdx + i] = sigTail[i]
    }
    val hist = MutableList<Double?>(values.size) { null }
    for (i in values.indices) {
        val m = line[i]
        val g = signal[i]
        if (m != null && g != null) hist[i] = m - g
    }
    return MacdResult(line, signal, hist)
}
