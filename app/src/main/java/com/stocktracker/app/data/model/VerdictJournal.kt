package com.stocktracker.app.data.model

/**
 * YOUR track record over logged verdicts (SWT-8). Pure math, no Android/UI, unit-tested in isolation
 * exactly like [RealizedPnl], [RiskMultiple] and [ExitTaxonomy].
 *
 * Nothing here computes an expectancy, a profit factor, a drawdown or an exit bucket of its own: the
 * R aggregation goes through [RiskMultiple.aggregateR] and the bucketing through
 * [ExitTaxonomy.classifyAgainstLevels], so this record and the options track record are the same
 * numbers measured the same way. They are meant to be read side by side, which they cannot be if two
 * copies of "expectancy" drift apart.
 *
 * WHAT IS DELIBERATELY NOT HERE. No mechanical/replayed series. Producing one would mean re-running
 * every plan against price history, which lives in the signals backend; guessing at it from the entry
 * zone and the target would put a fabricated curve next to a real one on the same chart, with nothing
 * on screen to say which was which. [ActualVsPlanCurve.mechanical] is left null until something
 * actually replays the plans.
 */
object VerdictJournal {

    /**
     * Your own track record.
     *
     * THE HEADLINE NUMBER IS A RATIO OF TWO COUNTS, NOT A RETURN. "Twelve verdicts, you took four" is
     * the most interesting thing this feature produces and it is invisible unless the entries you did
     * NOT take are counted rather than merely absent — which is why [notTakenCount] and
     * [undecidedCount] are first-class and why [entryCount] counts everything logged.
     *
     * The measurements sit inside [risk], nullable there by construction: over zero taken entries the
     * expectancy, total, profit factor and drawdown are all null, never 0.0. "You have no record yet"
     * and "your record breaks even" are opposite claims about the same account.
     */
    data class ActualRecord(
        /** Every entry handed in, whatever its state. The denominator nobody remembers to keep. */
        val entryCount: Int,
        /** Entries you acted on ([TakenState.TAKEN]), filled or not. */
        val takenCount: Int,
        /** Entries you deliberately passed on. Counted, never dropped — this is half the point. */
        val notTakenCount: Int,
        /** Entries with no decision recorded yet. NOT passes — see [TakenState]. */
        val undecidedCount: Int,
        /**
         * Percent of DECIDED entries you took: taken / (taken + notTaken) × 100.
         *
         * Undecided entries are excluded from both halves rather than counted as passes; folding them
         * in would make your take rate fall every time a verdict arrived and rise when you got round
         * to triaging it. Null — not 0.0 — when nothing has been decided at all.
         */
        val takeRatePct: Double?,
        /** Taken, but no fill price recorded, so nothing about them can be scored. */
        val unfilledCount: Int,
        /** Taken and filled, still held. Carry no result yet and are scored as nothing. */
        val openCount: Int,
        /**
         * The R track record over TAKEN, CLOSED entries only.
         *
         * [RiskMultiple.Aggregate.closedCount] here is how many of your entries actually finished;
         * `scored` / `unscoreable` split those by whether the snapshotted plan carried a stop, since
         * an entry logged without one is permanently unscoreable in R rather than 0R.
         */
        val risk: RiskMultiple.Aggregate,
        /** One bucket per [ExitTaxonomy.ExitKind], in enum order, over the closed entries. */
        val buckets: List<ExitTaxonomy.Bucket>,
    ) {
        /** Your expectancy in R per closed trade. Null until something has been scored. */
        val expectancyR: Double? get() = risk.avgR

        /** Sum of R over your scored closes. Null until something has been scored. */
        val totalR: Double? get() = risk.totalR

        /** Gross R won / gross R lost. Null when nothing is scored, or when you have never lost. */
        val profitFactor: Double? get() = risk.profitFactor

        /** Taken entries that have finished. */
        val closedCount: Int get() = risk.closedCount

        /** Closes an R could be computed for. */
        val scored: Int get() = risk.scored

        /** Closes with no R — the plan snapshot carried no stop. Reported, never dropped. */
        val unscoreable: Int get() = risk.unscoreable

        /** Too few scored closes for the numbers above to mean anything. */
        val smallSample: Boolean get() = risk.smallSample

        /** The bucket for [kind]; always present, possibly with count 0. */
        fun bucket(kind: ExitTaxonomy.ExitKind): ExitTaxonomy.Bucket = buckets.first { it.kind == kind }

        /** Buckets with something in them, in enum order — what a render site iterates. */
        val occupiedBuckets: List<ExitTaxonomy.Bucket> get() = buckets.filter { it.count > 0 }
    }

