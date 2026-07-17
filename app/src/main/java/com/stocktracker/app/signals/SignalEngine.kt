package com.stocktracker.app.signals

import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.util.bollingerBands
import com.stocktracker.app.util.macd
import com.stocktracker.app.util.rsi
import com.stocktracker.app.util.simpleMovingAverage
import com.stocktracker.app.util.stochastic
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Precomputed causal indicator arrays for a series — each index depends only on data at or before it. */
class SignalContext internal constructor(
    val prices: List<Double>,
    val volumes: List<Double?>,
    val benchmark: List<Double?>?,     // aligned to prices, nullable per element
    val rsi: List<Double?>,
    val smaFast: List<Double?>,
    val smaSlow: List<Double?>,
    val macdLine: List<Double?>,
    val macdHist: List<Double?>,
    val bollUpper: List<Double?>,
    val bollLower: List<Double?>,
    val stochK: List<Double?>,
    val stochD: List<Double?>,
) {
    val size get() = prices.size
}

/**
 * Turns the technical indicators the app already computes (ChartMath) into a single 0..100 buy/sell
 * score with a per-component "why". Weights/thresholds live in [weights] so they can be tuned or
 * backtested without touching this logic.
 *
 * Causality: [prepare] computes every indicator over the full series, but each array element uses
 * only data at or before its index (the ChartMath functions are all causal), so [evaluateAt] at bar
 * i reads no future information — the same value you'd get computing the indicator over
 * prices[0..i]. This is what makes the backtest honest.
 */
class SignalEngine(val weights: SignalWeights = SignalWeights()) {

    fun prepare(
        prices: List<Double>,
        volumes: List<Double?> = emptyList(),
        benchmark: List<Double?>? = null,
    ): SignalContext {
        val m = macd(prices)
        val (k, d) = stochastic(prices, weights.stochPeriod)
        val boll = bollingerBands(prices, weights.bollPeriod)
        return SignalContext(
            prices = prices,
            volumes = if (volumes.size == prices.size) volumes else List(prices.size) { null },
            benchmark = benchmark?.takeIf { it.size == prices.size },
            rsi = rsi(prices, weights.rsiPeriod),
            smaFast = simpleMovingAverage(prices, weights.maFast),
            smaSlow = simpleMovingAverage(prices, weights.maSlow),
            macdLine = m.macd,
            macdHist = m.histogram,
            bollUpper = boll.upper,
            bollLower = boll.lower,
            stochK = k,
            stochD = d,
        )
    }

    /** Evaluate the composite signal at bar [i], optionally dampening conviction in a high-[vix] regime. */
    fun evaluateAt(ctx: SignalContext, i: Int, vix: Double? = null): SignalResult {
        val comps = ArrayList<SignalComponent>(8)
        fun add(name: String, weight: Double, pair: Pair<Double, String>?) {
            if (pair != null && weight > 0.0) {
                comps.add(SignalComponent(name, pair.first.clampUnit(), weight, pair.second))
            }
        }
        add("RSI", weights.rsi, rsiComp(ctx, i))
        add("MACD", weights.macd, macdComp(ctx, i))
        add("Trend", weights.priceVsMa, priceVsMaComp(ctx, i))
        add("MA cross", weights.maCross, maCrossComp(ctx, i))
        add("Bollinger", weights.bollinger, bollingerComp(ctx, i))
        add("Stochastic", weights.stochastic, stochComp(ctx, i))
        add("Volume", weights.volume, volumeComp(ctx, i))
        add("Rel. strength", weights.relativeStrength, relStrengthComp(ctx, i))

        val sorted = comps.sortedByDescending { abs(it.contribution) }
        if (comps.size < weights.minComponents) {
            return SignalResult(50, SignalLabel.HOLD, sorted, 0.0, "Not enough history for a signal")
        }

        val wsum = comps.sumOf { it.weight }
        var net = if (wsum > 0.0) comps.sumOf { it.contribution } / wsum else 0.0

        var regimeNote: String? = null
        if (vix != null && vix >= weights.highVix) {
            net *= HIGH_VIX_DAMPEN
            regimeNote = "High-volatility regime (VIX ${vix.roundToInt()}) — conviction dampened"
        }
        net = net.clampUnit()

        val score = (50.0 + 50.0 * net).roundToInt().coerceIn(0, 100)
        return SignalResult(score, labelFor(score), sorted, net, regimeNote)
    }

    /** Evaluate the latest bar of a [points] series (daily bars recommended for swing signals). */
    fun evaluate(points: List<PricePoint>, benchmark: List<PricePoint>? = null, vix: Double? = null): SignalResult? {
        if (points.size < weights.rsiPeriod + 2) return null
        val prices = points.map { it.price }
        val volumes = points.map { it.volume }
        val bench = benchmark?.let { alignByDay(points, it) }
        val ctx = prepare(prices, volumes, bench)
        return evaluateAt(ctx, points.lastIndex, vix)
    }

    private fun labelFor(score: Int): SignalLabel = when {
        score >= weights.strongBuyThreshold -> SignalLabel.STRONG_BUY
        score >= weights.buyThreshold -> SignalLabel.BUY
        score <= weights.strongSellThreshold -> SignalLabel.STRONG_SELL
        score <= weights.sellThreshold -> SignalLabel.SELL
        else -> SignalLabel.HOLD
    }

    // --- components: each returns (score in -1..+1, human reason) or null if not computable at i ---

