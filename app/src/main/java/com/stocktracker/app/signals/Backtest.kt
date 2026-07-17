package com.stocktracker.app.signals

import com.stocktracker.app.data.model.PricePoint
import kotlin.math.max

/** Summary of a walk-forward backtest of [SignalEngine] on one series (long/flat, after simple costs). */
data class BacktestResult(
    val bars: Int,
    val strategyReturnPct: Double,
    val buyHoldReturnPct: Double,
    val maxDrawdownPct: Double,
    val winRatePct: Double,
    val trades: Int,
    val exposurePct: Double,
) {
    /** Strategy return minus buy-and-hold — the number that decides whether the signal is worth it. */
    val edgeVsBuyHoldPct: Double get() = strategyReturnPct - buyHoldReturnPct
}

/**
 * Walk the series bar by bar, turning each bar's signal into a long/flat position and realizing the
 * NEXT bar's return — strictly causal, so there is no lookahead. A [feeBps] cost is charged on every
 * position change, so results are "after cost". Long-only, single position, fully invested or flat.
 *
 * This is the honesty check for Tier 1: a signal that doesn't beat buy-and-hold here (see
 * [BacktestResult.edgeVsBuyHoldPct]) isn't worth acting on.
 */
object Backtest {
    fun run(
        points: List<PricePoint>,
        engine: SignalEngine = SignalEngine(),
        feeBps: Double = 10.0,
        benchmark: List<PricePoint>? = null,
    ): BacktestResult? {
        val w = engine.weights
        if (points.size < w.maSlow + w.rsPeriod + 5) return null

        val prices = points.map { it.price }
        val volumes = points.map { it.volume }
        val bench = benchmark?.let { alignByDay(points, it) }
        val ctx = engine.prepare(prices, volumes, bench)

        val warmup = max(w.maSlow, w.rsiPeriod + 1)
        if (prices[warmup] <= 0.0) return null // no valid baseline for buy-and-hold
        val fee = feeBps / 10_000.0
        var equity = 1.0
        var peak = 1.0
        var maxDd = 0.0
        var position = 0 // 0 flat, 1 long
        var trades = 0
        var wins = 0
        var barsInMarket = 0
        var entryEquity = 1.0

        for (i in warmup until points.lastIndex) {
            val sig = engine.evaluateAt(ctx, i)
            // Hysteresis: go long above the buy threshold, flat below the sell threshold, else hold.
            val desired = when {
                sig.score >= w.buyThreshold -> 1
                sig.score <= w.sellThreshold -> 0
                else -> position
            }
            if (desired != position) {
                if (desired == 1) {
                    entryEquity = equity      // baseline BEFORE the entry fee, so the win test nets both fees
                    equity *= (1.0 - fee)     // entry cost
                    trades++
                } else {
                    equity *= (1.0 - fee)     // exit cost
                    if (equity > entryEquity) wins++ // closed a long in profit, net of both fees
                }
                position = desired
            }
            if (position == 1 && prices[i] > 0.0) {
                equity *= (1.0 + (prices[i + 1] / prices[i] - 1.0)) // realize next bar's return
                barsInMarket++
            }
            peak = max(peak, equity)
            maxDd = max(maxDd, (peak - equity) / peak)
        }
        if (position == 1 && equity > entryEquity) wins++ // count an open winning long at the end

        val buyHold = prices[points.lastIndex] / prices[warmup] - 1.0
        val evaluated = points.lastIndex - warmup
        return BacktestResult(
            bars = evaluated,
            strategyReturnPct = (equity - 1.0) * 100.0,
            buyHoldReturnPct = buyHold * 100.0,
            maxDrawdownPct = maxDd * 100.0,
            winRatePct = if (trades > 0) wins.toDouble() / trades * 100.0 else 0.0,
            trades = trades,
            exposurePct = if (evaluated > 0) barsInMarket.toDouble() / evaluated * 100.0 else 0.0,
        )
    }
}
