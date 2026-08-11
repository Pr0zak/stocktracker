package com.stocktracker.app.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How old the numbers on screen are, and whether that age is a problem yet.
 *
 * Every price surface in this app falls back to the last cached quote when a fetch fails — the
 * watchlist does it in `loadQuotes`, the detail screen does it in `loadQuote`, and the repository
 * does it a third time as stale-while-error. Each of those is the right call on its own: a blank row
 * is worse than a slightly old one. Together they mean a dead network looks exactly like a calm
 * market, because the last thing anyone changed was the number, and the number is still there.
 *
 * [asOfEpochMs] is when the app last SUCCEEDED in talking to the price source, not when the exchange
 * printed the trade. That is the honest basis for a "last updated" line, and it is the thing a
 * refresh can actually move: over a weekend the quote is Friday's close either way, but re-reading it
 * on Sunday genuinely does update our knowledge of it.
 */
data class Freshness(
    val asOfEpochMs: Long,
    /** Age in ms, or null when we have no timestamp at all — which is NOT the same as "new". */
    val ageMs: Long?,
    val stale: Boolean,
    /** "Updated 4m ago", "Updated just now", "Never updated". Reads as a sentence on its own. */
    val label: String,
    /**
     * Just the age — "4m ago", "just now", "never" — for callers writing their own sentence.
     *
     * A failure needs this rather than [label]: "Updated just now · refresh failed" states two
     * opposing things and leaves the reader to work out which one governs. "Refresh failed · last
     * read 4m ago" says the same facts in an order that resolves.
     */
    val since: String,
) {
    val known: Boolean get() = ageMs != null
}

/**
 * While the tape moves, a five-minute-old price is a different price — that is the whole reason to
 * show an age at all.
 */
const val MOVING_STALE_MS: Long = 5 * 60 * 1000L

/**
 * When the market is shut, the last print IS the current price, so flagging it would cry wolf every
 * evening and all weekend. Twelve hours still catches the case that matters: the app has not reached
 * the source since before the last session ended, so a "+1.2% Today" beside it is last session's.
 */
const val CLOSED_STALE_MS: Long = 12 * 60 * 60 * 1000L

fun staleAfterMs(phase: MarketPhase): Long =
    if (phase == MarketPhase.CLOSED) CLOSED_STALE_MS else MOVING_STALE_MS

private val ABSOLUTE = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

/**
 * Turn a fetch timestamp into something a person can act on.
 *
 * A zero/absent timestamp is reported as stale with an explicit "Never updated", never as fresh —
 * an unknown age rendered as a blank is precisely how absent data starts passing for current data.
 */
fun freshnessOf(
    asOfEpochMs: Long,
    nowMs: Long,
    phase: MarketPhase,
    zone: ZoneId = ZoneId.systemDefault(),
): Freshness {
    if (asOfEpochMs <= 0L) {
        return Freshness(asOfEpochMs = 0L, ageMs = null, stale = true, label = "Never updated", since = "never")
    }
    // A timestamp in the future means a clock changed under us, not that the data is from the
    // future. Clamp rather than render "Updated -3m ago".
    val age = (nowMs - asOfEpochMs).coerceAtLeast(0L)
    val since = agePhrase(age, asOfEpochMs, zone)
    return Freshness(
        asOfEpochMs = asOfEpochMs,
        ageMs = age,
        stale = age > staleAfterMs(phase),
        label = "Updated $since",
        since = since,
    )
}

/** The age alone ("just now", "4m ago", "Aug 8, 4:01 PM") — for callers writing their own sentence. */
fun agePhrase(ageMs: Long, asOfEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val secs = ageMs / 1000
    val mins = secs / 60
    val hours = mins / 60
    return when {
        secs < 45 -> "just now"
        mins < 60 -> "${mins}m ago"
        // Past a day, "31h ago" stops being something anyone can place. Give the clock time instead.
        hours < 24 -> "${hours}h ago"
        else -> runCatching {
            ABSOLUTE.format(Instant.ofEpochMilli(asOfEpochMs).atZone(zone))
        }.getOrDefault("${hours / 24}d ago")
    }
}

/**
 * The freshness of a whole list, from each row's timestamp.
 *
 * Deliberately the OLDEST row, not the newest: this line speaks for everything under it, and one
 * symbol that stopped updating half an hour ago is exactly what it exists to surface. Reporting the
 * newest would let a single healthy row vouch for eleven dead ones.
 *
 * Rows with no timestamp at all are counted as unknown rather than skipped — a watchlist where
 * nothing has ever loaded must not render as "Updated just now" because the empty set had no minimum.
 */
fun listFreshness(
    asOfEpochMsPerRow: List<Long>,
    nowMs: Long,
    phase: MarketPhase,
    zone: ZoneId = ZoneId.systemDefault(),
): Freshness {
    val known = asOfEpochMsPerRow.filter { it > 0L }
    if (known.isEmpty()) {
        return Freshness(asOfEpochMs = 0L, ageMs = null, stale = true, label = "Never updated", since = "never")
    }
    return freshnessOf(known.min(), nowMs, phase, zone)
}

/** How many rows are individually past the staleness line — for "3 of 12 out of date". */
fun staleRowCount(asOfEpochMsPerRow: List<Long>, nowMs: Long, phase: MarketPhase): Int {
    val limit = staleAfterMs(phase)
    return asOfEpochMsPerRow.count { it <= 0L || nowMs - it > limit }
}
