package com.stocktracker.app.data.model

/**
 * How a closed call ACTUALLY ended, versus the exit plan it was opened with (SWT-7). Pure math, no
 * Android/UI, unit-tested in isolation exactly like [RealizedPnl] and [RiskMultiple].
 *
 * THE NUMBER THIS EXISTS TO PREVENT. A published track record can carry both of these for the same
 * set of trades:
 *
 *     profitable exit rate  65.3%      hard win rate  12.5%
 *
 * Same closes. The gap is the trades that reached neither their target nor their stop and were closed
 * on a decision — most of which happened to finish a little green. Both numbers are true. Publishing
 * only the first is not, and the first is the one everybody quotes. So the two rates are returned
 * TOGETHER, from one function, in one object ([Record]): there is deliberately no way for a caller to
 * obtain the flattering number without the qualifying one.
 *
 * WHAT MADE THIS COMPUTABLE. Until SWT-6 a [ClosedCallPosition] threw away the levels it was opened
 * with, so "did this reach its target" had no answer at all. It has one now, and only for positions
 * closed after that field existed — everything older lands in [ExitKind.UNPLANNED] and is named, not
 * guessed at and not quietly dropped from a denominator.
 *
 * DELIBERATELY NOT APPLIED TO THE BACKEND'S VERDICT SCORECARDS (`memory/stats`). Those score a fixed
 * 20-day horizon with no exit at all: every verdict ends the same way, by the clock. An exit taxonomy
 * there would be inventing a distinction the data does not contain.
 */
object ExitTaxonomy {

    /**
     * Below this many CLASSIFIED closes, a rate is noise. Deliberately the same floor as
     * [RiskMultiple.MIN_SCORED_FOR_EXPECTANCY] — a win rate over six trades is unreliable for exactly
     * the reason an expectancy over six trades is, and two floors that mean the same thing would drift
     * apart. [Record.smallSample] carries it; render sites are expected to state the count plainly
     * instead of printing a confident percentage over a handful of trades.
     */
    const val MIN_CLASSIFIED_FOR_RATES = RiskMultiple.MIN_SCORED_FOR_EXPECTANCY

    /**
     * Which planned level the exit corresponded to.
     *
     * [EXPIRY] IS NOT [STOP], and the two must never be merged. A stop is the plan working — you
     * defined the risk and it was taken. Letting a call expire at zero is the plan being ABANDONED,
     * and it costs more than the plan said: a 50% stop that expired worthless is −2R, not −1R (see
     * [RiskMultiple.rFor]). Folding expiry into stop would hide precisely the behaviour this taxonomy
     * exists to expose.
     *
     * [DISCRETIONARY] AND [UNPLANNED] ARE NOT THE SAME FACT either. One had levels and departed from
     * them — a decision. The other never had levels to depart from — an absence of information about
     * this app's own history. Collapsing them would read every old record as an act of discretion.
     */
    enum class ExitKind {
        /** Exit reached or passed the take-profit level the position was opened with. */
        TARGET,

        /** Exit at or below the stop level the position was opened with. */
        STOP,

        /** Expired worthless — the plan was abandoned. Its own outcome, never counted as a stop. */
        EXPIRY,

        /** Exercised: no option-leg exit price exists, so there is nothing to compare to the plan. */
        EXERCISED,

        /** Closed between the levels — a decision taken outside the plan. */
        DISCRETIONARY,

        /** Closed with no usable levels recorded: every position closed before SWT-6. */
        UNPLANNED,
    }

    /**
     * The take-profit level as a PRICE, from a percent of the entry premium.
     *
     * [CallPosition.takeProfitPct] is a percent of the premium paid ("close at +80%"), NOT a price —
     * the same trap [RiskMultiple.stopPriceFromPct] exists to keep out of the stop side. Comparing an
     * $80 "level" to a $2.00 option would classify every close as discretionary and look plausible.
     *
     * Worked: an 80% target on a $2.00 premium is a $3.60 exit level.
     *
     * Null when the percent is missing/non-finite/non-positive (a target at or below the entry is not
     * a target), or when the entry is not a positive finite price.
     */
    fun targetPriceFromPct(entry: Double, takeProfitPct: Double?): Double? {
        if (takeProfitPct == null || !takeProfitPct.isFinite()) return null
        if (!entry.isFinite() || entry <= 0.0) return null
        if (takeProfitPct <= 0.0) return null
        return entry * (1.0 + takeProfitPct / 100.0)
    }

