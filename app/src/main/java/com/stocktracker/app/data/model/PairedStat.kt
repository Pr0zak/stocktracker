package com.stocktracker.app.data.model

import java.util.Locale

/**
 * A performance number that may never travel alone (SWT-9). Pure math and pure text, no Android/UI,
 * unit-tested in isolation exactly like [RiskMultiple] and [ExitTaxonomy].
 *
 * THE PAIR THIS EXISTS BECAUSE OF. The service this app's measurement spine was researched from
 * publishes, for the SAME two plans:
 *
 *     BACKTEST                                  FORWARD (its live journal)
 *     breakout  514 trades  50.0% win  1.53 PF  breakout  23 closed  73.9% win  5.17 PF
 *     pullback 1165 trades  39.2% win  1.48 PF  pullback  72 closed  65.3% win  4.25 PF
 *
 * A 23-trade forward sample showing more than three times the backtested profit factor is not an
 * edge, it is noise — and their own methodology page puts the floor at 20–30 closed trades. To their
 * credit they publish both halves and the caveat. Showing EITHER number alone, without its sample
 * size and without the other, is how a track record misleads without containing a single false
 * statement. Every rule below follows from that one example:
 *
 *  1. A SAMPLE SIZE IS NEVER OPTIONAL. "73.9% win rate" is not a number without "over 23 trades", so
 *     [StatSample] cannot be constructed without an n and there is no API that takes a bare value.
 *  2. UNDER THE FLOOR, THE COUNT, NOT THE PERCENTAGE. "3 of 4 closed green" is honest; "75%" over
 *     four trades is not. See [Reading.Counts].
 *  3. A MISSING SIDE RENDERS AS MISSING. No forward record yet is not a forward record of zero, and
 *     it must not leave the backtest standing alone as though it were the whole story — so a [Side]
 *     with no sample is required to carry the reason there isn't one, and the render sites print it.
 *  4. A SHARP DIVERGENCE ON A THIN SAMPLE IS STATED WHERE THE NUMBER IS, not in a footnote. See
 *     [noisyDivergence].
 *  5. THE TWO ARE DIFFERENT KINDS OF EVIDENCE AND THE LABELS SAY WHICH. One is a simulation over
 *     bars that had already traded; the other is a record of calls made before the outcome was known.
 *     [Evidence] carries both the short label and the sentence.
 *
 * The floor is [RiskMultiple.MIN_SCORED_FOR_EXPECTANCY] itself, not a copy of its value: a third
 * constant meaning "too few trades to believe" would drift away from the other two.
 */
