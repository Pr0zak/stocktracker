package com.stocktracker.app.data.model

/**
 * R-multiples — the unit of account for a DECISION rather than a position (SWT-6). Pure math, kept
 * free of Android/UI so it unit-tests in isolation (see RiskMultipleTest), exactly like [RealizedPnl].
 *
 * R = (exit − entry) / (entry − stop). The denominator is the risk you DEFINED AT ENTRY, so a trade
 * that returned 10% on a 2%-of-book position and one that returned 10% on a 20% position are the same
 * call at different sizes — and only R says so. It is also the only unit in which "expectancy" and
 * "profit factor" compare across trades of different sizes.
 *
 * R CANNOT BE RECONSTRUCTED AFTER THE FACT. It needs the stop that was in force when the position was
 * opened, and nothing recoverable — price history, the P/L, the notes — tells you what that was. A
 * position closed without its stop recorded is permanently unscoreable in R; see [ClosedCallPosition]
 * for the history that is stuck that way.
 *
 * LONG-ONLY. Everything here assumes a long position: the stop sits BELOW the entry and a higher exit
 * is a gain. Shorts would need the sign convention flipped and are not supported — a short passed in
 * here has stop ≥ entry and comes back null rather than silently sign-flipped.
 */
object RiskMultiple {

    /**
     * Below this many SCORED closes the aggregates are noise, not an edge. The literature this came
     * from puts the floor at 20–30 closed trades; 20 is the low end of that band, chosen so the flag
     * fires as rarely as is defensible rather than never. [Aggregate.smallSample] carries it — the
     * numbers are still computed and still shown, but never as a confident expectancy over six trades.
     */
    const val MIN_SCORED_FOR_EXPECTANCY = 20

    /**
     * R for one trade, or null when it cannot be defined.
     *
     * Null — never 0.0 — when:
     *  - [stop] is null: no risk was recorded, so there is no denominator. 0R is a REAL and different
     *    claim (exited exactly at entry, a scratch), so returning it here would invent a result.
     *  - stop ≥ entry: no risk is defined. Dividing by it gives ±Infinity at equality, and a
     *    negative denominator above it flips the sign so losses read as gains.
     *  - any input is non-finite (NaN/Infinity), which would poison every aggregate downstream.
     *
     * Worked: entry $2.00, stop $1.00 → risk $1.00/share.
     *   exit $1.00 → (1.00 − 2.00) / 1.00 = −1.0R  (stopped out at exactly the risk = −1R)
     *   exit $3.00 → (3.00 − 2.00) / 1.00 = +1.0R
     *   exit $4.00 → (4.00 − 2.00) / 1.00 = +2.0R  (made twice what it risked)
     */
    fun rMultiple(entry: Double, exit: Double, stop: Double?): Double? {
        if (stop == null) return null
        if (!entry.isFinite() || !exit.isFinite() || !stop.isFinite()) return null
        val risk = entry - stop
        if (risk <= 0.0) return null
        return (exit - entry) / risk
    }

    /**
     * Turn a stop expressed as a PERCENT OF THE ENTRY into a stop PRICE.
     *
     * The options tracker stores [CallPosition.stopPct] as a percent of the premium paid ("bail at
     * −50%"), NOT as a price. Feeding that 50.0 straight into [rMultiple] as a stop would compute the
     * risk against a $50 stop on a $2 option — a percent mistaken for a price produces numbers that
     * look plausible and are nonsense, which is why this conversion is its own named, tested step.
     *
     * Worked: a 50% stop on a $2.00 premium is a $1.00 stop price (risk $1.00/share), so a $3.00 exit
     * is +1R.
     *
     * Null when the percent is missing/non-finite, when the entry is not a positive finite price, or
     * when the percent exceeds 100 — you cannot risk more than the premium on a bought call, so a
     * stop below $0 is not a stop, and clamping it to 100 would report a confident R from a nonsense
     * input. Exactly 100.0 is legal and means "risk the whole premium" (stop price $0).
     */
    fun stopPriceFromPct(entry: Double, stopPct: Double?): Double? {
        if (stopPct == null || !stopPct.isFinite()) return null
        if (!entry.isFinite() || entry <= 0.0) return null
        if (stopPct <= 0.0 || stopPct > 100.0) return null
        return entry * (1.0 - stopPct / 100.0)
    }

    /** [rMultiple] for an entry whose stop is a percent of that entry — see [stopPriceFromPct]. */
    fun rMultipleFromStopPct(entry: Double, exit: Double, stopPct: Double?): Double? =
        rMultiple(entry, exit, stopPriceFromPct(entry, stopPct))

