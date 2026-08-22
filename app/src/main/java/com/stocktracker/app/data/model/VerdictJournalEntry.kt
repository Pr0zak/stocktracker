package com.stocktracker.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * THE JOURNAL — what YOU did with a verdict (SWT-8). The equity twin of [ClosedCallPosition]: same
 * shape of problem (a plan, a real fill, a real exit, scored in R), on shares instead of contracts.
 *
 * WHY THIS EXISTS. Three things in this app measure decisions and none of them measures YOURS.
 * `memory/stats` on the backend scores what the ANALYST said, against a fixed 20-day forward return
 * with no exit at all. The sandbox scores what the PAPER TRADER did, in its own ledger with its own
 * money. The options tracker scores real positions — but only options. Nothing records that a verdict
 * was given on a stock, whether you acted on it, at what price, and how it ended. So "is this thing
 * actually helping me" has never had data behind it, and the honest answer has been that nobody knows.
 *
 * TWO CURVES ARE THE POINT. With the plan snapshotted and the real fill recorded, the cumulative R of
 * what YOU got can be drawn against the cumulative R the MECHANICAL PLAN would have produced — see
 * [VerdictJournal.curve]. The gap between them is the only number in this app that measures execution
 * rather than analysis. The mechanical side is supplied by a replay engine that lives in the signals
 * backend; it is never fabricated here, because a curve drawn from a plan nobody replayed is exactly
 * the invented number this whole feature exists to eliminate.
 *
 * EVERY FILL AND EXIT FIELD IS NULLABLE, AND THAT IS THE NORMAL CASE. A verdict logged and not yet
 * acted on is what most of this store contains. Under `coerceInputValues` a non-nullable `Double`
 * would decode both an omitted key and an explicit null to 0.0 and render "filled at $0" on a trade
 * that was never taken — the same defect the comment on [EntryPlan] describes.
 */
@Serializable
data class VerdictJournalEntry(
    val id: String = UUID.randomUUID().toString(),
    val symbol: String,
    /** The day the verdict was given, ISO yyyy-MM-dd. Not the day it was logged, if they differ. */
    val verdictDateIso: String,
    /** The plan AS IT STOOD when the verdict was given. A snapshot, never a live reference. */
    val plan: JournalPlan = JournalPlan(),
    /** Taken / not taken / not yet decided. Three-valued on purpose — see [TakenState]. */
    val taken: TakenState = TakenState.UNDECIDED,
    // --- your real entry; all null until you actually buy ---
    /** YOUR fill price per share. Not the plan's entry zone — what you actually paid. */
    val fillPrice: Double? = null,
    /** Shares you actually bought. Fractional, because this app's holdings are. */
    val shares: Double? = null,
    val fillDateIso: String? = null,
    // --- your real exit; all null until you actually sell ---
    /** YOUR exit price per share. */
    val exitPrice: Double? = null,
    val exitDateIso: String? = null,
    /** Free text — why you skipped it, why you sized it that way, what you'd do differently. */
    val notes: String? = null,
) {

    /** Where this entry stands right now. Derived, so it can never disagree with the fields. */
    val status: JournalStatus
        get() = when (taken) {
            TakenState.UNDECIDED -> JournalStatus.UNDECIDED
            TakenState.NOT_TAKEN -> JournalStatus.NOT_TAKEN
            TakenState.TAKEN -> when {
                fillPrice == null -> JournalStatus.TAKEN_UNFILLED
                exitPrice == null -> JournalStatus.OPEN
                else -> JournalStatus.CLOSED
            }
        }

    /** You acted on it. Says nothing about whether a fill was ever recorded. */
    val isTaken: Boolean get() = taken == TakenState.TAKEN

    /** Taken, filled, and exited — the only entries that can carry a result. */
    val isClosed: Boolean get() = status == JournalStatus.CLOSED

    /** Taken and filled but not yet exited. NOT a 0R closed trade; it has no result at all yet. */
    val isOpen: Boolean get() = status == JournalStatus.OPEN

    /** Cost of what you actually bought, or null while either half is unknown. Never 0.0 for absence. */
    val costBasis: Double? get() {
        val price = fillPrice ?: return null
        val qty = shares ?: return null
        return price * qty
    }

    /** Realized dollars on the shares — null until the position is genuinely closed. */
    val realizedPnl: Double? get() {
        val entry = fillPrice ?: return null
        val exit = exitPrice ?: return null
        val qty = shares ?: return null
        return (exit - entry) * qty
    }

    /**
     * YOUR R on this entry, or null when it cannot be defined.
     *
     * The entry is YOUR FILL, not the plan's entry zone — the whole point is measuring what you got,
     * and a plan-priced entry would quietly score the plan again. The stop is the PLAN's stop, because
     * R's denominator is the risk that was DEFINED AT ENTRY and the snapshot is the only place that
     * survives. [RiskMultiple.rMultiple] owns the arithmetic and the null rules: no stop recorded, a
     * stop at or above the entry, or a non-finite input all come back null rather than 0.0.
     *
     * Worked: plan stop $90, your fill $100, your exit $120 → risk $10 → (120 − 100) / 10 = +2.0R.
     * A verdict you logged and never took has no fill, so it has no R and belongs to no rate.
     */
    val rMultiple: Double?
        get() {
            val entry = fillPrice ?: return null
            val exit = exitPrice ?: return null
            return RiskMultiple.rMultiple(entry = entry, exit = exit, stop = plan.stop)
        }

    /**
     * Which planned level your exit corresponded to, via [ExitTaxonomy.classifyAgainstLevels] — the
     * same rule the options history is bucketed with, one implementation.
     *
     * Null — not [ExitTaxonomy.ExitKind.UNPLANNED] — while the entry is still open or was never
     * taken. UNPLANNED means "it ended and we could not tell how"; an open trade has not ended, and
     * filing it under any bucket would put a live position into an exit breakdown.
     */
    val exitKind: ExitTaxonomy.ExitKind?
        get() = if (!isClosed) null
        else ExitTaxonomy.classifyAgainstLevels(exitPrice, plan.stop, plan.target)
}

