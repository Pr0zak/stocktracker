package com.stocktracker.app.signals

import com.stocktracker.app.data.model.PricePoint

/**
 * Rule-based buy/sell signal model (Tier 1 of the AI-signals roadmap — see
 * docs/ai-signals-roadmap.md). Decision support only, long-only, personal use — not advice.
 */

/** A 5-point buy/sell scale, derived from a 0..100 composite score (50 = neutral). */
enum class SignalLabel(val display: String) {
    STRONG_BUY("Strong Buy"),
    BUY("Buy"),
    HOLD("Hold"),
    SELL("Sell"),
    STRONG_SELL("Strong Sell"),
}

/** One contributing indicator's read: [score] in -1..+1 (bearish..bullish), its [weight], and a [reason]. */
data class SignalComponent(
    val name: String,
    val score: Double,
    val weight: Double,
    val reason: String,
) {
    /** Weighted pull on the composite — sign is direction, magnitude is influence. */
    val contribution: Double get() = score * weight
}

/** The composite signal for one bar: a 0..100 [score], its [label], and the [components] behind it. */
data class SignalResult(
    val score: Int,
    val label: SignalLabel,
    val components: List<SignalComponent>,
    val net: Double,               // raw -1..+1 before the 0..100 rescale
    val regimeNote: String? = null,
) {
    val bullishReasons: List<String> get() = components.filter { it.score > 0.05 }.map { it.reason }
    val bearishReasons: List<String> get() = components.filter { it.score < -0.05 }.map { it.reason }
}

/**
 * Tunable knobs for the engine — component weights, label thresholds, and indicator periods.
 * These defaults are sensible priors, NOT researched optima; the deep-research pass (tracked in
 * docs/ai-signals-roadmap.md) refines them. RSI / Bollinger / Stochastic are treated as
 * mean-reversion (fire at extremes); MACD / MA-cross / price-vs-MA as trend; relative strength and
 * volume as confirmation. That deliberate split means a strongly extended move reads closer to
 * neutral than to "chase it" — a feature, not a bug, pending the research tune.
 */
data class SignalWeights(
    val rsi: Double = 1.0,
    val macd: Double = 1.2,
    val priceVsMa: Double = 1.0,
    val maCross: Double = 1.0,
    val bollinger: Double = 0.8,
    val stochastic: Double = 0.6,
    val volume: Double = 0.4,
    // Relative strength / momentum is the best-evidenced factor (Jegadeesh-Titman; see the roadmap's
    // research notes), so it carries the most weight of the confirmation signals.
    val relativeStrength: Double = 1.2,
    val strongBuyThreshold: Double = 72.0,
    val buyThreshold: Double = 60.0,
    val sellThreshold: Double = 40.0,
    val strongSellThreshold: Double = 28.0,
    val rsiPeriod: Int = 14,
    val maFast: Int = 20,
    val maSlow: Int = 50,
    val bollPeriod: Int = 20,
    val stochPeriod: Int = 14,
    val volumeLookback: Int = 20,
    // ~3-month (63 trading-day) relative-strength lookback: the momentum literature's edge lives in
    // the 3–12mo band, and shorter windows sit in the noisier short-horizon-reversal zone.
    val rsPeriod: Int = 63,
    val highVix: Double = 25.0,
    val minComponents: Int = 2,
)

internal fun Double.clampUnit(): Double = coerceIn(-1.0, 1.0)

/**
 * Align a benchmark series to [points] by UTC calendar day, so relative-strength math lines up even
 * when the two series differ in length or trading calendar. Result is the same length as [points];
 * entries with no same-day benchmark bar are null (relative strength skips those bars).
 */
fun alignByDay(points: List<PricePoint>, benchmark: List<PricePoint>): List<Double?> {
    val byDay = HashMap<Long, Double>()
    for (b in benchmark) byDay[b.epochMs / 86_400_000L] = b.price // last bar of a day wins
    return points.map { byDay[it.epochMs / 86_400_000L] }
}
