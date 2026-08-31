package com.stocktracker.app.ui.marketscan

import com.stocktracker.app.util.agePhrase
import java.util.Locale

/**
 * The words that stop a once-a-night sweep from reading as a live view of the market.
 *
 * Pure functions, no Compose, so the honesty rules are unit-testable rather than buried in a
 * composable. Three rules they exist to enforce:
 *
 *  1. **An unknown count is never printed as a number.** A missing `scanned` means the server did
 *     not say; "0 scanned" would mean the sweep ran and found nothing, which is a market claim.
 *  2. **`fetch_failed` and `too_short` are never summed.** They are different facts about different
 *     things — the network vs. the age of a listing — and a combined "34 problems" tells the reader
 *     nothing about which one they have.
 *  3. **The age is always available.** The scan is hours old by construction; a screen that omits
 *     that is showing last night's cross-section as though it were today's tape.
 */
object MarketScanProvenance {

    /** "3,113 of 3,147 scanned". Degrades to whichever half is known, or null when neither is. */
    fun coverage(scanned: Int?, universeSize: Int?): String? = when {
        scanned != null && universeSize != null -> "${n(scanned)} of ${n(universeSize)} scanned"
        scanned != null -> "${n(scanned)} scanned"
        universeSize != null -> "${n(universeSize)} in the universe"
        else -> null
    }

    /**
     * "0 fetch-failed · 34 too short" — the two shortfall counters, side by side and never added.
     *
     * A known zero IS printed. "0 fetch-failed" is the reassurance that the run was healthy, and
     * hiding it would make a healthy run and an unreported one look identical. Only an actually
     * absent counter is dropped.
     */
    fun shortfall(fetchFailed: Int?, tooShort: Int?): String? {
        val parts = buildList {
            fetchFailed?.let { add("${n(it)} fetch-failed") }
            tooShort?.let { add("${n(it)} too short") }
        }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    /** The night the rows are from ("20260821" → "2026-08-21"). Null when nothing is stored yet. */
    fun asOfLabel(asOf: String?): String? {
        val s = asOf?.trim().orEmpty()
        if (s.isEmpty()) return null
        return if (s.length == 8 && s.all { it.isDigit() }) {
            "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}"
        } else {
            s
        }
    }

    /**
     * How old the reading is, from the server's `generated_at` (epoch SECONDS).
     *
     * A future timestamp is clamped rather than rendered as a negative age — that means a clock
     * moved, not that the scan is from tomorrow.
     */
    fun age(generatedAtEpochSeconds: Double?, nowMs: Long): String? {
        val g = generatedAtEpochSeconds ?: return null
        if (!g.isFinite() || g <= 0.0) return null
        val ms = (g * 1000.0).toLong()
        return agePhrase((nowMs - ms).coerceAtLeast(0L), ms)
    }

    /**
     * The whole provenance line, e.g.
     * "Nightly scan · 2026-08-21 · 3h ago · 3,113 of 3,147 scanned · 0 fetch-failed · 34 too short".
     *
     * Never null and never empty: when nothing at all is known it says so outright, because a blank
     * where the provenance should be is exactly how a stale reading passes for a fresh one.
     */
    fun summary(
        asOf: String?,
        generatedAtEpochSeconds: Double?,
        scanned: Int?,
        universeSize: Int?,
        fetchFailed: Int?,
        tooShort: Int?,
        universeStale: Boolean?,
        nowMs: Long,
    ): String {
        val parts = buildList {
            add("Nightly scan")
            asOfLabel(asOf)?.let { add(it) }
            age(generatedAtEpochSeconds, nowMs)?.let { add(it) }
            coverage(scanned, universeSize)?.let { add(it) }
            shortfall(fetchFailed, tooShort)?.let { add(it) }
            // A stale universe is a claim about the MEMBERSHIP list, not about the scan: the sweep
            // may be minutes old and still be ranking a month-old idea of what is listed.
            if (universeStale == true) add("universe stale")
        }
        return if (parts.size == 1) "Nightly scan · coverage unknown" else parts.joinToString(" · ")
    }

    /**
     * "58.4% above the 50-day · 67.6% above the 200-day · 3,113 names" — or NULL.
     *
     * Null in three cases, all of which must render as nothing at all rather than as a reading:
     * no breadth payload, a payload whose `available` is false (no scan stored), and a payload with
     * nothing measured on it. `pct_above_sma50: 0` is the most bearish breadth reading that exists,
     * and it must never be manufactured out of an empty table — so each half is printed only if the
     * server actually sent it, and a row count with no percentages beside it is not a reading.
     */
    fun breadthLine(
        available: Boolean?,
        pctAboveSma50: Double?,
        pctAboveSma200: Double?,
        rows: Int?,
        /**
         * near_52w_high - near_52w_low. Null on any night the server could not measure both sides,
         * which includes every night stored before the low side existed — those cannot be
         * backfilled, and a 0 there would read as "highs and lows are balanced" when in fact nobody
         * counted the lows.
         */
        highLowDiff: Int? = null,
    ): String? {
        if (available != true) return null
        val parts = buildList {
            pctAboveSma50?.takeIf { it.isFinite() }?.let { add(pct(it) + " above the 50-day") }
            pctAboveSma200?.takeIf { it.isFinite() }?.let { add(pct(it) + " above the 200-day") }
            // Signed on purpose. The reading that matters is a NEGATIVE one — more names sitting
            // near their lows than near their highs while the index holds up — and an unsigned
            // count would bury exactly that.
            highLowDiff?.let {
                add(
                    when {
                        it > 0 -> "+$it more near highs than lows"
                        it < 0 -> "${-it} more near lows than highs"
                        else -> "highs and lows evenly matched"
                    },
                )
            }
        }
        if (parts.isEmpty()) return null
        val counted = rows?.takeIf { it > 0 }?.let { parts + "${n(it)} names" } ?: parts
        return counted.joinToString(" · ")
    }

    private fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v)

    private fun n(v: Int): String = String.format(Locale.US, "%,d", v)
}

/** A sortable metric, named exactly as the scan store spells it. */
data class MarketScanSort(val key: String, val label: String)

/**
 * The sorts worth offering. Keys are column names in the server's scan table — an unrecognised one
 * is refused there rather than silently ignored, so these are spelled to match, not invented.
 *
 * BARE KEYS, with no "-" baked in: the direction is a separate control now (see [scanSortParam]),
 * and a chip that hardcoded one end would be a second, contradictory source of truth for it. The
 * labels are the metric's plain name for the same reason — "Calmest (low ATR%)" is a claim about
 * which end of the ordering you are looking at, and it becomes false the moment the toggle flips.
 */
val MARKET_SCAN_SORTS: List<MarketScanSort> = listOf(
    MarketScanSort("rel_strength_3mo", "Rel. strength (3mo)"),
    MarketScanSort("mom_20d", "Momentum (20d)"),
    MarketScanSort("mom_60d", "Momentum (60d)"),
    MarketScanSort("adx14", "Trend strength (ADX)"),
    MarketScanSort("rsi14", "RSI (14)"),
    MarketScanSort("adr20_pct", "Avg daily range"),
    MarketScanSort("atr14_pct", "ATR (14) %"),
    MarketScanSort("rel_volume", "Relative volume"),
    MarketScanSort("dollar_volume_20d", "Dollar volume (20d)"),
    MarketScanSort("pct_off_52w_high", "Off 52-week high"),
    MarketScanSort("ema20_slope_pct", "20-EMA slope"),
)
