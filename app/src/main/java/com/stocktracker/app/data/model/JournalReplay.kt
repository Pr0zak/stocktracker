package com.stocktracker.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The MECHANICAL leg of a journal entry (SWT-8): what the plan AS WRITTEN would have done, walked
 * bar by bar by the signals backend's `POST /journal/replay`.
 *
 * ONE CLASS, TWO JOBS, ON PURPOSE. This is both the wire shape the route returns and the shape
 * stored on [VerdictJournalEntry.replay]. A second "stored" copy would have to be kept in step with
 * the route by hand, and the first field that drifted would put a number on the chart that the
 * server never said. The plan snapshot ([JournalPlan]) is a separate class from [EntryPlan] for the
 * opposite reason — a plan is REGENERATED and a live reference would rewrite history — but a replay
 * result is a recorded fact about bars that have already traded, so recording it verbatim is exactly
 * what we want.
 *
 * WHY IT IS STORED AT ALL rather than fetched when a curve is drawn. Two reasons, and the second is
 * the important one. A replay walks up to two years of daily bars per entry, so re-fetching on every
 * recomposition would be absurd. But more than that: a mechanical outcome recorded once is a FACT
 * with a date on it, where one recomputed at render time can quietly change — the horizon default
 * moves, the fill window changes, Yahoo restates a split — and the "what the plan would have done"
 * half of the comparison would rewrite itself under the user with nothing on screen to say so.
 * [replayedAtMs] is when we recorded it.
 *
 * EVERY NUMBER IS NULLABLE AND `outcome` IS NULLABLE TOO. `Http.json` sets `coerceInputValues = true`,
 * so a non-nullable `Double = 0.0` would swallow both an omitted key and the explicit `null` this
 * route emits on every path where a value does not apply — the "Stop $0 · target $0" defect
 * (see [EntryPlan]). `r: null` is NOT 0R: 0R is a real claim (the plan exited exactly where it
 * entered), where null means no stop was named or the trade has not exited. And `outcome: null` is
 * the single most common state in a fresh journal — it means "nothing has traded since the plan was
 * written". It is not an error, and it must never render as one.
 */
@Serializable
data class JournalReplay(
    /** target | stop | time | open | never_filled, or null when nothing has been decided yet. */
    val outcome: String? = null,
    /** The server's own sentence for [outcome] — including why nothing was decided. */
    val reason: String? = null,
    /**
     * The PLAN was unusable (an inverted zone, a stop that is not below the entry) — a fact about
     * what was recorded, not about the market. Defaulting to false is right for a replay stored
     * before this field existed: it was accepted, so it was not refused.
     */
    val refused: Boolean = false,
    /**
     * One daily bar touched BOTH the stop and the target and the bars cannot say which came first.
     * The backend resolves it against the trade (assumes the stop) and sets this so the assumption
     * travels with the row. A curve leaning on these must say how many — see
     * [JournalComparison.Paired.ambiguousCount].
     */
    val ambiguous: Boolean = false,
    @SerialName("entry_price") val entryPrice: Double? = null,
    @SerialName("entry_date") val entryDate: String? = null,
    @SerialName("exit_price") val exitPrice: Double? = null,
    @SerialName("exit_date") val exitDate: String? = null,
    @SerialName("bars_held") val barsHeld: Int? = null,
    /** R the plan produced. Null when no stop was named or the trade never exited. NEVER 0.0 for absence. */
    val r: Double? = null,
    @SerialName("return_pct") val returnPct: Double? = null,
    /** Last observed close for a plan still running. A MARK, deliberately not in the exit field. */
    @SerialName("mark_price") val markPrice: Double? = null,
    @SerialName("mark_date") val markDate: String? = null,
    @SerialName("horizon_days") val horizonDays: Int? = null,
    @SerialName("fill_window_days") val fillWindowDays: Int? = null,
    @SerialName("bars_seen") val barsSeen: Int? = null,
    @SerialName("bars_skipped") val barsSkipped: Int? = null,
    // --- provenance the route adds around the replay itself ---
    val symbol: String? = null,
    @SerialName("as_of") val asOf: String? = null,
    val source: String? = null,
    @SerialName("bars_from") val barsFrom: String? = null,
    @SerialName("bars_to") val barsTo: String? = null,
    val note: String? = null,
    /**
     * Epoch ms this replay was RECORDED on the device. Set by the client after decoding, never sent
     * by the server — an entry replayed in June and an entry replayed this morning are different
     * evidence, and without a date on it the mechanical curve is undateable.
     */
    @SerialName("replayed_at_ms") val replayedAtMs: Long? = null,
) {

    /**
     * The plan reached an end the bars can price: a level, or the clock.
     *
     * `open` and `never_filled` are deliberately NOT resolved. They are the two ways a plan produced
     * no result, and neither is a flat trade — treating either as 0R would put a plan that was never
     * tradeable on the curve as a scratch it never took.
     */
    val isResolved: Boolean get() = outcome == TARGET || outcome == STOP || outcome == TIME

    /**
     * The R this replay contributes to a mechanical curve, or null when it contributes none.
     *
     * Both halves are required: a resolved outcome AND a finite R. A resolved trade whose plan named
     * no stop has no denominator and is unscoreable, exactly as [VerdictJournalEntry.rMultiple] is on
     * your side of the same entry.
     */
    val scoredR: Double? get() = r?.takeIf { isResolved && it.isFinite() }

    /** Nothing has been decided yet, and the plan itself is fine. The normal state of a fresh entry. */
    val isPending: Boolean get() = outcome == null && !refused

    companion object {
        const val TARGET = "target"
        const val STOP = "stop"
        const val TIME = "time"
        const val OPEN = "open"
        const val NEVER_FILLED = "never_filled"

        /**
         * One short line for what the replay says, for a row that has room for one line.
         *
         * A NULL REPLAY AND A NULL OUTCOME BOTH READ "Not replayed yet", and neither reads as a
         * failure. Nothing has been asked of the server for the first; the server was asked and
         * answered "no session has traded since" for the second. Both are ordinary states of a
         * journal you are keeping in real time, and a row that shouted "error" at either would train
         * the reader to ignore the rows that mean it.
         */
        fun describe(replay: JournalReplay?): String = when {
            replay == null -> "Not replayed yet"
            replay.refused -> "Plan can't be replayed"
            replay.outcome == null -> "Not replayed yet"
            replay.outcome == TARGET -> "Plan hit target"
            replay.outcome == STOP -> "Plan stopped out"
            replay.outcome == TIME -> "Plan ran out of clock"
            replay.outcome == OPEN -> "Plan still open"
            replay.outcome == NEVER_FILLED -> "Plan never filled"
            else -> replay.outcome // a newer server naming an outcome this build has never heard of
        }
    }
}