/**
 * Did you act on it? THREE-VALUED, NOT A BOOLEAN.
 *
 * "I passed on this" and "I haven't decided yet" are different facts, and a `Boolean taken = false`
 * states the first while meaning the second — which would inflate the not-taken count with every
 * freshly logged verdict and make the take rate a function of how recently you opened the app.
 */
@Serializable
enum class TakenState {
    /** Logged, not yet acted on either way. What every fresh entry is, and the store's default state. */
    UNDECIDED,

    /** You acted on it. The fill may or may not have been recorded yet. */
    TAKEN,

    /** You deliberately passed. Counted — see [VerdictJournal.ActualRecord.notTakenCount]. */
    NOT_TAKEN,
}

/** Where an entry stands, derived from [TakenState] plus which of the fill/exit fields exist. */
enum class JournalStatus {
    /** Logged, no decision recorded yet. */
    UNDECIDED,

    /** Passed on. */
    NOT_TAKEN,

    /** Marked taken, but no fill price recorded — so nothing about it can be scored. */
    TAKEN_UNFILLED,

    /** Taken and filled, still held. Has no result yet, and must never be scored as one. */
    OPEN,

    /** Taken, filled and exited. The only status that can carry an R or an exit bucket. */
    CLOSED,
}

/**
 * The plan as it stood when the verdict was given — a SNAPSHOT, deliberately not a reference.
 *
 * Mirrors [EntryPlan], the analyst's own shape, but stores it rather than pointing at it: the plan
 * endpoint is cached for hours and regenerated on demand, so a live reference would silently rewrite
 * the history you are trying to measure against. Comparing your fill to today's plan instead of the
 * one you were actually given is the failure this class exists to prevent.
 *
 * EVERY FIELD IS NULLABLE, INCLUDING ALL OF THEM AT ONCE. The analyst legitimately returns null when
 * it cannot justify a level, and an entry written before this class carried a field decodes with the
 * key simply absent. Both must read as UNKNOWN. A stop of 0.0 is a claim about risk — it is the
 * "Stop $0 · target $0" incident from [EntryPlan] — and a stop of null makes the entry unscoreable in
 * R, which is the honest outcome.
 */
@Serializable
data class JournalPlan(
    /** buy_now | buy_on_pullback | wait | avoid, as [EntryPlan.action] spells them. */
    val action: String? = null,
    val entryLow: Double? = null,
    val entryHigh: Double? = null,
    /** A PRICE, not a percent — unlike the options side, where the stop is a percent of the premium. */
    val stop: Double? = null,
    /** A PRICE, for the same reason. */
    val target: Double? = null,
    /** 0–100 as the analyst reports it. Null when the verdict carried none. */
    val conviction: Int? = null,
    /** The one-paragraph reason, frozen at verdict time. */
    val thesis: String? = null,
) {
    /** Was any level recorded at all? With neither, the entry can never be scored or bucketed. */
    val hasLevels: Boolean get() = stop != null || target != null
}
