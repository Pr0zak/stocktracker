package com.stocktracker.app.ui.detail

import com.stocktracker.app.data.remote.PlanResponse
import java.util.Locale

/**
 * SWT-3 — turning the server's chase read into one line the entry-plan card can print.
 *
 * The card already showed the analyst's entry zone a few millimetres from the live price, which is a
 * warning only to a reader who does the division. The backend now does it and names the answer; this
 * decides what the screen says about it, and — more importantly — when it says NOTHING.
 *
 * Three rules, in order of how much money they save:
 *
 *  1. ABSENT IS SILENT. A null status with a null percent means the read could not be taken: the
 *     analyst declined to commit to a zone, or the quote feed was down. That renders as no line at
 *     all — not "0%", not "ok". A confident "not chasing" built out of a missing key is the exact
 *     defect that shipped "Stop $0 · target $0" onto a money decision.
 *  2. ONLY THE EXPENSIVE CASE SHOUTS. `chase_too_deep` is the one state where the trade the user
 *     believes they are taking has stopped existing, so it gets the alarm treatment WITH the number.
 *     `in_zone` and `below_zone` are the plan working; they get a quiet line. Crying wolf on those
 *     teaches the reader to swipe past the one banner that mattered.
 *  3. NEVER INVENT THE NUMBER. Every string here is built from values that arrived; a status with no
 *     percent prints without one rather than reaching for a default.
 */
enum class ChaseTone {
    /** You are paying materially over the plan's own zone. */
    ALARM,

    /** The read itself is broken (an inverted zone) — say so, don't imply a price verdict. */
    CAUTION,

    /** Price is inside or under the zone: the plan is working. */
    CALM,

    /** A number worth showing that carries no verdict. */
    NEUTRAL,
}

/** One line for the card: [headline] always, [detail] only when there is something true to add. */
data class ChaseBanner(val headline: String, val detail: String?, val tone: ChaseTone)

/**
 * The chase read as it travels through UI state — raw numbers, formatted at the card.
 *
 * [from] returns null when the server said nothing at all (every field null, or a backend older than
 * SWT-3). That null is load-bearing: it is the difference between "we looked and can't say" and a
 * fabricated reassurance, and both must render as silence rather than as a number.
 */
data class ChaseState(
    val status: String?,
    val pct: Double?,
    val price: Double?,
    val warning: String?,
) {
    companion object {
        fun from(resp: PlanResponse?): ChaseState? {
            if (resp == null) return null
            if (resp.chaseStatus == null && resp.chasePct == null && resp.chaseWarning == null) return null
            return ChaseState(resp.chaseStatus, resp.chasePct, resp.chasePrice, resp.chaseWarning)
        }
    }
}

object ChaseRead {

    const val TOO_DEEP = "chase_too_deep"
    const val OK = "ok"
    const val IN_ZONE = "in_zone"
    const val BELOW_ZONE = "below_zone"

    /**
     * The line to print, or null when the honest thing is to print nothing.
     *
     * [priceLabel] is the already-formatted quote the server took the read against (e.g. "$74.02"),
     * so the card can show its own arithmetic instead of asking the reader to trust a bare percent.
     * [warning] is set only when the zone itself is malformed.
     */
    fun banner(status: String?, pct: Double?, priceLabel: String?, warning: String?): ChaseBanner? {
        // A malformed zone outranks everything: percentages computed off inverted bounds look
        // perfectly reasonable and mean nothing, so the card reports the defect, not a verdict.
        if (!warning.isNullOrBlank()) {
            return ChaseBanner("No chase read", warning, ChaseTone.CAUTION)
        }
        val at = priceLabel?.let { "at $it now" }
        return when (status) {
            TOO_DEEP -> ChaseBanner(
                pct?.let { "Chasing — ${above(it)} above the entry zone" } ?: "Chasing — above the entry zone",
                listOfNotNull(at, "the plan's reward and risk both move against you up here")
                    .joinToString(" · "),
                ChaseTone.ALARM,
            )
            // Above the zone, but inside the band that is just quote noise. Worth stating plainly;
            // not worth a warning, or the warnings stop being read.
            OK -> ChaseBanner(
                pct?.let { "${above(it)} above the entry zone" } ?: "Just above the entry zone",
                at,
                ChaseTone.NEUTRAL,
            )
            IN_ZONE -> ChaseBanner("In the entry zone", at, ChaseTone.CALM)
            BELOW_ZONE -> ChaseBanner("Below the entry zone — cheaper than planned", at, ChaseTone.CALM)
            // No status, but a measured distance: the analyst gave a zone top and no floor, so "in
            // the zone" and "under it" are not distinguishable. State the distance, claim nothing.
            null -> pct?.let { ChaseBanner(underTop(it), at, ChaseTone.NEUTRAL) }
            // A status this build doesn't know (a newer server). Show the number if there is one and
            // stay silent rather than guessing what the label meant.
            else -> pct?.let { ChaseBanner(vsTop(it), at, ChaseTone.NEUTRAL) }
        }
    }

    /** "4.6%" — one decimal, locale-safe, no sign (the surrounding words carry the direction). */
    private fun above(pct: Double): String = String.format(Locale.US, "%.1f%%", kotlin.math.abs(pct))

    private fun underTop(pct: Double): String = when {
        pct > 0.0 -> "${above(pct)} above the entry-zone top"
        // -0.04 rounds to "0.0% under", which reads as a measurement rather than "it is sitting there".
        pct > -0.05 -> "At the top of the entry zone"
        else -> "${above(pct)} under the entry-zone top"
    }

    private fun vsTop(pct: Double): String =
        if (pct > 0.0) "${above(pct)} above the entry zone" else underTop(pct)
}
