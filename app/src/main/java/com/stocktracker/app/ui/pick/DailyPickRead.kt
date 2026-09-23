package com.stocktracker.app.ui.pick

import com.stocktracker.app.data.remote.DailyPickComparison
import com.stocktracker.app.data.remote.DailyPickLevels
import com.stocktracker.app.data.remote.DailyPickReportCard
import com.stocktracker.app.data.remote.DailyPickResponse
import com.stocktracker.app.data.remote.DailyPickTrackRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Daily Pick's pure rules (DP-5 / DP-6 / DP-10 / DP-14 / DP-15): what the card says, where the plan
 * ladder puts each mark, and which intraday alert a price crossing earns. No Android, no Compose, so
 * every rule here is unit-tested.
 *
 * The recurring defect this app has had to keep fixing is "absent rendered as a confident number". Every
 * function below takes nullable inputs and returns null — or a sentence that says what is missing — rather
 * than substituting a zero.
 */
object DailyPickRead {

    private val ET: ZoneId = ZoneId.of("America/New_York")
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

    /** What the card is, in one of six shapes. The composable renders exactly one of them. */
    sealed interface Shape {
        data object NotConfigured : Shape
        data object Loading : Shape
        /** The load failed. Carries the error and NOTHING from a previous load. */
        data class LoadFailed(val message: String) : Shape
        /** The server has never run a pick. */
        data class NeverRun(val reason: String) : Shape
        /** The run for [date] failed server-side. Not "no pick". */
        data class RunFailed(val date: String?, val error: String, val stale: Boolean) : Shape
        data class NoPick(val resp: DailyPickResponse, val stale: Boolean) : Shape
        data class Pick(val resp: DailyPickResponse, val stale: Boolean) : Shape
    }

    fun shape(configured: Boolean, loading: Boolean, resp: DailyPickResponse?, error: String?): Shape = when {
        !configured -> Shape.NotConfigured
        error != null -> Shape.LoadFailed(error)
        resp == null -> if (loading) Shape.Loading else Shape.LoadFailed("No response from the Signals service.")
        !resp.available -> Shape.NeverRun(resp.reason ?: "The daily pick has not run yet.")
        resp.isFailed -> Shape.RunFailed(resp.date, resp.error ?: "The run failed without saying why.", resp.stale == true)
        resp.isPick -> Shape.Pick(resp, resp.stale == true)
        resp.isNone -> Shape.NoPick(resp, resp.stale == true)
        else -> Shape.LoadFailed("The Signals service returned a pick in a shape this app does not know (${resp.status}).")
    }

    /** "TODAY'S PICK", or a header that names the older date instead of passing it off as today's. */
    fun header(shape: Shape): String = when (shape) {
        is Shape.Pick -> if (shape.stale) "PICK FROM ${dayLabel(shape.resp.date)}" else "TODAY'S PICK"
        is Shape.NoPick -> if (shape.stale) "NO PICK ON ${dayLabel(shape.resp.date)}" else "NO PICK TODAY"
        is Shape.RunFailed -> if (shape.stale) "PICK FAILED ON ${dayLabel(shape.date)}" else "TODAY'S PICK FAILED"
        else -> "TODAY'S PICK"
    }

    /** Under a stale header: when the next one is due. */
    const val STALE_NOTE = "Today's pick runs at 8:05 AM ET on trading days."

    fun dayLabel(iso: String?): String =
        iso?.let { runCatching { LocalDate.parse(it).format(DAY).uppercase(Locale.US) }.getOrNull() } ?: "AN EARLIER DAY"

    /** "picked 8:07 AM ET" from the run's epoch seconds, or null when the server sent none. */
    fun pickedAt(ts: Double?): String? {
        if (ts == null || ts <= 0) return null
        val t = Instant.ofEpochMilli((ts * 1000).toLong()).atZone(ET)
        return "picked ${t.format(TIME)} ET"
    }

