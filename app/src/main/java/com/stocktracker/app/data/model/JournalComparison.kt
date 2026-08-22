package com.stocktracker.app.data.model

/**
 * THE TWO CURVES, drawn over a population BOTH of them could take (SWT-8). Pure math, no Android/UI,
 * unit-tested in isolation exactly like [VerdictJournal], [RiskMultiple] and [ExitTaxonomy].
 *
 * [VerdictJournal.curve] builds YOUR cumulative R and deliberately leaves the mechanical series null,
 * because nothing on the device may invent one. This object is what fills that slot once the backend's
 * replay engine has actually answered ([VerdictJournalEntry.replay]), and its whole job is the one
 * rule that makes the comparison mean anything:
 *
 * AN ENTRY IS ON BOTH CURVES OR ON NEITHER.
 *
 * The tempting version — plot every trade you closed, and plot the mechanical points that happen to
 * exist — is silently, systematically flattering. The trades whose replays are missing are not a
 * random sample: a plan that never filled is one where price ran away from the zone, and those are
 * disproportionately the ones you chased and got in on anyway. Letting your curve carry a trade the
 * plan never took, against a mechanical curve that skips it, compares your best decisions to the
 * plan's full record. Every drop-out is therefore counted BY REASON and stated on screen — see
 * [populationSentence] — because a shared population that shrinks silently is the same lie told
 * quietly.
 *
 * THE ORDER IS YOUR CLOSE ORDER, for both series. The two are drawn against each other index for
 * index, so they need ONE x-axis, and the sequence you actually lived is the one the chart is about.
 * The consequence is stated rather than hidden: the mechanical curve's SHAPE is that of the same
 * trades in the order you closed them, not the order the plan would have. Its endpoint — the number
 * the comparison turns on — is a sum and does not care about order at all.
 */
object JournalComparison {

    /**
     * Why an entry you TOOK is on neither curve.
     *
     * These partition the excluded set: exactly one applies to each, tested in the order declared, so
     * the counts sum to (taken − paired) exactly. An entry can easily qualify for several — a position
     * you still hold, on a plan nobody has replayed — and it is counted under the first, which is why
     * the order is deliberate rather than incidental: YOUR side is tested first, because "you have not
     * finished this trade" is a fact about the journal and "nobody replayed it" is a fact about a
     * button you have not pressed, and the first is the more useful thing to be told.
     */
    enum class Exclusion {
        /** Marked taken, but no fill price recorded — nothing on your side can be scored. */
        NO_FILL,

        /** You are still in it. Has no result yet, and a 0R point would claim it finished flat. */
        STILL_OPEN,

        /** Closed with a price but no date, so it cannot be placed in a sequence. */
        NO_EXIT_DATE,

        /** The plan snapshot carried no usable stop, so YOUR R has no denominator. */
        NO_STOP_IN_PLAN,

        /** Nobody has asked the backend about this plan yet. */
        NOT_REPLAYED,

        /** The backend refused the plan — an inverted zone, a stop that is not below the entry. */
        REPLAY_REFUSED,

        /** Replayed, and nothing has been decided: no session has traded since the plan was written. */
        REPLAY_PENDING,

        /** The plan filled and is still running in the replay — no exit, so no R. */
        REPLAY_STILL_OPEN,

        /** Price never came into the entry zone. The plan was never tradeable — NOT a losing trade. */
        NEVER_FILLED,

        /** The replay resolved but named no stop, so the mechanical R has no denominator either. */
        REPLAY_UNSCOREABLE,
    }

    /** One reason, and how many entries fell out for it. A count, so 0 is honest. */
    data class ExcludedFor(val reason: Exclusion, val count: Int)