    /**
     * Classify one closed position.
     *
     * Order of the checks, and why:
     *  1. EXERCISED first — there is no option-leg exit price at all, so no comparison to any level is
     *     possible. [RiskMultiple] already treats it as unscoreable for the same reason.
     *  2. EXPIRED next, AHEAD of the missing-levels check. "Expired worthless" is a complete answer to
     *     "how did this end" and needs no plan to be known, so an old record that expired is reported
     *     as an expiry rather than being swept into UNPLANNED — that would throw away a fact we have.
     *  3. Then the levels. With neither level usable — or a SOLD close with no exit price to compare
     *     against them — the plan cannot be checked at all: UNPLANNED.
     *  4. At or above the target → TARGET. At or below the stop → STOP. Boundaries are inclusive on
     *     both sides: an exit exactly AT the level is that level being reached, not a near miss.
     *     (The levels cannot overlap: a valid stop price is below the entry and a valid target above.)
     *  5. Anything left sat between the levels → DISCRETIONARY.
     *
     * A HALF-PLAN STILL COUNTS. A position opened with only a stop, sold above it, is DISCRETIONARY,
     * not UNPLANNED: it had a plan and the exit was not part of it.
     */
    fun classify(position: ClosedCallPosition): ExitKind {
        if (position.outcome == CallOutcome.EXERCISED) return ExitKind.EXERCISED

        val target = targetPriceFromPct(position.fillPrice, position.takeProfitPct)
        val stop = RiskMultiple.stopPriceFromPct(position.fillPrice, position.stopPct)

        // NO PLAN RECORDED MEANS UNCLASSIFIED, HOWEVER THE POSITION ENDED. This test has to come
        // before the expiry branch, and the asymmetry it replaces was actively misleading.
        //
        // Expiry used to be returned unconditionally, ahead of any check for levels. EXPIRY counts
        // inside the rate denominator and UNPLANNED does not, so across a history recorded before
        // the exit plan was carried through the close, every expiry was scored as a loss while every
        // sale — profitable or not — was discarded. Nine winning sales and one expiry reported 0%
        // finished green: a history that made money reading as one that never worked.
        //
        // Both rates are plan-relative and share a single denominator by construction, so the rule
        // must be symmetric. With no levels recorded there is nothing to assess the close against,
        // and "expired worthless" is an outcome rather than a verdict on a plan that was never
        // written down. ONE level is enough — a stop with no target still states the plan on the
        // side an expiry landed on.
        if (target == null && stop == null) return ExitKind.UNPLANNED

        if (position.outcome == CallOutcome.EXPIRED) return ExitKind.EXPIRY

        val exit = position.exitPricePerShare
        if (exit == null || !exit.isFinite()) return ExitKind.UNPLANNED
        if (target != null && exit >= target) return ExitKind.TARGET
        if (stop != null && exit <= stop) return ExitKind.STOP
        return ExitKind.DISCRETIONARY
    }

    /** Did the position finish green on the option leg? Expiry-at-zero never does. */
    private fun finishedGreen(position: ClosedCallPosition): Boolean {
        if (position.outcome == CallOutcome.EXPIRED) return false
        position.realizedPnl?.let { return it > 0.0 }
        val exit = position.exitPricePerShare ?: return false
        return exit.isFinite() && position.fillPrice.isFinite() && exit > position.fillPrice
    }

    /**
     * One bucket of the taxonomy.
     *
     * [avgR] is null — never 0.0 — when no member of the bucket could be scored in R (no stop recorded,
     * or exercised). "We could not measure these" and "these averaged out to nothing" are different
     * claims, and only one of them is true of the UNPLANNED bucket.
     */
    data class Bucket(
        val kind: ExitKind,
        /** How many closes landed here. A count, so 0 is honest. */
        val count: Int,
        /** Mean R over the members that could be scored; null when none could. */
        val avgR: Double?,
        /** Members that carried a scoreable R — the population [avgR] was computed over. */
        val scored: Int,
    )