data class PairedStat(
    /** What is being measured — "Win rate", "Expectancy per trade". */
    val label: String,
    /** How the value is expressed. Decides formatting AND what counts as a sharp divergence. */
    val unit: StatUnit,
    /** The simulated half. */
    val backtest: Side,
    /** The recorded-live half. */
    val forward: Side,
) {

    init {
        require(backtest.evidence == Evidence.BACKTEST) { "the backtest side must be BACKTEST evidence" }
        require(forward.evidence == Evidence.FORWARD) { "the forward side must be FORWARD evidence" }
    }

    /** Which kind of evidence a side is. The distinction is the whole point, so it is a type. */
    enum class Evidence(val shortLabel: String, val sentence: String) {
        /** Simulated over bars that had already traded. Knows the answer it is being asked about. */
        BACKTEST("Backtested", "simulated over history"),

        /** Calls recorded before the outcome was known. The half worth trusting as it accumulates. */
        FORWARD("Forward", "recorded before the outcome was known"),
    }

    /** How a value is expressed. Not cosmetic: each unit has its own idea of "sharply different". */
    enum class StatUnit { RATE_PCT, R_MULTIPLE, RATIO }

    /**
     * One side's measurement AND the sample it was measured over, inseparably.
     *
     * [n] has no default and is validated, so there is no way to construct a value without one — rule
     * 1 above, enforced by the type rather than by every render site remembering it. A side with no
     * sample at all is a null [Side.sample], never a [StatSample] with n = 0: "nothing has closed
     * yet" is a statement about the record, not a measurement of it.
     *
     * [hits] is the numerator when the statistic is a rate. It is carried so a sample under the floor
     * can degrade to "3 of 4" instead of "75%" — the count cannot be recovered from a percentage the
     * render site was told not to print.
     */
    data class StatSample(
        val n: Int,
        val value: Double,
        val hits: Int? = null,
    ) {
        init {
            require(n > 0) {
                "a statistic with no sample is not a statistic — pass a null Side.sample instead of n = 0"
            }
            require(value.isFinite()) { "a non-finite statistic is not a measurement" }
            hits?.let { require(it in 0..n) { "hits ($it) must lie within the sample ($n)" } }
        }

        /** Below the shared floor this sample is a direction, not a verdict. */
        val underFloor: Boolean get() = n < FLOOR

        companion object {
            /**
             * A rate built FROM ITS COUNTS, so both survive to the render site.
             *
             * Prefer this over the raw constructor for anything expressed as a percentage: a rate
             * that arrives already divided cannot be printed as "3 of 4" when the sample is thin.
             */
            fun rate(hits: Int, n: Int): StatSample =
                StatSample(n = n, value = if (n > 0) hits.toDouble() / n * 100.0 else 0.0, hits = hits)

            /**
             * The TOTAL form of the constructor, for render sites.
             *
             * The constructor throws on a sample that is not a sample — which is right for a model
             * that must not be talked into inventing one, and wrong inside a composable, where a
             * pathological value would take the screen down instead of degrading to "no record".
             * Returns null for exactly the inputs the constructor rejects, so the caller falls into
             * the absent branch it already has to handle.
             */
            fun of(n: Int, value: Double?, hits: Int? = null): StatSample? {
                if (n <= 0 || value == null || !value.isFinite()) return null
                if (hits != null && hits !in 0..n) return null
                return StatSample(n = n, value = value, hits = hits)
            }
        }
    }

    /**
     * One half of the pair: what kind of evidence it is, whose number it is, and either a sample or
     * the reason there isn't one.
     *
     * [absentReason] IS REQUIRED WHEN THERE IS NO SAMPLE. "No forward record" is not a fact a reader
     * can act on; "the rule engine's live signals have never been recorded and scored" is. Making it
     * mandatory is what stops a missing half from silently becoming a blank the eye skips over.
     */
    data class Side(
        val evidence: Evidence,
        /** Whose number this is — "This rule, on this chart", "The plan, replayed", "Your fills". */
        val subject: String,
        val sample: StatSample?,
        val absentReason: String? = null,
    ) {
        init {
            require(sample != null || !absentReason.isNullOrBlank()) {
                "a side with no sample must say why — absent is never zero, and it is never blank either"
            }
        }

        val isPresent: Boolean get() = sample != null

        /** True only when a sample exists AND is too thin to state as a rate. */
        val underFloor: Boolean get() = sample?.underFloor == true

        companion object {
            fun backtest(subject: String, sample: StatSample?, absentReason: String? = null) =
                Side(Evidence.BACKTEST, subject, sample, absentReason)

            fun forward(subject: String, sample: StatSample?, absentReason: String? = null) =
                Side(Evidence.FORWARD, subject, sample, absentReason)
        }
    }

    /**
     * What a render site is allowed to print for one side. Rendering follows from the model rather
     * than each screen deciding for itself, which is how the flattering number ends up travelling
     * alone in the first place.
     */
    sealed interface Reading {
        /** No sample, and why. Never a zero, never an empty cell. */
        data class Absent(val why: String) : Reading

        /** Under the floor: counts only, the percentage deliberately suppressed. */
        data class Counts(val text: String, val n: Int) : Reading

        /** At or above the floor: the measurement, with the sample it was measured over. */
        data class Measured(val text: String, val n: Int) : Reading
    }

    // ------------------------------------------------------------------ readings

    /** What may be printed for [side]. */
    fun reading(side: Side): Reading {
        val sample = side.sample
            ?: return Reading.Absent(side.absentReason ?: "no record")
        if (!sample.underFloor) return Reading.Measured(format(sample), sample.n)

        // Under the floor. A RATE degrades to its counts — "3 of 4" says everything "75%" does and
        // cannot be mistaken for a rate estimated over a sample. An expectancy or a profit factor has
        // no counts to fall back on, so it is shown WITH the caveat welded on ([smallSampleNote])
        // rather than hidden: suppressing it entirely would leave the other half standing alone,
        // which is the defect this class exists to prevent.
        return when (unit) {
            StatUnit.RATE_PCT -> {
                val hits = sample.hits
                if (hits != null) {
                    Reading.Counts("$hits of ${sample.n}", sample.n)
                } else {
                    // A rate that arrived already divided. The count is unrecoverable, so the only
                    // honest output is the sample and no rate at all.
                    Reading.Counts("too few to state a rate", sample.n)
                }
            }
            StatUnit.R_MULTIPLE, StatUnit.RATIO -> Reading.Measured(format(sample), sample.n)
        }
    }

    val backtestReading: Reading get() = reading(backtest)
    val forwardReading: Reading get() = reading(forward)

    /** The measurement formatted in this stat's unit. Never called on an absent side. */
    fun format(sample: StatSample): String = when (unit) {
        // No decimal on a rate: "73.9%" spends a decimal place of confidence the sample rarely earns.
        StatUnit.RATE_PCT -> String.format(Locale.US, "%.0f%%", sample.value)
        StatUnit.R_MULTIPLE -> RiskMultiple.format(sample.value)
        StatUnit.RATIO -> String.format(Locale.US, "%.2f", sample.value)
    }

    /** "over 514 trades" / "over 4 trades" — the half of the number nobody publishes. */
    fun sampleText(sample: StatSample): String =
        "over ${sample.n} ${if (sample.n == 1) TRIAL_SINGULAR else TRIAL_PLURAL}"

    // ------------------------------------------------------------------ what qualifies the pair

    val bothPresent: Boolean get() = backtest.isPresent && forward.isPresent

    /** Nothing at all to show. A render site with this must draw nothing, not an empty row. */
    val isEmpty: Boolean get() = !backtest.isPresent && !forward.isPresent

    /** Either present side is too thin to state as a rate. */
    val anyUnderFloor: Boolean get() = backtest.underFloor || forward.underFloor

    /**
     * The two halves disagree by more than this unit's threshold.
     *
     * The thresholds are JUDGEMENT CALLS, not measured quantities — they are set where the cautionary
     * pair above would fire (a 24-point win-rate gap, a profit factor more than three times its
     * backtest) and no theory says the line belongs exactly there. False unless both sides exist: a
     * divergence from a number nobody produced is not a divergence.
     */
    val diverges: Boolean get() {
        val bt = backtest.sample ?: return false
        val fwd = forward.sample ?: return false
        return when (unit) {
            StatUnit.RATE_PCT -> kotlin.math.abs(fwd.value - bt.value) >= SHARP_RATE_GAP_PCT
            StatUnit.R_MULTIPLE -> kotlin.math.abs(fwd.value - bt.value) >= SHARP_R_GAP
            StatUnit.RATIO -> {
                val lo = kotlin.math.min(kotlin.math.abs(fwd.value), kotlin.math.abs(bt.value))
                val hi = kotlin.math.max(kotlin.math.abs(fwd.value), kotlin.math.abs(bt.value))
                lo > 0.0 && hi / lo >= SHARP_RATIO_FACTOR
            }
        }
    }

    /**
     * A sharp divergence resting on a sample too thin to support it — the exact shape of the
     * 23-trade, 5.17-profit-factor claim in this class's doc.
     *
     * Either side counts, though in practice it is the forward one: a backtest has hundreds of
     * simulated trades and a live journal has a dozen real ones, so the gap between them is usually a
     * statement about the smaller sample rather than about the strategy.
     */
    val noisyDivergence: Boolean get() = diverges && anyUnderFloor

    /** The sentence [noisyDivergence] has to put NEXT TO the number, or null when it does not apply. */
    val divergenceNote: String? get() {
        if (!noisyDivergence) return null
        val bt = backtest.sample ?: return null
        val fwd = forward.sample ?: return null
        val thin = if (fwd.underFloor) fwd else bt
        val which = if (fwd.underFloor) "forward" else "backtested"
        return "The $which figure is a long way from the other over ${thin.n} " +
            "${if (thin.n == 1) TRIAL_SINGULAR else TRIAL_PLURAL} — a gap that size on a sample that " +
            "small is noise, not an edge. Below $FLOOR, one or two outcomes move it."
    }

    /** The caveat that must travel with any present side under the floor, or null. */
    val smallSampleNote: String? get() {
        if (!anyUnderFloor) return null
        return "Under $FLOOR ${TRIAL_PLURAL}, treat this as a direction and not a verdict."
    }

    /**
     * The whole pair as one line, for a site that has room for text and not for a table.
     *
     * Both halves always appear, absent ones included: "or neither appears" is the rule, and a line
     * that quietly dropped the empty half would be the flattering number travelling alone again.
     */
    fun line(): String {
        fun part(side: Side): String = when (val r = reading(side)) {
            is Reading.Absent -> "${side.evidence.shortLabel} — ${r.why}"
            is Reading.Counts -> "${side.evidence.shortLabel} ${r.text}"
            is Reading.Measured -> "${side.evidence.shortLabel} ${r.text} over ${r.n}"
        }
        return "$label — ${part(backtest)} · ${part(forward)}"
    }

    companion object {
        /**
         * The shared small-sample floor, and deliberately the SAME constant as
         * [RiskMultiple.MIN_SCORED_FOR_EXPECTANCY] and [ExitTaxonomy.MIN_CLASSIFIED_FOR_RATES]. All
         * three mean "too few closed trades for this to be evidence"; three separate literals would
         * drift and the app would then contradict itself about what counts as a sample.
         */
        const val FLOOR = RiskMultiple.MIN_SCORED_FOR_EXPECTANCY

        /** A win-rate gap in PERCENTAGE POINTS that reads as a different strategy. Judgement call. */
        const val SHARP_RATE_GAP_PCT = 15.0

        /** An expectancy gap in R that reads as a different strategy. Judgement call. */
        const val SHARP_R_GAP = 0.5

        /** One ratio at least this many times the other — 1.53 against 5.17 is 3.4×. Judgement call. */
        const val SHARP_RATIO_FACTOR = 2.0

        private const val TRIAL_SINGULAR = "trade"
        private const val TRIAL_PLURAL = "trades"

        /** The one line that says what the two labels mean. Shown once per surface, not per row. */
        const val EVIDENCE_NOTE =
            "Backtested = the rule run over bars that had already traded. Forward = calls recorded " +
                "before the outcome was known. Only the second is a track record."
    }
}
