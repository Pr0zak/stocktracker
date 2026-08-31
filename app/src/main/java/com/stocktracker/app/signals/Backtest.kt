package com.stocktracker.app.signals

import com.stocktracker.app.data.model.PairedStat
import com.stocktracker.app.data.model.PricePoint
import kotlin.math.max

/** Summary of a walk-forward backtest of [SignalEngine] on one series (long/flat, after simple costs). */
data class BacktestResult(
    val bars: Int,
    val strategyReturnPct: Double,
    val buyHoldReturnPct: Double,
    val maxDrawdownPct: Double,
    /**
     * Percent of simulated trades that closed green, or NULL when none were taken.
     *
     * Nullable — never 0.0 — because "the rule never traded this chart" and "the rule traded and lost
     * every time" are opposite statements about a signal, and 0.0 makes the harsher one. Callers must
     * pair it with [trades] wherever it is shown (SWT-9).
     */
    val winRatePct: Double?,
    /**
     * Simulated trades that closed green. Carried alongside the rate so a render site under the
     * shared small-sample floor can print "3 of 4" instead of a confident "75%" over four trades.
     */
    val wins: Int,
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
 * WHAT "AFTER COST" DOES AND DOES NOT INCLUDE. The fee is a flat 10 bps of the position on each side
 * of a trade and that is the whole cost model: no bid/ask spread, no slippage, no gap through the
 * intended price, no per-order commission. Fills are assumed at the close of the signal bar. The
 * methodology screen states this in the app's own words — keep the two in step.
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
        val bench = benchmark?.let { alignByDay(points, it) }
        val ctx = engine.prepare(points, bench)

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
            winRatePct = if (trades > 0) wins.toDouble() / trades * 100.0 else null,
            wins = wins,
            trades = trades,
            exposurePct = if (evaluated > 0) barsInMarket.toDouble() / evaluated * 100.0 else 0.0,
        )
    }
}

/**
 * The backtested win rate PAIRED with its forward twin (SWT-9).
 *
 * THE FORWARD SIDE IS ABSENT, AND THAT IS THE POINT. Nothing in this app has ever recorded a rule
 * engine signal as it fired and scored it afterwards — the backend's memory layer grades the ANALYST's
 * calls, which is a different subject and must not be borrowed to fill this slot. Presenting the two
 * as one record would be the mislabelling this pairing exists to prevent, so the honest output is a
 * simulated number standing next to a stated absence rather than standing alone.
 *
 * Returns null when the rule never traded this chart: with no simulated trades there is no rate to
 * qualify, and a pair of two absences is nothing to draw.
 */
fun BacktestResult.winRatePair(): PairedStat? {
    if (trades <= 0) return null
    return PairedStat(
        label = "Win rate",
        unit = PairedStat.StatUnit.RATE_PCT,
        backtest = PairedStat.Side.backtest(
            subject = "this rule, on this chart",
            // Built from the counts so a thin sample degrades to "3 of 4" rather than "75%".
            sample = PairedStat.StatSample.rate(hits = wins, n = trades),
        ),
        forward = PairedStat.Side.forward(
            subject = "this rule, live",
            sample = null,
            absentReason = "never recorded — this app has never logged a rule signal as it fired " +
                "and scored it afterwards, so there is nothing to check the simulation against",
        ),
    )
}