    /**
     * The exit-taxonomy track record.
     *
     * The bucket counts PARTITION the closed set: they sum to [closedCount] exactly, always. Nothing
     * is dropped for being awkward to classify; the awkward ones have named buckets.
     *
     * [hardWinRatePct] and [profitableExitRatePct] share one denominator, [classified], and arrive
     * together. Both are null — not 0.0 — when nothing could be classified: "no trade reached its
     * target" and "we could not tell what any trade did" are opposite statements, and 0.0 would print
     * the harsher one over a history that never said it.
     */
    data class Record(
        /** Every closed position handed in. */
        val closedCount: Int,
        /** One entry per [ExitKind], in enum order, including empty ones. */
        val buckets: List<Bucket>,
        /** Closes measurable against a plan: TARGET + STOP + EXPIRY + DISCRETIONARY. The denominator. */
        val classified: Int,
        /** EXERCISED + UNPLANNED — counted, named, and excluded from the rates. */
        val unclassified: Int,
        /** Classified closes that reached the target — the numerator of [hardWinRatePct]. */
        val targetHits: Int,
        /**
         * Classified closes that finished green by any route — the numerator of [profitableExitRatePct].
         * Carried as a count so a render site under [MIN_CLASSIFIED_FOR_RATES] can say "3 of 5" without
         * reconstructing it from a percentage it was told not to print.
         */
        val greenExits: Int,
        /**
         * Percent (0–100) of [classified] closes that reached the planned TARGET. The strict number:
         * the plan worked as written. Null when [classified] is 0.
         */
        val hardWinRatePct: Double?,
        /**
         * Percent (0–100) of the SAME [classified] closes that finished green by any route, target or
         * not. The flattering number, and the one that must never be shown alone. Null when
         * [classified] is 0.
         */
        val profitableExitRatePct: Double?,
        /** [classified] < [MIN_CLASSIFIED_FOR_RATES] — state the counts, don't publish a percentage. */
        val smallSample: Boolean,
    ) {
        /** The bucket for [kind]; always present, possibly with count 0. */
        fun bucket(kind: ExitKind): Bucket = buckets.first { it.kind == kind }

        /** Buckets with something in them, in enum order — what a render site iterates. */
        val occupiedBuckets: List<Bucket> get() = buckets.filter { it.count > 0 }
    }

    /** Classify and roll up [positions]. */
    fun summarize(positions: List<ClosedCallPosition>): Record {
        // Classified once and carried, so the buckets and the rates can never disagree about which
        // kind a position is.
        val tagged = positions.map { classify(it) to it }
        val byKind = tagged.groupBy({ it.first }, { it.second })

        val buckets = ExitKind.entries.map { kind ->
            val members = byKind[kind].orEmpty()
            val rs = members.mapNotNull { RiskMultiple.rFor(it) }
            Bucket(
                kind = kind,
                count = members.size,
                // Averaged over the scoreable members only, and null when there are none. Treating an
                // unscoreable member as 0R would drag every bucket average toward a break-even it
                // never demonstrated.
                avgR = if (rs.isEmpty()) null else rs.sum() / rs.size,
                scored = rs.size,
            )
        }

        val classifiedKinds = setOf(ExitKind.TARGET, ExitKind.STOP, ExitKind.EXPIRY, ExitKind.DISCRETIONARY)
        val classifiedPositions = tagged.filter { it.first in classifiedKinds }
        val classified = classifiedPositions.size
        val targetHits = classifiedPositions.count { it.first == ExitKind.TARGET }
        val greenExits = classifiedPositions.count { finishedGreen(it.second) }

        return Record(
            closedCount = positions.size,
            buckets = buckets,
            classified = classified,
            unclassified = positions.size - classified,
            targetHits = targetHits,
            greenExits = greenExits,
            hardWinRatePct = if (classified == 0) null else targetHits.toDouble() / classified * 100.0,
            profitableExitRatePct = if (classified == 0) null else greenExits.toDouble() / classified * 100.0,
            smallSample = classified < MIN_CLASSIFIED_FOR_RATES,
        )
    }

    /** Short label for a bucket, for display. */
    fun label(kind: ExitKind): String = when (kind) {
        ExitKind.TARGET -> "Hit target"
        ExitKind.STOP -> "Stopped out"
        ExitKind.EXPIRY -> "Expired worthless"
        ExitKind.EXERCISED -> "Exercised"
        ExitKind.DISCRETIONARY -> "Closed off-plan"
        ExitKind.UNPLANNED -> "No plan recorded"
    }
}