    /**
     * Build the record over [entries].
     *
     * [ActualRecord.takenCount] + [ActualRecord.notTakenCount] + [ActualRecord.undecidedCount] equals
     * [ActualRecord.entryCount] exactly, always, and unfilled + open + closed equals takenCount for
     * the same reason: every entry is somewhere, and nothing is quietly excluded for being awkward.
     */
    fun record(entries: List<VerdictJournalEntry>): ActualRecord {
        val taken = entries.filter { it.isTaken }
        val notTaken = entries.count { it.taken == TakenState.NOT_TAKEN }
        val undecided = entries.count { it.taken == TakenState.UNDECIDED }
        val decided = taken.size + notTaken

        // Closed entries in the order they CLOSED, not the order they were logged or stored — a
        // drawdown and a losing streak are statements about a sequence in time. Same rule, and the
        // same stable lexicographic-ISO sort, as RiskMultiple.aggregate uses on the options history.
        //
        // A close with a price but no DATE sorts to the front on the empty-string key. It is kept:
        // dropping it would remove a real result from your expectancy, which is order-independent and
        // would simply be wrong without it. The two order-dependent numbers — drawdown and losing
        // streak — are the ones its unknown position can move, and [curve], whose entire subject IS
        // the sequence, excludes it and reports the count instead of guessing.
        val closed = taken.filter { it.isClosed }.sortedBy { it.exitDateIso ?: "" }

        // Bucketed once and carried, so the breakdown and the per-bucket averages can never disagree
        // about which kind an entry is.
        val tagged = closed.map { it.exitKind to it }
        val buckets = ExitTaxonomy.ExitKind.entries.map { kind ->
            val members = tagged.filter { it.first == kind }.map { it.second }
            val rs = members.mapNotNull { it.rMultiple }
            ExitTaxonomy.Bucket(
                kind = kind,
                count = members.size,
                // Null, not 0.0, when no member could be scored — the difference between "these
                // averaged out to nothing" and "we could not measure any of these".
                avgR = if (rs.isEmpty()) null else rs.sum() / rs.size,
                scored = rs.size,
            )
        }

        return ActualRecord(
            entryCount = entries.size,
            takenCount = taken.size,
            notTakenCount = notTaken,
            undecidedCount = undecided,
            takeRatePct = if (decided == 0) null else taken.size.toDouble() / decided * 100.0,
            unfilledCount = taken.count { it.status == JournalStatus.TAKEN_UNFILLED },
            openCount = taken.count { it.isOpen },
            // The nulls go in deliberately: they are what `unscoreable` is counted from.
            risk = RiskMultiple.aggregateR(closed.map { it.rMultiple }),
            buckets = buckets,
        )
    }

    /** One step of a cumulative-R curve: the trade that closed, and where the curve stood after it. */
    data class CurvePoint(
        val symbol: String,
        /** ISO yyyy-MM-dd the trade closed. The x-axis. */
        val closeDateIso: String,
        /** This trade's R. */
        val r: Double,
        /** Running sum of R through and including this trade. */
        val cumulativeR: Double,
    )

    /**
     * The two curves a chart draws against each other.
     *
     * [mechanical] IS NULL UNTIL SOMETHING REPLAYS THE PLANS, and null is a different statement from
     * an empty list: null means nobody has replayed anything, empty means a replay ran and produced
     * no closed trades. A render site must draw nothing at all for null — not a flat line at zero,
     * which would read as "the plan would have made you nothing" over plans that were never tested.
     */
    data class ActualVsPlanCurve(
        /** What YOU got, in close order. */
        val actual: List<CurvePoint>,
        /** What the MECHANICAL PLAN would have got. Supplied by the backend's replay engine. */
        val mechanical: List<CurvePoint>? = null,
        /**
         * Closed entries left OUT of [actual] because they carried no R — the plan snapshot had no
         * stop, so there is no denominator. Surfaced so a chart can say "3 closes aren't plotted"
         * rather than silently drawing a shorter curve than your history.
         */
        val unscoreableCloses: Int = 0,
        /**
         * Closed entries left out because they have an exit price but no exit DATE, so they cannot be
         * placed in a sequence. Sorting them to the front would invent an order and change the shape
         * of the drawdown; dropping them silently would hide that the curve is incomplete.
         */
        val undatedCloses: Int = 0,
    ) {
        /** Where the actual curve finished. Null on an empty curve — never 0.0R. */
        val finalActualR: Double? get() = actual.lastOrNull()?.cumulativeR

        /** Where the mechanical curve finished, if one was ever supplied. */
        val finalMechanicalR: Double? get() = mechanical?.lastOrNull()?.cumulativeR

        /**
         * Your final R minus the plan's — the execution gap, positive when you beat the mechanical
         * plan. Null unless BOTH curves exist, because a gap against a curve nobody drew is not a
         * number.
         */
        val executionGapR: Double? get() {
            val mine = finalActualR ?: return null
            val plan = finalMechanicalR ?: return null
            return mine - plan
        }
    }

    /**
     * Cumulative R over your taken, closed, scoreable entries, in CLOSE order.
     *
     * Store order is insertion order and entry order is when the verdict arrived; neither is when the
     * trade finished, and only the last one produces a curve whose peaks and troughs happened in that
     * sequence. Ties keep the order they were recorded in (the sort is stable).
     */
    fun curve(entries: List<VerdictJournalEntry>): ActualVsPlanCurve {
        val closed = entries.filter { it.isClosed }
        val dated = closed.filter { !it.exitDateIso.isNullOrBlank() }.sortedBy { it.exitDateIso }

        var cumulative = 0.0
        val points = mutableListOf<CurvePoint>()
        var unscoreable = 0
        for (entry in dated) {
            val r = entry.rMultiple
            if (r == null) {
                unscoreable++ // no stop in the snapshot — plotting it as 0R would invent a scratch
                continue
            }
            cumulative += r
            points += CurvePoint(
                symbol = entry.symbol,
                closeDateIso = entry.exitDateIso.orEmpty(),
                r = r,
                cumulativeR = cumulative,
            )
        }

        return ActualVsPlanCurve(
            actual = points,
            mechanical = null, // never fabricated here — see the class doc
            unscoreableCloses = unscoreable,
            undatedCloses = closed.size - dated.size,
        )
    }
}