    /**
     * The paired result: two curves over one population, and everything that had to be dropped to get
     * there.
     *
     * [yours] and [mechanical] are aggregated over the SAME entries in the SAME order, so their
     * expectancies are comparable by construction — which is the only reason it is legitimate to print
     * them beside each other. [VerdictJournal.ActualRecord.risk], by contrast, is your record over
     * every close you have, including the ones the plan could not be measured on; both are worth
     * showing and they answer different questions.
     */
    data class Paired(
        /**
         * The two series, in the shared [VerdictJournal.ActualVsPlanCurve] shape so
         * [VerdictJournal.ActualVsPlanCurve.executionGapR] is the one implementation of the gap.
         * [VerdictJournal.ActualVsPlanCurve.mechanical] is an EMPTY LIST rather than null once this
         * has run: a replay pass happened and produced no shared population, which is a different
         * statement from "nobody has replayed anything".
         */
        val curve: VerdictJournal.ActualVsPlanCurve,
        /** Entries examined: the ones you marked TAKEN. Declined and undecided are not candidates. */
        val takenConsidered: Int,
        /** One row per reason that actually excluded something, in enum order. */
        val excluded: List<ExcludedFor>,
        /**
         * How many of the PAIRED entries rest on the backend's intrabar assumption — one daily bar
         * touched both the stop and the target, and it resolved against the trade.
         *
         * Surfaced because it is the one number that says how much of the mechanical curve is a
         * reading of the tape and how much is a convention. A backtest that resolves its own
         * ambiguities silently is the standard way one flatters itself.
         */
        val ambiguousCount: Int,
        /** YOUR R record over the shared population only. */
        val yours: RiskMultiple.Aggregate,
        /** The PLAN's R record over the same entries, in the same order. */
        val mechanical: RiskMultiple.Aggregate,
    ) {
        /** How many entries made it onto both curves. */
        val pairedCount: Int get() = curve.actual.size

        /** Entries dropped for any reason. Always [takenConsidered] − [pairedCount]. */
        val excludedCount: Int get() = excluded.sumOf { it.count }

        /** Nothing to draw. The screen must say WHY rather than render empty axes. */
        val isEmpty: Boolean get() = pairedCount == 0

        /** Your final cumulative R minus the plan's, over the shared population. Null unless both exist. */
        val executionGapR: Double? get() = curve.executionGapR
    }

    /** Short label for an exclusion, phrased so a list of them reads as a sentence fragment. */
    fun label(reason: Exclusion): String = when (reason) {
        Exclusion.NO_FILL -> "no fill recorded"
        Exclusion.STILL_OPEN -> "still open"
        Exclusion.NO_EXIT_DATE -> "no exit date"
        Exclusion.NO_STOP_IN_PLAN -> "no stop in the plan"
        Exclusion.NOT_REPLAYED -> "not replayed yet"
        Exclusion.REPLAY_REFUSED -> "the plan can't be replayed"
        Exclusion.REPLAY_PENDING -> "nothing has traded since"
        Exclusion.REPLAY_STILL_OPEN -> "the plan is still running"
        Exclusion.NEVER_FILLED -> "never filled"
        Exclusion.REPLAY_UNSCOREABLE -> "the replay had no stop to score against"
    }

    /** Which [Exclusion] keeps [entry] off both curves, or null when it belongs on both. */
    fun exclusionFor(entry: VerdictJournalEntry): Exclusion? {
        // --- your side ---
        if (entry.status == JournalStatus.TAKEN_UNFILLED) return Exclusion.NO_FILL
        if (entry.isOpen) return Exclusion.STILL_OPEN
        if (!entry.isClosed) return Exclusion.NO_FILL // taken with neither fill nor exit; nothing to score
        if (entry.exitDateIso.isNullOrBlank()) return Exclusion.NO_EXIT_DATE
        if (entry.rMultiple == null) return Exclusion.NO_STOP_IN_PLAN

        // --- the plan's side ---
        val replay = entry.replay ?: return Exclusion.NOT_REPLAYED
        if (replay.refused) return Exclusion.REPLAY_REFUSED
        return when {
            replay.outcome == null -> Exclusion.REPLAY_PENDING
            replay.outcome == JournalReplay.OPEN -> Exclusion.REPLAY_STILL_OPEN
            replay.outcome == JournalReplay.NEVER_FILLED -> Exclusion.NEVER_FILLED
            // Covers both a resolved trade with no stop AND an outcome string this build does not
            // recognise: either way there is no R we are willing to plot.
            replay.scoredR == null -> Exclusion.REPLAY_UNSCOREABLE
            else -> null
        }
    }