    /** RSI as mean-reversion: bullish when oversold, bearish when overbought, neutral in the middle band. */
    private fun rsiComp(ctx: SignalContext, i: Int): Pair<Double, String>? {
        val r = ctx.rsi.getOrNull(i) ?: return null
        val s = when {
            r < 35 -> (35 - r) / 15.0
            r > 65 -> (65 - r) / 15.0
            else -> 0.0
        }
        val zone = when { r < 35 -> "oversold"; r > 65 -> "overbought"; else -> "neutral" }
        return s to "RSI ${r.roundToInt()} ($zone)"
    }

    /** MACD trend + momentum: histogram sign (vs signal) and line sign (vs zero line). */
    private fun macdComp(ctx: SignalContext, i: Int): Pair<Double, String>? {
        val line = ctx.macdLine.getOrNull(i) ?: return null
        val hist = ctx.macdHist.getOrNull(i) ?: return null
        // Three-way (not a bare sign test) so an exactly-flat series reads neutral, not bearish.
        val mom = if (hist > 0) 0.6 else if (hist < 0) -0.6 else 0.0
        val trend = if (line > 0) 0.4 else if (line < 0) -0.4 else 0.0
        val s = mom + trend
        val desc = (if (hist > 0) "above" else "below") + " signal, " + (if (line > 0) "above" else "below") + " zero"
        return s to "MACD $desc"
    }

    /** Trend: price above/below the fast MA. */
    private fun priceVsMaComp(ctx: SignalContext, i: Int): Pair<Double, String>? {
        val ma = ctx.smaFast.getOrNull(i) ?: return null
        if (ma == 0.0) return null
        val dev = ctx.prices[i] / ma - 1.0
        val where = if (dev >= 0) "above" else "below"
        return (dev / 0.05) to "Price ${abs((dev * 100).roundToInt())}% $where SMA${weights.maFast}"
    }

    /** Trend: fast MA above/below slow MA (golden / death cross regime). */
    private fun maCrossComp(ctx: SignalContext, i: Int): Pair<Double, String>? {
        val fast = ctx.smaFast.getOrNull(i) ?: return null
        val slow = ctx.smaSlow.getOrNull(i) ?: return null
        if (slow == 0.0) return null
        val gap = fast / slow - 1.0
        val dir = if (gap >= 0) "above" else "below"
        val trend = if (gap >= 0) "uptrend" else "downtrend"
        return (gap / 0.03) to "SMA${weights.maFast} $dir SMA${weights.maSlow} ($trend)"
    }

    /** Bollinger %B as mean-reversion: bullish at the lower band, bearish at the upper band. */
    private fun bollingerComp(ctx: SignalContext, i: Int): Pair<Double, String>? {
        val up = ctx.bollUpper.getOrNull(i) ?: return null
        val lo = ctx.bollLower.getOrNull(i) ?: return null
        if (up <= lo) return null
        val pctB = (ctx.prices[i] - lo) / (up - lo)
        val zone = when { pctB <= 0.1 -> "at lower band"; pctB >= 0.9 -> "at upper band"; else -> "mid-band" }
        return ((0.5 - pctB) * 2.0) to "Bollinger $zone"
    }

    /** Stochastic: mean-reversion at extremes plus the %K/%D cross. */
    private fun stochComp(ctx: SignalContext, i: Int): Pair<Double, String>? {
        val k = ctx.stochK.getOrNull(i) ?: return null
        val d = ctx.stochD.getOrNull(i)
        val extreme = when {
            k < 20 -> (20 - k) / 20.0
            k > 80 -> (80 - k) / 20.0
            else -> 0.0
        }
        val cross = if (d != null) (if (k > d) 0.3 else if (k < d) -0.3 else 0.0) else 0.0
        val zone = when { k < 20 -> "oversold"; k > 80 -> "overbought"; else -> "neutral" }
        return (extreme + cross) to "Stochastic %K ${k.roundToInt()} ($zone)"
    }

    /** Volume confirmation: elevated volume in the direction of the day's move. */
    private fun volumeComp(ctx: SignalContext, i: Int): Pair<Double, String>? {
        if (i < 1) return null
        val v = ctx.volumes.getOrNull(i) ?: return null
        if (v <= 0.0) return null
        val start = (i - weights.volumeLookback).coerceAtLeast(0)
        var sum = 0.0
        var n = 0
        for (j in start until i) {
            val vj = ctx.volumes.getOrNull(j)
            if (vj != null && vj > 0.0) { sum += vj; n++ }
        }
        if (n == 0) return null
        val rel = v / (sum / n)
        val dir = if (ctx.prices[i] >= ctx.prices[i - 1]) 1.0 else -1.0
        val move = if (dir >= 0) "up" else "down"
        return ((rel - 1.0).coerceIn(0.0, 1.0) * dir) to String.format(Locale.US, "Volume %.1fx avg (%s day)", rel, move)
    }

    /** Relative strength vs the benchmark: is the price/benchmark ratio rising over [rsPeriod]? */
    private fun relStrengthComp(ctx: SignalContext, i: Int): Pair<Double, String>? {
        val bench = ctx.benchmark ?: return null
        val prev = i - weights.rsPeriod
        if (prev < 0) return null
        val b1 = bench.getOrNull(i) ?: return null
        val b0 = bench.getOrNull(prev) ?: return null
        if (b1 == 0.0 || b0 == 0.0) return null
        val ratioPrev = ctx.prices[prev] / b0
        if (ratioPrev == 0.0) return null
        val slope = (ctx.prices[i] / b1) / ratioPrev - 1.0
        val dir = if (slope >= 0) "Outperforming" else "Lagging"
        return (slope / 0.05) to "$dir benchmark"
    }

    private companion object {
        const val HIGH_VIX_DAMPEN = 0.6
    }
}