    /** "price 2m ago" / "price 3h ago", or "price unavailable". The card must always say which. */
    fun priceAge(quoteTs: Double?, nowMs: Long): String {
        if (quoteTs == null || quoteTs <= 0) return "price unavailable"
        val mins = ((nowMs - quoteTs * 1000) / 60_000).toLong().coerceAtLeast(0)
        return when {
            mins < 1 -> "price just now"
            mins < 60 -> "price ${mins}m ago"
            else -> "price ${mins / 60}h ago"
        }
    }

    /** "The latest scan is from Fri Sep 18" when the pick was drawn from a scan one session behind. */
    fun scanLagNote(resp: DailyPickResponse): String? {
        val lag = resp.scanLagSessions ?: return null
        if (lag <= 0) return null
        val night = resp.scanNight ?: return "Drawn from a market scan $lag session${if (lag == 1) "" else "s"} old."
        val d = runCatching {
            LocalDate.of(night.substring(0, 4).toInt(), night.substring(4, 6).toInt(), night.substring(6, 8).toInt())
        }.getOrNull()
        return "Drawn from the ${d?.format(DAY) ?: night} close — last night's market scan did not run."
    }

    // ------------------------------------------------------------ plan ladder

    /** One mark on the ladder, positioned 0..1 along it. */
    data class Mark(val kind: Kind, val price: Double, val x: Float) {
        enum class Kind { STOP, ZONE_LOW, ZONE_HIGH, TARGET, PRICE }
    }

    /**
     * Horizontal positions for every level that EXISTS. A null level gets no mark (never a mark at $0),
     * and a ladder with fewer than two distinct prices is not a ladder — null.
     */
    fun ladder(levels: DailyPickLevels?, price: Double?): List<Mark>? {
        val raw = buildList {
            levels?.stop?.let { add(Mark.Kind.STOP to it) }
            levels?.entryLow?.let { add(Mark.Kind.ZONE_LOW to it) }
            levels?.entryHigh?.let { add(Mark.Kind.ZONE_HIGH to it) }
            levels?.target?.let { add(Mark.Kind.TARGET to it) }
            price?.let { add(Mark.Kind.PRICE to it) }
        }.filter { it.second > 0 && it.second.isFinite() }
        if (raw.map { it.second }.distinct().size < 2) return null
        val lo = raw.minOf { it.second }
        val hi = raw.maxOf { it.second }
        val pad = (hi - lo) * 0.06
        val a = lo - pad
        val span = (hi + pad) - a
        return raw.map { (k, p) -> Mark(k, p, ((p - a) / span).toFloat()) }
    }

