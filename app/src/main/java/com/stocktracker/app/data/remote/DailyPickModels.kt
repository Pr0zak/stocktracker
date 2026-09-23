package com.stocktracker.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire models for the Daily Pick (DP-3 / DP-4 / DP-12) — GET /daily_pick, GET /daily_pick/history,
 * POST /daily_pick/fit and /daily_pick/settings on the Signals service.
 *
 * EVERY NUMBER IS NULLABLE, and null is the normal answer for most of them some of the time. Http.json
 * runs with `coerceInputValues = true`, which decodes an explicit null into a non-nullable field's
 * default — so a `Double = 0.0` here would turn "the analyst gave no stop" into "Stop $0", and a factor
 * the scan could not measure into the 0th percentile. See the app UI honesty invariants.
 */

/** The card's payload. Read [available], then [stale], then [status] ("pick" | "none" | "failed"). */
@Serializable
data class DailyPickResponse(
    val available: Boolean = false,
    /** True when the newest run is from an earlier ET date than [today]. Never today's pick. */
    val stale: Boolean? = null,
    val today: String? = null,
    /** Why nothing is available (only when [available] is false). */
    val reason: String? = null,
    /** The ET date (yyyy-MM-dd) this run is for. */
    val date: String? = null,
    /** Epoch seconds the run finished. */
    val ts: Double? = null,
    val status: String? = null,
    /** Set on a failed run. A failed run is NOT "no pick today". */
    val error: String? = null,
    val universe: String? = null,
    /** The market-scan night the pick was drawn from, "20260918". */
    @SerialName("scan_night") val scanNight: String? = null,
    @SerialName("scan_lag_sessions") val scanLagSessions: Int? = null,
    val model: String? = null,
    val gate: DailyPickGate? = null,
    @SerialName("macro_available") val macroAvailable: Boolean? = null,
    val screen: DailyPickScreen? = null,
    @SerialName("rule_pick") val rulePick: DailyPickRulePick? = null,
    val pick: DailyPick? = null,
    @SerialName("none_reason") val noneReason: String? = null,
    val live: DailyPickLive? = null,
    val chase: DailyPickChase? = null,
    val repeats: DailyPickRepeats? = null,
    /** Today's latest intraday re-check, or null if none was run. Never merged into [pick]. */
    val recheck: DailyPickRecheck? = null,
) {
    val isPick: Boolean get() = status == "pick" && pick?.symbol != null
    val isNone: Boolean get() = status == "none"
    val isFailed: Boolean get() = status == "failed"
}

/**
 * An intraday re-check of this morning's shortlist against live prices (POST /daily_pick/recheck).
 * It never replaces the morning pick and is never graded — it answers "does the pick still hold now?".
 */
@Serializable
data class DailyPickRecheck(
    val date: String? = null,
    /** Epoch seconds the re-check finished. */
    val ts: Double? = null,
    /** "pick" | "none" | "failed". */
    val status: String? = null,
    val error: String? = null,
    val pick: DailyPick? = null,
    @SerialName("none_reason") val noneReason: String? = null,
    @SerialName("morning_symbol") val morningSymbol: String? = null,
    /** Null when it could not be compared (a failed re-check). */
    @SerialName("same_as_morning") val sameAsMorning: Boolean? = null,
    /** Seconds until another re-check may run; >0 means this one was returned from the cooldown. */
    @SerialName("cooldown_seconds") val cooldownSeconds: Int? = null,
)

@Serializable
data class DailyPickGate(
    val available: Boolean = false,
    /** Three-valued: true open, false shut, null undecided. Never collapse null into false. */
    val passed: Boolean? = null,
    val failing: List<String> = emptyList(),
    val unmeasured: List<String> = emptyList(),
    @SerialName("market_score") val marketScore: Double? = null,
    val note: String? = null,
    /** The five checks with their readings. Empty on runs recorded before 2026-09-23. */
    val legs: List<GateLeg> = emptyList(),
) {
    /** The same shape the Watchlist's gate card reads, so both screens word the gate identically. */
    fun toGateResponse(): GateResponse = GateResponse(
        passed = passed, available = available, marketScore = marketScore, legs = legs,
        failing = failing, unmeasured = unmeasured, note = note ?: "",
    )
}

@Serializable
data class DailyPickScreen(
    val scanned: Int? = null,
    val eligible: Int? = null,
    val rejects: Map<String, Int> = emptyMap(),
    @SerialName("earnings_excluded") val earningsExcluded: List<DailyPickEarningsExcluded> = emptyList(),
    /** Null = the run never reached the calendar; false = it could not be read. */
    @SerialName("earnings_calendar_ok") val earningsCalendarOk: Boolean? = null,
)

