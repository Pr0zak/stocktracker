package com.stocktracker.app.ui.marketscan

import java.util.Locale
import kotlin.math.roundToInt

/**
 * SWT-4 — the words that turn a raw metric into a reading, without turning a rank into a grade.
 *
 * "RSI 81.4" is a number the reader has to already know the distribution to use. "RSI 81.4 (96th
 * percentile of 3,101 scanned)" is a read. Everything here is a pure function so the honesty rules
 * are unit-testable rather than buried in a composable, exactly like [MarketScanProvenance].
 *
 * FOUR RULES, and all four exist because the alternative is a confident sentence about the market
 * assembled out of a missing number.
 *
 *  1. **A null percentile renders NOTHING.** Not "0th", not "—th percentile", and never `?: 0.0`.
 *     Null means the ranking pass has not run for that night, or too little of the market could be
 *     measured on that metric, or this name had no value to rank. 0.0 is a different and confident
 *     claim — "the lowest measured value in the market that night" — and it is a claim we would be
 *     making about precisely the names we could not measure. The raw value is rendered alone.
 *  2. **A rank is never printed bare.** "96" reads as a score out of a hundred, and there is no
 *     scoring here: high RSI is not "good", the 99th percentile of average daily range is the most
 *     volatile name in the market rather than the best one. The word "percentile" and the
 *     population it is a percentile OF travel with the number, always.
 *  3. **The denominator is the server's, or it is words.** [MarketScanResponse.percentilesOver] is
 *     the count the ranks were actually computed over — the whole night, not the filtered slice.
 *     When it is absent we say "of the night's scan" rather than substitute a count we have (the
 *     rows on screen, the total matching a filter), because a percentile labelled with the wrong
 *     population is a false statement dressed as provenance. When it is small — a `limit`ed smoke
 *     run ranks over 50 names — printing it is the whole defence: "98th percentile of 50 scanned"
 *     cannot be misread as a statement about the market the way a bare "98th percentile" can.
 *  4. **An impossible rank is refused, not clamped into shape.** A percentile outside 0..100, or a
 *     NaN, is a broken producer; rendering "105th percentile" or silently pinning it to 100 both
 *     publish a number nobody computed. It renders as no rank at all.
 */
object MetricRank {

    /** What an absent value renders as, matching [com.stocktracker.app.util.Formatting]. */
    const val NA: String = "—"

    /**
     * The percentile as a usable number, or null.
     *
     * Tolerates a hair either side of the ends (0.0 and 100.0 are produced by rounding a
     * `(position)/(n-1)*100` scale, and a float can land at 100.00000000000001) and refuses anything
     * further out. Refusing rather than clamping: see rule 4.
     */
    fun usable(pctile: Double?): Double? {
        val p = pctile ?: return null
        if (!p.isFinite()) return null
        if (p < -0.05 || p > 100.05) return null
        return p.coerceIn(0.0, 100.0)
    }

    /**
     * "96th", "1st", "22nd", "0th". Null when there is no rank to name.
     *
     * "0th" is deliberate and correct: a real 0.0 means this name held the lowest measured value in
     * the night's cross-section, which is a measurement. It is only ever reached from a non-null
     * percentile — the absent case never gets this far.
     */
    fun ordinal(pctile: Double?): String? {
        val p = usable(pctile) ?: return null
        val n = p.roundToInt()
        val suffix = if (n % 100 in 11..13) {
            "th"
        } else {
            when (n % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$n$suffix"
    }

    /**
     * "96th percentile of 3,101 scanned", or "96th percentile of the night's scan" when the server
     * did not say how many names it ranked. Null when there is no rank — the caller then renders the
     * raw value alone rather than a sentence with a hole in it.
     */
    fun label(pctile: Double?, scannedOver: Int? = null): String? {
        val ord = ordinal(pctile) ?: return null
        // A denominator of zero or less cannot have produced a rank, so it is treated as unsaid
        // rather than printed: "96th percentile of 0 scanned" is a sentence that refutes itself.
        val over = scannedOver?.takeIf { it > 0 }
        return if (over != null) {
            "$ord percentile of ${count(over)} scanned"
        } else {
            "$ord percentile of the night's scan"
        }
    }

    /**
     * The tight form for a table cell: "96th pctile". Still not a bare number (rule 2), and still
     * null when unranked. The population belongs beside it on the screen — see
     * [MarketScanUiState.rankFooter] for the one line that carries it for a whole list.
     */
    fun short(pctile: Double?): String? = ordinal(pctile)?.let { "$it pctile" }

    /**
     * "1.40× (96th percentile of 3,101 scanned)" — the value first, because the rank is a rank OF
     * something and the reader needs both.
     *
     * [valueText] absent or blank renders as [NA] ALONE: with no measurement there is nothing for a
     * rank to describe, and a lone "(96th percentile)" beside a dash invites the reader to supply
     * the missing number themselves.
     */
    fun line(valueText: String?, pctile: Double?, scannedOver: Int? = null): String {
        val v = valueText?.trim().orEmpty()
        if (v.isEmpty() || v == NA) return NA
        val l = label(pctile, scannedOver) ?: return v
        return "$v ($l)"
    }

    /**
     * The rank as a 0..1 fraction for a bar, or NULL when there is no rank.
     *
     * Null rather than 0f on purpose: a bar drawn at zero width is indistinguishable from "worst in
     * the market", so the caller draws NO BAR. Same defect as "0th percentile", in pixels.
     */
    fun fraction(pctile: Double?): Float? = usable(pctile)?.let { (it / 100.0).toFloat() }

    private fun count(v: Int): String = String.format(Locale.US, "%,d", v)
}