    /** "Risk $5.50 to make $12.50 a share · 2.3R", with each half only when it exists. */
    fun riskLine(riskPerShare: Double?, rewardPerShare: Double?, rr: Double?): String? {
        val parts = buildList {
            if (riskPerShare != null && rewardPerShare != null) {
                add("risk ${money(riskPerShare)} to make ${money(rewardPerShare)} a share")
            } else if (riskPerShare != null) {
                add("risk ${money(riskPerShare)} a share · no target given")
            } else if (rewardPerShare != null) {
                add("${money(rewardPerShare)} a share to the target · no stop given")
            }
            rr?.let { add(String.format(Locale.US, "%.1fR", it)) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun chaseLabel(status: String?, pct: Double?): String? = when (status) {
        "in_zone" -> "in the buy zone"
        "below_zone" -> "below the buy zone"
        "ok" -> pct?.let { String.format(Locale.US, "%.1f%% above the zone", it) } ?: "just above the zone"
        "chase_too_deep" -> pct?.let { String.format(Locale.US, "ran %.1f%% past the zone", it) } ?: "ran past the zone"
        else -> null
    }

    // ------------------------------------------------------------ factors

    /** A percentile bar's label: "88th". Null percentile → null, and the bar is not drawn. */
    fun ordinal(p: Double?): String? {
        if (p == null || !p.isFinite()) return null
        val n = p.roundToInt().coerceIn(0, 100)
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    fun trackRecordLine(tr: DailyPickTrackRecord?): String? {
        val beat = tr?.beatRate20d ?: return null
        val n = tr.n ?: return null
        val names = tr.nSymbols?.let { " across $it names" } ?: ""
        return "Setups like this beat the S&P ${(beat * 100).roundToInt()}% of the time over 20 days · n=$n$names"
    }

    /** Below this many graded setups a rate is shown greyed, as the memory layer's own card does. */
    const val MIN_TRUSTED_N = 20

    fun repeatLine(symbolCount: Int?, symbol: String): String? =
        if (symbolCount != null && symbolCount >= 1) "Picked ${symbolCount + 1}× in the last month — $symbol before too" else null

    // ------------------------------------------------------------ DP-15 explanations

    /** Static, written once — never model text, so it cannot drift from the numbers. */
    val explanations: Map<String, String> = mapOf(
        "trend" to "Trend: whether the price sits above its 50-day and 200-day average prices, and whether those averages are stacked in rising order. Stacked is the strongest shape a trend has.",
        "rel_strength" to "Relative strength: how this stock has done compared with the S&P 500 over the last 3 months, in percentage points. The bar is its rank against every stock measured last night.",
        "momentum" to "Momentum: the price change over the last 60 trading sessions. Momentum is the best-evidenced single factor in stock returns, which is why the pick weights it most.",
        "rsi" to "RSI (relative strength index) measures how fast the price has moved lately, 0 to 100. Above 70 is 'stretched' (overbought); below 30 is 'washed out' (oversold).",
        "extension" to "How far the price is above or below its 50-day average. A stock far above it has run a long way quickly and is more prone to snap back.",
        "range_52w" to "Where the price sits against its highest price of the last year. Stocks near their highs tend to keep making new ones more often than stocks far below them.",
        "long_cycle" to "The 200-week average: roughly the four-year trend line. Far above it means a long advance; below it is historically rare for quality names.",
        "volume" to "How much the stock traded versus its normal day. Heavy volume on a rise means more buyers are behind it.",
        "volatility" to "How much the price moves on an average day. The bar ranks it against the market: the 90th percentile is among the jumpiest 10%.",
        "track_record" to "What happened after past setups that looked like this one, measured against the S&P over the same 20 days. n is how many past setups were found; fewer than 20 is too few to lean on.",
        "insider" to "Company insiders (executives, directors) buying their own stock with their own money, from SEC Form 4 filings.",
        "quality" to "Business-quality tags from the company's financials: return on equity, debt level, and similar.",
        "short_interest" to "How many shares are sold short and how many days of normal volume it would take to buy them back. Heavy shorting is usually a bearish sign.",
        "seasonality" to "How this stock has typically done in the current calendar month over the past decade. A weak tilt, not a rule.",
        "macro" to "The news-driven backdrop: wars, rates, oil, policy. No macro read means the backdrop is UNKNOWN, not calm.",
        "earnings" to "An earnings report can move a stock 5-10% overnight in either direction. Picks exclude names reporting in the next 3 sessions.",
        "regime" to "The market gate: five conditions (S&P and Nasdaq above their 50-day averages, breadth, the VIX, and S&P momentum). When it is shut, the pick needs conviction 70 instead of 60.",
        "conviction" to "Conviction: how sure the analyst is, 0 to 100. 70+ means several independent signals agree; 40-55 is a mixed picture. The card only shows picks of 60 or more (70 when the market gate is shut).",
        "ladder" to "The plan: the stop is where the idea is wrong and you would sell; the shaded zone is a reasonable price to pay today; the target is the first realistic upside. R is the reward divided by the risk.",
        "percentile" to "The bars are ranks, not grades: '88th' means higher than 88% of the ~3,000 stocks measured last night. A high rank is not always good — a high volatility rank means a jumpier stock.",
    )

    // ------------------------------------------------------------ DP-11 comparison

    fun comparisonLine(c: DailyPickComparison?, minDays: Int): String? {
        c ?: return null
        if (c.nDays == 0) return "No pick has a ${c.horizonSessions ?: "?"}-day result yet."
        val head = "AI pick beat the simple rule on ${c.aiBetter} of ${c.nDays} days" +
            (if (c.ties > 0) " (${c.ties} tie${if (c.ties == 1) "" else "s"})" else "")
        val med = c.medianDiffPp?.let { String.format(Locale.US, ", median %+.1f pts", it) } ?: ""
        val thin = if (c.nDays < minDays) " — too few days to mean anything yet" else ""
        return head + med + thin
    }

    // ------------------------------------------------------------ DP-7 / DP-14 notification text

    data class Note(val title: String, val body: String)

    /**
     * The morning notification, or null when there is nothing honest to say: not configured, not today's
     * run, or a failed run. A failed run is shown on the card, not pushed as "no pick".
     */
    fun morningNote(resp: DailyPickResponse?, today: String): Note? {
        if (resp == null || !resp.available || resp.date != today || resp.stale == true) return null
        if (resp.isPick) {
            val p = resp.pick!!
            val sym = p.symbol!!
            val conv = p.conviction?.let { " ($it)" } ?: ""
            val body = p.thesis?.takeIf { it.isNotBlank() } ?: "Tap for the reasons for and against."
            return Note("Today's pick: $sym$conv", body)
        }
        if (resp.isNone) {
            return Note("No pick today", resp.noneReason ?: "Nothing cleared the bar this morning.")
        }
        return null
    }

    fun reportCardLine(card: DailyPickReportCard): String? {
        val fwd = card.fwdPct ?: return null
        val sym = card.symbol ?: return null
        val span = if (card.horizonSessions == 5) "last week's" else "${card.horizonSessions}-day"
        val bench = card.benchPct?.let { String.format(Locale.US, " vs S&P %+.1f%%", it) } ?: " (S&P return unavailable)"
        return String.format(Locale.US, "%s pick %s: %+.1f%%", span.replaceFirstChar { it.uppercase() }, sym, fwd) + bench
    }

    // ------------------------------------------------------------ DP-10 intraday alerts

    enum class PriceState { BELOW_STOP, BELOW_ZONE, IN_ZONE, ABOVE_ZONE, RAN_PAST, AT_TARGET }

    enum class Alert(val final: Boolean) { ENTERED_ZONE(false), RAN_PAST(false), HIT_STOP(true), HIT_TARGET(true) }

    /** Same threshold the server's chase read uses (chase.CHASE_OK_PCT). */
    const val RAN_PAST_PCT = 1.5

    /** Where [price] sits against the plan, or null when it cannot be placed (no price, no zone top). */
    fun priceState(price: Double?, levels: DailyPickLevels?): PriceState? {
        if (price == null || !price.isFinite() || price <= 0) return null
        val stop = levels?.stop
        val target = levels?.target
        if (stop != null && price <= stop) return PriceState.BELOW_STOP
        if (target != null && price >= target) return PriceState.AT_TARGET
        val hi = levels?.entryHigh ?: return null
        val lo = levels.entryLow
        if (price > hi) {
            return if ((price / hi - 1) * 100 > RAN_PAST_PCT) PriceState.RAN_PAST else PriceState.ABOVE_ZONE
        }
        if (lo != null && price < lo) return PriceState.BELOW_ZONE
        return PriceState.IN_ZONE
    }

    /**
     * The alert this observation earns, given the previous observation today and what was already sent.
     *
     * * Stop and target fire whenever reached — even on the first look of the day — and are FINAL: once
     *   either has fired, nothing else fires that day.
     * * "Ran past" fires once a day, the first time the price is seen beyond the chase threshold.
     * * "Entered zone" fires only on a real crossing: the previous look was above the zone. The first look
     *   of the day finding it in the zone is what the morning notification already said.
     * * A price that cannot be placed (null state) never fires anything.
     */
    fun alertFor(prev: PriceState?, now: PriceState?, sent: Set<Alert>): Alert? {
        if (now == null) return null
        if (sent.any { it.final }) return null
        val candidate = when (now) {
            PriceState.BELOW_STOP -> Alert.HIT_STOP
            PriceState.AT_TARGET -> Alert.HIT_TARGET
            PriceState.RAN_PAST -> Alert.RAN_PAST
            PriceState.IN_ZONE ->
                if (prev == PriceState.ABOVE_ZONE || prev == PriceState.RAN_PAST) Alert.ENTERED_ZONE else null
            else -> null
        } ?: return null
        return candidate.takeIf { it !in sent }
    }

    fun alertNote(alert: Alert, symbol: String, price: Double, levels: DailyPickLevels?, readAt: String, mine: Boolean): Note {
        val who = if (mine) "Your $symbol position" else symbol
        val px = money(price)
        return when (alert) {
            Alert.ENTERED_ZONE -> Note(
                "$symbol is back in its buy zone",
                "$px at $readAt — zone ${money(levels?.entryLow)}–${money(levels?.entryHigh)}.",
            )
            Alert.RAN_PAST -> {
                val pct = levels?.entryHigh?.let { abs(price / it - 1) * 100 }
                Note(
                    "$symbol ran past its buy zone",
                    "$px at $readAt" + (pct?.let { String.format(Locale.US, ", %.1f%% above the zone top", it) } ?: "") +
                        ". Chasing it means a worse entry than the plan.",
                )
            }
            Alert.HIT_STOP -> Note(
                if (mine) "$who hit its stop" else "$symbol broke its stop",
                "$px at $readAt, at or under the ${money(levels?.stop)} stop — " +
                    if (mine) "the plan says the idea is wrong." else "today's pick is invalidated.",
            )
            Alert.HIT_TARGET -> Note(
                "$who reached its target",
                "$px at $readAt, at or above the ${money(levels?.target)} target.",
            )
        }
    }

    fun money(v: Double?): String =
        v?.let { if (abs(it) >= 1000) String.format(Locale.US, "$%,.0f", it) else String.format(Locale.US, "$%.2f", it) } ?: "—"

    // Log entries for the alert dedupe: "2026-09-22|BRK-B|HIT_STOP" and "2026-09-22|BRK-B|last:IN_ZONE".

    fun sentAlerts(log: Set<String>, date: String, symbol: String): Set<Alert> =
        log.mapNotNull { e ->
            val p = e.split("|")
            if (p.size == 3 && p[0] == date && p[1] == symbol && !p[2].startsWith("last:")) {
                runCatching { Alert.valueOf(p[2]) }.getOrNull()
            } else null
        }.toSet()

    fun lastState(log: Set<String>, date: String, symbol: String): PriceState? =
        log.firstNotNullOfOrNull { e ->
            val p = e.split("|")
            if (p.size == 3 && p[0] == date && p[1] == symbol && p[2].startsWith("last:")) {
                runCatching { PriceState.valueOf(p[2].removePrefix("last:")) }.getOrNull()
            } else null
        }

    /** The log after this observation: today's entries only (older days pruned), last state replaced. */
    fun nextLog(log: Set<String>, date: String, symbol: String, state: PriceState?, fired: Alert?): Set<String> {
        val keep = log.filter { it.startsWith("$date|") && !(it.startsWith("$date|$symbol|last:")) }.toMutableSet()
        state?.let { keep += "$date|$symbol|last:${it.name}" }
        fired?.let { keep += "$date|$symbol|${it.name}" }
        return keep
    }
}
