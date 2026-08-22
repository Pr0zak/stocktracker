package com.stocktracker.app.ui.watchlist

import com.stocktracker.app.data.remote.DipCounts
import com.stocktracker.app.data.remote.DipReject
import com.stocktracker.app.data.remote.ScanLatest
import com.stocktracker.app.data.remote.ScanResult

/**
 * SWT-5 — what the dip radar is allowed to SAY, given what it actually knows.
 *
 * The screen used to have one empty state. `scan?.results ?: emptyList()` turned an unreachable scan
 * service into "No dips right now — nothing you track is notably off its highs", which is a claim
 * about the market made out of a network error. Reassurance is the one message you must never emit
 * by default, because it is the message that stops the user looking.
 *
 * So there are four outcomes, and only ONE of them is allowed to be calming:
 *   - [Unreachable] — the fetch failed. We hold no scan. Offer a retry, say nothing about dips.
 *   - [NotConfigured] — there is no signals service to ask. Point at the setting.
 *   - [NoScan] — the server answered and told us it has no scan (`scan_available == false`).
 *   - [Ready] — a scan ran. Only here is "nothing qualified" ours to say, and it comes with the
 *     counters that make it checkable.
 */
sealed interface DipRadarState {

    /** Still asking. Not an empty list. */
    data object Loading : DipRadarState

    /** The request threw, or returned nothing decodable. [message] is the exception's own words. */
    data class Unreachable(val message: String?) : DipRadarState

    /** No Signals service URL configured — nothing was ever asked. */
    data object NotConfigured : DipRadarState

    /** The server has no scan on disk. [reason] is its explanation, when it gave one. */
    data class NoScan(val reason: String?) : DipRadarState

    /**
     * A scan ran. [counts] may still carry nulls (a stored scan older than the counters), and
     * [rejectsAvailable] is false for a scan that predates the reject audit — in which case the
     * three lists are empty because nothing was RECORDED, not because nothing was rejected.
     */
    data class Ready(
        val dips: List<DipEntry>,
        val nearMiss: List<DipReject>,
        val nowhereNear: List<DipReject>,
        val unmeasured: List<DipReject>,
        val counts: DipCounts,
        val rejectsAvailable: Boolean,
    ) : DipRadarState
}

object DipRadar {

    /** Most-severe first. Anything the server invents later sorts to the end rather than vanishing. */
    val TIER_ORDER = listOf("mega_dip", "below_line", "oversold", "pullback_10", "pullback_5")

    fun entries(results: List<ScanResult>?): List<DipEntry> =
        results.orEmpty()
            .mapNotNull { r ->
                r.dip?.let { DipEntry(r.symbol.removeSuffix("-USD"), it, r.pctOffRecentHigh, r.pctOff52wHigh) }
            }
            .sortedBy { TIER_ORDER.indexOf(it.tier).let { i -> if (i < 0) 99 else i } }

    /**
     * Classify one fetch attempt. [error] is whatever the call threw; [scan] is what it returned.
     *
     * A null scan with no exception still means we hold nothing — [SignalsApiService.latestScan]
     * returns null on a blank base URL — so it is never treated as an empty market.
     */
    fun state(scan: ScanLatest?, error: Throwable?, configured: Boolean): DipRadarState = when {
        !configured -> DipRadarState.NotConfigured
        error != null -> DipRadarState.Unreachable(error.message)
        scan == null -> DipRadarState.Unreachable(null)
        !scan.hasScan -> DipRadarState.NoScan(scan.unavailableReason)
        else -> {
            val dips = entries(scan.results)
            DipRadarState.Ready(
                dips = dips,
                nearMiss = scan.dipRejects?.nearMiss.orEmpty(),
                nowhereNear = scan.dipRejects?.nowhereNear.orEmpty(),
                unmeasured = scan.dipRejects?.unmeasured.orEmpty(),
                counts = counts(scan, dips.size),
                rejectsAvailable = scan.dipRejects != null,
            )
        }
    }

    /**
     * The server's counters, with only the two we can derive ourselves filled in when it is an older
     * scan: how many rows it holds, and how many of them carry a dip tier. The other three stay NULL
     * — a scan written before the audit recorded no near-misses, and printing "0 near misses" about
     * it would answer a question nobody measured.
     */
    fun counts(scan: ScanLatest, qualified: Int): DipCounts {
        val server = scan.dipCounts
        return DipCounts(
            scanned = server?.scanned ?: scan.results?.size,
            qualified = server?.qualified ?: qualified,
            nearMiss = server?.nearMiss,
            nowhereNear = server?.nowhereNear,
            unmeasured = server?.unmeasured,
        )
    }

    /**
     * "0 of 34 scanned qualified" — the sentence that makes an empty dip list checkable. Degrades to
     * whichever half is known and disappears entirely when neither is.
     */
    fun coverage(c: DipCounts?): String? = when {
        c == null -> null
        c.qualified != null && c.scanned != null -> "${c.qualified} of ${c.scanned} scanned qualified"
        c.qualified != null -> "${c.qualified} qualified"
        c.scanned != null -> "${c.scanned} scanned"
        else -> null
    }

    /**
     * "3 near miss · 30 nowhere near · 1 unmeasured" — each counter printed only if it was reported.
     * A known zero IS printed: "0 unmeasured" is the evidence the scan was complete, and hiding it
     * makes a complete scan and an unreported one look identical.
     */
    fun breakdown(c: DipCounts?): String? {
        if (c == null) return null
        val parts = buildList {
            c.nearMiss?.let { add("$it near miss") }
            c.nowhereNear?.let { add("$it nowhere near") }
            c.unmeasured?.let { add("$it unmeasured") }
        }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    /**
     * The line that must appear EVEN WHEN there are dips to show: some names could not be measured,
     * so the list in front of the user is incomplete. Null when the count is zero (nothing to warn
     * about) and null when it is absent (nothing measured about the measuring — see [staleNote]).
     */
    fun incompleteNote(c: DipCounts?): String? {
        val n = c?.unmeasured ?: return null
        if (n <= 0) return null
        return if (n == 1) {
            "1 name couldn't be measured — this list may be incomplete."
        } else {
            "$n names couldn't be measured — this list may be incomplete."
        }
    }

    /**
     * Shown instead of the reject section when the stored scan predates it. Explicitly NOT zeros:
     * "nothing was rejected" and "we never recorded what was rejected" are different claims.
     */
    fun staleNote(rejectsAvailable: Boolean): String? =
        if (rejectsAvailable) {
            null
        } else {
            "This scan ran before the reject audit existed, so there's no record of what it turned down."
        }
}