@Serializable
data class DailyPickEarningsExcluded(val symbol: String = "", val date: String? = null)

@Serializable
data class DailyPickRulePick(
    val symbol: String? = null,
    val name: String? = null,
    @SerialName("screen_score") val screenScore: Double? = null,
    val price: Double? = null,
    @SerialName("as_of_date") val asOfDate: String? = null,
)

/**
 * On a "pick" run: the chosen name with everything the card draws. On a "none" run only
 * [runnersUp], [convictionFloor], [rejectedSymbol], [rejectedConviction] and [closest] are set.
 */
@Serializable
data class DailyPick(
    val symbol: String? = null,
    val name: String? = null,
    val conviction: Int? = null,
    val thesis: String? = null,
    val invalidation: String? = null,
    val reasons: List<DailyPickReason> = emptyList(),
    @SerialName("reasons_dropped") val reasonsDropped: Int? = null,
    val levels: DailyPickLevels? = null,
    @SerialName("level_notes") val levelNotes: List<String> = emptyList(),
    @SerialName("risk_reward") val riskReward: DailyPickRiskReward? = null,
    @SerialName("runners_up") val runnersUp: List<DailyPickRunnerUp> = emptyList(),
    @SerialName("conviction_floor") val convictionFloor: Int? = null,
    @SerialName("gate_shut") val gateShut: Boolean? = null,
    @SerialName("price_at_pick") val priceAtPick: Double? = null,
    @SerialName("as_of_date") val asOfDate: String? = null,
    @SerialName("screen_score") val screenScore: Double? = null,
    val earnings: DailyPickEarnings? = null,
    /** Every factor the server could MEASURE for this name, in display order. Unmeasured = absent. */
    val factors: List<DailyPickFactor> = emptyList(),
    val rsi14: Double? = null,
    val atr14: Double? = null,
    @SerialName("fifty_two_week_high") val fiftyTwoWeekHigh: Double? = null,
    @SerialName("fifty_two_week_low") val fiftyTwoWeekLow: Double? = null,
    @SerialName("sma_200w") val sma200w: Double? = null,
    @SerialName("track_record") val trackRecord: DailyPickTrackRecord? = null,
    @SerialName("rejected_symbol") val rejectedSymbol: String? = null,
    @SerialName("rejected_conviction") val rejectedConviction: Int? = null,
    val closest: DailyPickRunnerUp? = null,
)

@Serializable
data class DailyPickReason(
    val factor: String = "",
    /** "supports" | "against" */
    val stance: String = "",
    val text: String = "",
) {
    val supports: Boolean get() = stance == "supports"
}

@Serializable
data class DailyPickFactor(
    val key: String = "",
    val label: String = "",
    /** The server's plain-words reading, e.g. "+8.2 pts vs the S&P over 3 months". */
    val display: String = "",
    val value: Double? = null,
    val unit: String? = null,
    /** A RANK in last night's cross-section, 0-100. Null = not ranked; never draw it as 0. */
    val pctile: Double? = null,
)

@Serializable
data class DailyPickLevels(
    @SerialName("entry_low") val entryLow: Double? = null,
    @SerialName("entry_high") val entryHigh: Double? = null,
    val stop: Double? = null,
    val target: Double? = null,
)

@Serializable
data class DailyPickRiskReward(
    @SerialName("entry_mid") val entryMid: Double? = null,
    @SerialName("risk_per_share") val riskPerShare: Double? = null,
    @SerialName("reward_per_share") val rewardPerShare: Double? = null,
    @SerialName("rr_ratio") val rrRatio: Double? = null,
)

@Serializable
data class DailyPickRunnerUp(
    val symbol: String = "",
    @SerialName("why_not") val whyNot: String? = null,
    val name: String? = null,
    val price: Double? = null,
)

@Serializable
data class DailyPickEarnings(
    /** False = the lookup failed and the date is UNKNOWN — not "nothing scheduled". */
    val ok: Boolean = false,
    val date: String? = null,
    val sessions: Int? = null,
    @SerialName("window_days") val windowDays: Int? = null,
)

@Serializable
data class DailyPickTrackRecord(
    val n: Int? = null,
    @SerialName("n_symbols") val nSymbols: Int? = null,
    @SerialName("median_excess_20d_pct") val medianExcess20dPct: Double? = null,
    @SerialName("beat_rate_20d") val beatRate20d: Double? = null,
)

