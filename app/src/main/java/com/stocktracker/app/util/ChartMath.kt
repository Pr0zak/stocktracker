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
    // The bar extremes have to be rebased along with the close. Copying only `price` would leave a
    // point holding a percentage next to two raw dollar figures, and both consumers read all three:
    // the chart folds high/low into its y-scale (so a $18 low beside a -3% close blows the axis out
    // by three orders of magnitude) and the high/low marker formats them with the percent formatter.
    return map {
        it.copy(
            price = (it.price / base - 1.0) * 100.0,
            high = it.high?.let { h -> (h / base - 1.0) * 100.0 },
            low = it.low?.let { l -> (l / base - 1.0) * 100.0 },
            open = it.open?.let { o -> (o / base - 1.0) * 100.0 },
        )
    }
}

/**
 * The bar's true high / low, falling back to its close when the source reports neither.
 *
 * A close is what the price happened to be at one instant per bar, so a series of closes has no
 * memory of the range traded inside it. That is invisible on the line but decides the high/low
 * markers, and each chart range asks Yahoo for a different bar size — 1D in 1-minute bars, 1W in
 * 5-minute, 1M in 30-minute. Picking extremes from closes therefore gives a different answer per
 * range for the same trading day, and lets a WIDER window report a higher low than the narrower one
 * it contains. Bar extremes nest by construction; closes do not.
 */
fun PricePoint.barHigh(): Double = high ?: price
fun PricePoint.barLow(): Double = low ?: price

/** Index of the highest bar in [from]..[to] inclusive, or -1 when the range is empty. */
fun highIndexIn(points: List<PricePoint>, from: Int, to: Int): Int {
    if (points.isEmpty() || from > to) return -1
    val lo = from.coerceIn(0, points.lastIndex)
    val hi = to.coerceIn(0, points.lastIndex)
    var best = lo
    for (k in lo..hi) if (points[k].barHigh() > points[best].barHigh()) best = k
    return best
}