    /**
     * Pair [entries] into two curves over the population both sides could take.
     *
     * Declined and undecided entries are not candidates and are not "excluded" — they were never on
     * the way to a curve. They are counted in [VerdictJournal.record], which is where the headline
     * "how many verdicts did you pass on" comes from, and that is the whole reason the journal records
     * a pass at all.
     */
    fun pair(entries: List<VerdictJournalEntry>): Paired {
        val taken = entries.filter { it.isTaken }

        val counts = linkedMapOf<Exclusion, Int>()
        val included = mutableListOf<VerdictJournalEntry>()
        for (entry in taken) {
            val why = exclusionFor(entry)
            if (why == null) included += entry else counts[why] = (counts[why] ?: 0) + 1
        }

        // ONE order for both series — see the class doc. Stable, so same-day closes keep the order
        // they were logged in and the two series stay aligned index for index.
        val ordered = included.sortedBy { it.exitDateIso.orEmpty() }

        var mineCum = 0.0
        var planCum = 0.0
        val minePoints = mutableListOf<VerdictJournal.CurvePoint>()
        val planPoints = mutableListOf<VerdictJournal.CurvePoint>()
        for (entry in ordered) {
            // Both non-null by construction: exclusionFor rejected every entry either side could not
            // score. Anything that got here has two numbers, which is what "shared population" means.
            val mine = entry.rMultiple ?: continue
            val plan = entry.mechanicalR ?: continue
            mineCum += mine
            planCum += plan
            val date = entry.exitDateIso.orEmpty()
            minePoints += VerdictJournal.CurvePoint(entry.symbol, date, mine, mineCum)
            planPoints += VerdictJournal.CurvePoint(entry.symbol, date, plan, planCum)
        }

        return Paired(
            curve = VerdictJournal.ActualVsPlanCurve(
                actual = minePoints,
                mechanical = planPoints,
                unscoreableCloses = counts[Exclusion.NO_STOP_IN_PLAN] ?: 0,
                undatedCloses = counts[Exclusion.NO_EXIT_DATE] ?: 0,
            ),
            takenConsidered = taken.size,
            excluded = Exclusion.entries.mapNotNull { r -> counts[r]?.let { ExcludedFor(r, it) } },
            ambiguousCount = ordered.count { it.replay?.ambiguous == true },
            // Never filtered before aggregation — but nothing here IS null, so scored == closedCount
            // on both sides by construction. That equality is the invariant: the two aggregates are
            // over the same trades, so their expectancies are comparable.
            yours = RiskMultiple.aggregateR(minePoints.map { it.r }),
            mechanical = RiskMultiple.aggregateR(planPoints.map { it.r }),
        )
    }

    /**
     * "6 entries on both curves; 3 excluded — 2 still open, 1 never filled."
     *
     * The sentence that has to sit under the chart. A comparison whose population is not stated is not
     * a comparison, it is a picture; and the reasons are named individually because "3 excluded" alone
     * invites the reader to assume they were dropped at random, which is exactly what they are not.
     */
    fun populationSentence(paired: Paired): String {
        val n = paired.pairedCount
        val head = when (n) {
            0 -> "No entries on both curves"
            1 -> "1 entry on both curves"
            else -> "$n entries on both curves"
        }
        if (paired.excluded.isEmpty()) return "$head."
        val why = paired.excluded.joinToString(", ") { "${it.count} ${label(it.reason)}" }
        return "$head; ${paired.excludedCount} excluded — $why."
    }
}