@Serializable
data class DailyPickLive(
    /** The session price (pre/post-market included). Null = the quote could not be read. */
    val price: Double? = null,
    /** The regular-session price — what the intraday alerts compare against. */
    @SerialName("regular_price") val regularPrice: Double? = null,
    @SerialName("change_pct") val changePct: Double? = null,
    @SerialName("market_state") val marketState: String? = null,
    /** Epoch seconds the quote was read. */
    @SerialName("quote_ts") val quoteTs: Double? = null,
)

@Serializable
data class DailyPickChase(
    val pct: Double? = null,
    /** below_zone | in_zone | ok | chase_too_deep, or null when no read could be taken. */
    val status: String? = null,
    val warning: String? = null,
    val price: Double? = null,
)

@Serializable
data class DailyPickRepeats(
    @SerialName("window_runs") val windowRuns: Int? = null,
    val symbol: Int? = null,
    val group: Int? = null,
    val sector: Int? = null,
    @SerialName("recent_picks") val recentPicks: List<String> = emptyList(),
)

// ---------------------------------------------------------------- history (DP-4 / DP-11 / DP-14)

@Serializable
data class DailyPickHistory(
    val items: List<DailyPickHistoryItem> = emptyList(),
    /** Keyed "5d" / "20d". */
    val comparison: Map<String, DailyPickComparison> = emptyMap(),
    @SerialName("report_cards") val reportCards: List<DailyPickReportCard> = emptyList(),
    @SerialName("min_days_for_comparison") val minDaysForComparison: Int = 20,
)

@Serializable
data class DailyPickHistoryItem(
    val date: String = "",
    val status: String? = null,
    val symbol: String? = null,
    val conviction: Int? = null,
    @SerialName("price_at_pick") val priceAtPick: Double? = null,
    @SerialName("none_reason") val noneReason: String? = null,
    val error: String? = null,
    @SerialName("rule_symbol") val ruleSymbol: String? = null,
    /** Keyed "5d" / "20d" / "63d" / "252d"; a null value = that mark is not written yet. */
    val marks: Map<String, DailyPickMark?> = emptyMap(),
    @SerialName("rule_marks") val ruleMarks: Map<String, DailyPickMark?> = emptyMap(),
)

@Serializable
data class DailyPickMark(
    @SerialName("fwd_pct") val fwdPct: Double? = null,
    @SerialName("bench_pct") val benchPct: Double? = null,
    @SerialName("excess_pp") val excessPp: Double? = null,
)

@Serializable
data class DailyPickComparison(
    @SerialName("horizon_sessions") val horizonSessions: Int? = null,
    @SerialName("n_days") val nDays: Int = 0,
    @SerialName("ai_better") val aiBetter: Int = 0,
    @SerialName("rule_better") val ruleBetter: Int = 0,
    val ties: Int = 0,
    @SerialName("median_diff_pp") val medianDiffPp: Double? = null,
    @SerialName("declined_days") val declinedDays: Int = 0,
    @SerialName("declined_rule_median_excess_pp") val declinedRuleMedianExcessPp: Double? = null,
)

@Serializable
data class DailyPickReportCard(
    val date: String = "",
    val symbol: String? = null,
    @SerialName("horizon_sessions") val horizonSessions: Int = 0,
    @SerialName("fwd_pct") val fwdPct: Double? = null,
    @SerialName("bench_pct") val benchPct: Double? = null,
    @SerialName("excess_pp") val excessPp: Double? = null,
)

// ---------------------------------------------------------------- fit (DP-12) and settings

@Serializable
data class DailyPickHolding(val symbol: String, val value: Double?)

@Serializable
data class DailyPickFitRequest(val holdings: List<DailyPickHolding>, val symbol: String? = null)

@Serializable
data class DailyPickFit(
    val symbol: String = "",
    val sector: String? = null,
    @SerialName("already_held") val alreadyHeld: Boolean? = null,
    val available: Boolean = false,
    val note: String? = null,
    /** The server-built line under the plan. Rendered verbatim so the app and server cannot drift. */
    val sentence: String? = null,
    @SerialName("weight_pct") val weightPct: Double? = null,
    @SerialName("sector_weight_pct") val sectorWeightPct: Double? = null,
    @SerialName("sector_weight_after_pct") val sectorWeightAfterPct: Double? = null,
)

@Serializable
data class DailyPickSettings(val universe: String = "market")

@Serializable
data class DailyPickSettingsPatch(val universe: String? = null)