/** Index of the lowest bar in [from]..[to] inclusive, or -1 when the range is empty. */
fun lowIndexIn(points: List<PricePoint>, from: Int, to: Int): Int {
    if (points.isEmpty() || from > to) return -1
    val lo = from.coerceIn(0, points.lastIndex)
    val hi = to.coerceIn(0, points.lastIndex)
    var best = lo
    for (k in lo..hi) if (points[k].barLow() < points[best].barLow()) best = k
    return best
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

/**
 * Stochastic oscillator, per Pine Script's reference semantics:
 *
 *     %K = 100 * (close - lowest(low, period)) / (highest(high, period) - lowest(low, period))
 *     %D = SMA(%K, smoothD)
 *
 * The window's extremes come from the BAR HIGHS AND LOWS, not from the closes. Taking them from the
 * closes — which this did until 2026-08-30 — makes %K read exactly 0 whenever the current close is
 * the window's lowest close and exactly 100 whenever it is the highest. Measured over 1-2 years of
 * daily bars on AAPL, NVDA, MSFT, AMD, SPY and BTC-USD, that pinned ~30% of bars to an extreme
 * against 0-1% for a true stochastic, and moved 9-19% of bars across the 20/80 zone call. Since
 * [com.stocktracker.app.signals.SignalEngine] scores an extreme reading as maximum conviction, a
 * third of its stochastic component was a binary flag wearing an oscillator's label.
 *
 * A value is produced only where every bar in the window reports a coherent high and low.
 * [barHigh]/[barLow] deliberately are NOT used here: their fallback to the close is right for a
 * high/low marker and would silently reinstate the close-basis formula under a label claiming
 * otherwise. Close-only series — CoinGecko's fallback path, the signals service's Webull history —
 * therefore produce no oscillator at all, and a single nulled bar suppresses only the windows that
 * contain it rather than splicing extremes across the hole.
 */
fun stochastic(
    points: List<PricePoint>,
    period: Int = 14,
    smoothD: Int = 3,
): Pair<List<Double?>, List<Double?>> {
    val k = MutableList<Double?>(points.size) { null }
    for (i in points.indices) {
        if (i < period - 1) continue
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        var complete = true
        for (j in (i - period + 1)..i) {
            val h = points[j].high
            val l = points[j].low
            if (h == null || l == null || !h.isFinite() || !l.isFinite() || h < l) {
                complete = false
                break
            }
            if (l < lo) lo = l
            if (h > hi) hi = h
        }
        if (!complete) continue
        val close = points[i].price
        k[i] = if (hi > lo) 100.0 * (close - lo) / (hi - lo) else 50.0
    }

    // %D is a plain SMA over %K, computed per-window rather than over the contiguous tail: %K can now
    // carry interior holes, and the old `it ?: 0.0` would have folded a hole in as a reading of zero.
    val d = MutableList<Double?>(points.size) { null }
    for (i in points.indices) {
        if (i < smoothD - 1) continue
        var sum = 0.0
        var complete = true
        for (j in (i - smoothD + 1)..i) {
            val v = k[j]
            if (v == null) {
                complete = false
                break
            }
            sum += v
        }
        if (complete) d[i] = sum / smoothD
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

/**
 * Pine's `ta.tr(true)` — the bar's true range, `max(high - low, |high - prevClose|, |low - prevClose|)`.
 *
 * The first bar has no previous close, so it falls back to `high - low`; that is what the `true`
 * argument means in Pine, and it is the form `ta.atr` is defined over. A bar missing either extreme,
 * or reporting a high below its own low, yields null rather than a range computed off the close —
 * the same rule [stochastic] applies, for the same reason: a close has no memory of the range traded
 * inside its bar, so substituting it invents a quiet day that may not have happened.
 */
fun trueRange(points: List<PricePoint>): List<Double?> {
    val out = MutableList<Double?>(points.size) { null }
    for (i in points.indices) {
        val h = points[i].high
        val l = points[i].low
        if (h == null || l == null || !h.isFinite() || !l.isFinite() || h < l) continue
        val prevClose = if (i > 0) points[i - 1].price.takeIf { it.isFinite() } else null
        out[i] = if (prevClose == null) {
            h - l
        } else {
            maxOf(h - l, kotlin.math.abs(h - prevClose), kotlin.math.abs(l - prevClose))
        }
    }
    return out
}

/**
 * Pine's `ta.rma` — Wilder's smoothing: `alpha = 1 / length`, seeded with the SMA of the first full
 * window.
 *
 * It is NOT [exponentialMovingAverage] with `2 * length - 1`. The recursion coefficient of the two
 * does coincide — `2 / ((2n - 1) + 1) = 1 / n` — which is why the substitution is so often made, and
 * TradingView's own DMI help page describes the smoothing as an "Exponential Moving Average" while
 * the shipped Pine uses `ta.rma`. The SEED does not coincide: this module's EMA seeds on the mean of
 * the first `2n - 1` values and so produces nothing until index `2n - 2`, where RMA seeds on the mean
 * of the first `n` and starts at index `n - 1`. For n = 14 that is a 13-bar difference in where the
 * series begins and a permanently different level thereafter, since neither ever forgets its seed.
 *
 * A null breaks the recursion rather than being skipped or treated as zero: the series restarts,
 * re-seeding from the first full window of values after the hole. Carrying the average across a gap
 * would smooth two non-adjacent runs into one.
 */
fun wilderRma(values: List<Double?>, period: Int): List<Double?> {
    val out = MutableList<Double?>(values.size) { null }
    if (period < 1 || values.isEmpty()) return out
    val alpha = 1.0 / period

    var prev: Double? = null
    var runStart = -1          // index where the current unbroken non-null run began
    for (i in values.indices) {
        val v = values[i]
        if (v == null || !v.isFinite()) {
            prev = null
            runStart = -1
            continue
        }
        if (runStart < 0) runStart = i
        if (prev != null) {
            prev = alpha * v + (1.0 - alpha) * prev
            out[i] = prev
        } else if (i - runStart + 1 >= period) {
            var sum = 0.0
            for (j in (i - period + 1)..i) sum += values[j]!!
            prev = sum / period
            out[i] = prev
        }
    }
    return out
}

/** Pine's `ta.atr` — [wilderRma] of [trueRange]. In price units, so it reads as a stop distance. */
fun atr(points: List<PricePoint>, period: Int = 14): List<Double?> =
    wilderRma(trueRange(points), period)

/**
 * The median gap between consecutive bars, in milliseconds, or null when there are too few to tell.
 *
 * Read off the plotted data rather than derived from the requested range, because the range does not
 * determine the bar size on its own: `ChartRange.MONTH` is 30-minute bars for a stock
 * (`YahooFinanceService.rangeParams`) and daily bars for crypto (`YahooFinanceService.cryptoHistory`).
 * A label built from the range would therefore be wrong for one of the two. Median, not mean, so
 * weekends and holidays do not stretch a daily series into something else.
 */
fun medianBarSpacingMs(points: List<PricePoint>): Long? {
    if (points.size < 3) return null
    val gaps = ArrayList<Long>(points.size - 1)
    for (i in 1 until points.size) {
        val d = points[i].epochMs - points[i - 1].epochMs
        if (d > 0) gaps.add(d)
    }
    if (gaps.isEmpty()) return null
    gaps.sort()
    return gaps[gaps.size / 2]
}

/**
 * A short human label for a bar size — "5m", "1h", "1d". Snapped to the nearest common interval
 * rather than printed exactly, since real feeds jitter by seconds.
 *
 * This exists so an indicator denominated in bars cannot be misread as one denominated in days. An
 * "ATR 14" beside a stop distance means fourteen DAYS of range on a 1Y chart and fourteen MINUTES of
 * it on a 1D chart, and nothing on the pane would otherwise say which.
 */
fun barSpacingLabel(ms: Long?): String? {
    if (ms == null || ms <= 0) return null
    val known = listOf(
        60_000L to "1m", 300_000L to "5m", 900_000L to "15m", 1_800_000L to "30m",
        3_600_000L to "1h", 86_400_000L to "1d", 604_800_000L to "1wk", 2_592_000_000L to "1mo",
    )
    val best = known.minByOrNull { kotlin.math.abs(it.first - ms) } ?: return null
    // Within 25% of a known interval, call it that; otherwise say nothing rather than guess.
    return if (kotlin.math.abs(best.first - ms).toDouble() / best.first <= 0.25) best.second else null
}

/** One price bucket of a volume profile: how much traded in [lo]..[hi], split by bar direction. */
data class VolumeRow(val lo: Double, val hi: Double, val up: Double, val down: Double) {
    val total: Double get() = up + down
    val mid: Double get() = (lo + hi) / 2.0
}

/**
 * A volume profile over a window of bars: where volume transacted, rather than when.
 *
 * [poc] is the point of control — the price bucket that traded the most. [valueAreaLow]..[valueAreaHigh]
 * is the contiguous band around it holding [VALUE_AREA_SHARE] of the window's volume.
 *
 * [method] describes how the numbers were made and is meant to be shown, not stored. This profile
 * spreads each bar's volume UNIFORMLY across its own high-low range, which is not TradingView's
 * method — they build profiles from lower-timeframe intrabar data, which this app does not fetch. The
 * shapes agree; the numbers will not reconcile with a TradingView chart, and a reader comparing the
 * two deserves to know why rather than to discover it.
 */
data class VolumeProfile(
    val rows: List<VolumeRow>,
    val poc: Double,
    val valueAreaLow: Double,
    val valueAreaHigh: Double,
    val method: String,
)

/** The conventional share of volume the value area covers. */
const val VALUE_AREA_SHARE = 0.70

/**
 * Build a volume profile over `points[from..to]`.
 *
 * Returns null rather than a degenerate profile whenever the window cannot support one. The gate is
 * PER BAR, not per source, and deliberately reads `high`/`low` directly instead of [barHigh]/[barLow]:
 * those fall back to the close, which would collapse every bar onto a single bucket and draw a
 * confident one-row profile out of a series that reports no ranges at all. The close-only paths —
 * CoinGecko's fallback and the signals service's Webull history — are excluded by exactly that test.
 *
 * The CoinGecko path is worth naming twice, because it would be wrong here for a second and
 * independent reason: its `volume` is `total_volumes`, a ROLLING 24-HOUR figure sampled once per
 * point, so binning it counts the same trades once per bar rather than once.
 */
fun volumeProfile(points: List<PricePoint>, from: Int, to: Int, rows: Int = 64): VolumeProfile? {
    if (rows < 2 || points.isEmpty()) return null
    val lo0 = from.coerceIn(0, points.lastIndex)
    val hi0 = to.coerceIn(0, points.lastIndex)
    if (hi0 <= lo0) return null

    val window = points.subList(lo0, hi0 + 1)
    // Every bar in the window must carry a coherent range AND a volume. A partial window would
    // profile some of the sessions and present the result as all of them.
    if (window.any { p ->
            val h = p.high
            val l = p.low
            val v = p.volume
            h == null || l == null || v == null || !h.isFinite() || !l.isFinite() || h < l || v <= 0.0
        }
    ) return null

    val pMin = window.minOf { it.low!! }
    val pMax = window.maxOf { it.high!! }
    if (!(pMax > pMin)) return null

    val step = (pMax - pMin) / rows
    val up = DoubleArray(rows)
    val down = DoubleArray(rows)

    for (p in window) {
        val l = p.low!!
        val h = p.high!!
        val v = p.volume!!
        // A bar that closed at or above its open is buying volume — the convention TradingView
        // documents. A bar with no open reported still counts toward the profile, because its volume
        // is real, but it is split evenly rather than assigned a direction it does not have: the
        // colouring then reads as neutral instead of as a claim about who was buying.
        val o = p.open
        val first = ((l - pMin) / step).toInt().coerceIn(0, rows - 1)
        val last = ((h - pMin) / step).toInt().coerceIn(0, rows - 1)
        val share = v / (last - first + 1)
        for (r in first..last) {
            when {
                o == null -> { up[r] += share / 2.0; down[r] += share / 2.0 }
                p.price >= o -> up[r] += share
                else -> down[r] += share
            }
        }
    }

    val buckets = (0 until rows).map {
        VolumeRow(lo = pMin + it * step, hi = pMin + (it + 1) * step, up = up[it], down = down[it])
    }
    val total = buckets.sumOf { it.total }
    if (total <= 0.0) return null

    val pocIdx = buckets.indices.maxByOrNull { buckets[it].total } ?: return null

    // TradingView's documented value-area walk: start at the point of control, repeatedly compare
    // the next row above against the next row below, add the larger, and advance only that side.
    // Ties resolve toward the POC and then upward. Stop once the accumulated volume reaches the
    // target share.
    var lowIdx = pocIdx
    var highIdx = pocIdx
    var acc = buckets[pocIdx].total
    val target = total * VALUE_AREA_SHARE
    while (acc < target && (lowIdx > 0 || highIdx < rows - 1)) {
        val above = if (highIdx < rows - 1) buckets[highIdx + 1].total else -1.0
        val below = if (lowIdx > 0) buckets[lowIdx - 1].total else -1.0
        if (above >= below) {
            highIdx++
            acc += above
        } else {
            lowIdx--
            acc += below
        }
    }

    return VolumeProfile(
        rows = buckets,
        poc = buckets[pocIdx].mid,
        valueAreaLow = buckets[lowIdx].lo,
        valueAreaHigh = buckets[highIdx].hi,
        method = "volume spread evenly across each bar's range — not TradingView's intrabar method",
    )
}