    /**
     * R for a closed call, or null when the position cannot be scored.
     *
     * Entry is the premium paid per share; the exit is the premium sold per share. Unscoreable when:
     *  - the record carries no [ClosedCallPosition.stopPct] — every position closed before SWT-6 is in
     *    this bucket, permanently;
     *  - the outcome is EXERCISED — the option's value rolled into the shares, so there is no option-leg
     *    exit price to measure (the same exclusion [RealizedPnl.summarize] makes);
     *  - a SOLD close somehow has no exit price recorded.
     *
     * EXPIRED-worthless IS scored, at an exit of $0: the premium went to zero. That usually blows
     * through the stop — a 50% stop that expired worthless is −2R, not −1R — and that is the honest
     * number. R measures what the trade actually did, not what the plan said would happen.
     */
    fun rFor(position: ClosedCallPosition): Double? {
        val exit = when (position.outcome) {
            CallOutcome.SOLD -> position.exitPricePerShare ?: return null
            CallOutcome.EXPIRED -> 0.0
            CallOutcome.EXERCISED -> return null
        }
        return rMultipleFromStopPct(position.fillPrice, exit, position.stopPct)
    }

    /**
     * R-based track record over a set of closed positions.
     *
     * [scored] + [unscoreable] = [closedCount] always. The split is the point: an expectancy over 4 of
     * 30 closed positions is not this account's expectancy, and the number has to arrive with the
     * evidence that says so. Nothing here silently drops the positions it could not measure.
     *
     * The measurements are nullable rather than zero-filled: with nothing scored there is no total, no
     * average and no drawdown, and printing 0.0 for any of them would claim a flat, break-even record.
     */
    data class Aggregate(
        /** Every closed position handed in, scoreable or not. */
        val closedCount: Int,
        /** Positions an R could be computed for. */
        val scored: Int,
        /** Positions with no R — no stop recorded, or exercised. Reported, never dropped. */
        val unscoreable: Int,
        /** Sum of R over [scored] positions; null when nothing is scored. */
        val totalR: Double?,
        /** Expectancy: mean R per scored trade. Null when nothing is scored. */
        val avgR: Double?,
        /**
         * Gross R won / gross R lost, both as magnitudes. Null when nothing is scored OR when there
         * are no losing trades at all — an undefined ratio, not an infinitely good one.
         */
        val profitFactor: Double?,
        /** Best single trade in R; null when nothing is scored. */
        val largestWinR: Double?,
        /** Worst single trade in R (negative); null when nothing is scored. */
        val largestLossR: Double?,
        /** Longest run of consecutive losing (R < 0) trades in close order. A count, so 0 is honest. */
        val longestLosingStreak: Int,
        /**
         * Deepest peak-to-trough fall of the cumulative-R equity curve, in close order, as a POSITIVE
         * magnitude. 0.0 means the curve never gave anything back; null means nothing was scored.
         */
        val maxDrawdownR: Double?,
        /** [scored] < [MIN_SCORED_FOR_EXPECTANCY] — treat the numbers above as noise. */
        val smallSample: Boolean,
    )

    /**
     * Aggregate [positions] in R.
     *
     * The equity curve is walked in the order trades CLOSED (by [ClosedCallPosition.closeDateIso],
     * an ISO yyyy-MM-dd string, so lexicographic order is chronological), not in store order, because
     * a drawdown is a statement about a sequence in time. The sort is stable, so same-day closes keep
     * the order they were recorded in.
     */
    fun aggregate(positions: List<ClosedCallPosition>): Aggregate {
        val inCloseOrder = positions.sortedBy { it.closeDateIso }
        val rs = inCloseOrder.mapNotNull { rFor(it) }
        val scored = rs.size
        val unscoreable = positions.size - scored

        if (scored == 0) {
            return Aggregate(
                closedCount = positions.size,
                scored = 0,
                unscoreable = unscoreable,
                totalR = null,
                avgR = null,
                profitFactor = null,
                largestWinR = null,
                largestLossR = null,
                longestLosingStreak = 0,
                maxDrawdownR = null,
                smallSample = true,
            )
        }

        val grossWin = rs.filter { it > 0.0 }.sum()
        val grossLoss = -rs.filter { it < 0.0 }.sum() // magnitude
        val total = rs.sum()

        var streak = 0
        var longestStreak = 0
        var cumulative = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        for (r in rs) {
            if (r < 0.0) {
                streak++
                if (streak > longestStreak) longestStreak = streak
            } else {
                streak = 0
            }
            cumulative += r
            if (cumulative > peak) peak = cumulative
            val drawdown = peak - cumulative
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
        }

        return Aggregate(
            closedCount = positions.size,
            scored = scored,
            unscoreable = unscoreable,
            totalR = total,
            avgR = total / scored,
            profitFactor = if (grossLoss > 0.0) grossWin / grossLoss else null,
            largestWinR = rs.maxOrNull(),
            largestLossR = rs.minOrNull(),
            longestLosingStreak = longestStreak,
            maxDrawdownR = maxDrawdown,
            smallSample = scored < MIN_SCORED_FOR_EXPECTANCY,
        )
    }

    /** "+1.5R" / "−2.0R" for display. Callers must skip the row entirely when R is null. */
    fun format(r: Double): String {
        val sign = if (r >= 0.0) "+" else "−"
        return "$sign${"%.1f".format(kotlin.math.abs(r))}R"
    }
}
