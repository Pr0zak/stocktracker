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

/**
 * SWT-14 — the dip strip's non-Ready states, compressed to something a dashboard strip can carry.
 *
 * The strip on the watchlist rendered exactly one state: a list of dips. Everything else — an
 * unreachable scan service, no configured service, a server holding no scan — rendered as the strip
 * not being there, which looks precisely like a market with no dips in it. That is the reassuring
 * reading, and it is the one that stops the user looking.
 *
 * This is a compact NOTICE, not the full [DipListScreen] error panel: a title, the source's own
 * words when it gave any, and whether trying again could plausibly help.
 */
data class DipStripNote(
    val title: String,
    /** The scan service's / the exception's own words, when there were any. Never invented. */
    val detail: String?,
    val tone: DipStripTone,
    val retryable: Boolean,
)

/** How loud the strip's notice is. [WORKING] is a fetch in flight, which is never a warning. */
enum class DipStripTone { WORKING, WARN, INFO }

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

    /**
     * The strip's notice for a state that is NOT [DipRadarState.Ready], or null when it is Ready —
     * where the dips (or [calm]) are the content and there is no notice to give.
     *
     * Nothing returned from here may mention dips being absent. We hold no scan in any of these
     * states, so "there are no dips" is not ours to say; see [calm], which is the only place it is.
     */
    fun strip(state: DipRadarState): DipStripNote? = when (state) {
        is DipRadarState.Loading -> DipStripNote(
            title = "Checking the latest scan…",
            detail = null,
            tone = DipStripTone.WORKING,
            retryable = false,
        )
        is DipRadarState.Unreachable -> DipStripNote(
            title = "Couldn't reach the scan service",
            detail = state.message?.takeIf { it.isNotBlank() },
            tone = DipStripTone.WARN,
            retryable = true,
        )
        is DipRadarState.NotConfigured -> DipStripNote(
            title = "Dip radar isn't set up",
            detail = "Set the Signals service URL in Settings.",
            tone = DipStripTone.INFO,
            retryable = false,
        )
        is DipRadarState.NoScan -> DipStripNote(
            title = "No scan has run yet",
            // The server's explanation, in its words, exactly as the full radar screen prints it.
            detail = state.reason?.takeIf { it.isNotBlank() }?.replaceFirstChar { c -> c.uppercase() },
            tone = DipStripTone.INFO,
            retryable = true,
        )
        is DipRadarState.Ready -> null
    }

    /**
     * The one reassuring sentence in this feature, and the ONLY function that may produce it.
     *
     * It is reachable from [DipRadarState.Ready] and nowhere else: a scan ran, we are holding its
     * results, and none of them qualified. Every other state returns null, because in every other
     * state we hold no scan and "nothing is off its highs" would be a claim about the market built
     * out of a network error.
     */
    fun calm(state: DipRadarState): String? =
        (state as? DipRadarState.Ready)
            ?.takeIf { it.dips.isEmpty() }
            ?.let { "No dips right now — nothing you track is notably off its highs." }

    /**
     * The dip fact compressed for the collapsed market-context line. Null when there is nothing
     * honest to fit in two words — a strip that is still loading, or a radar nobody configured, has
     * nothing to contribute to a one-line summary, and guessing would put a market claim in it.
     */
    fun chip(state: DipRadarState): String? = when (state) {
        is DipRadarState.Ready ->
            if (state.dips.isEmpty()) "no dips"
            else "${state.dips.size} dip" + (if (state.dips.size == 1) "" else "s")
        is DipRadarState.Unreachable -> "dips unavailable"
        is DipRadarState.NoScan -> "no scan yet"
        is DipRadarState.Loading, is DipRadarState.NotConfigured -> null
    }

    /** What the strip should hold after a refresh, and whether the held list is stale. */
    data class StripUpdate(val state: DipRadarState, val stale: String?)

    /**
     * Decide whether a refreshed radar state REPLACES the one on screen, or whether the previous
     * reading is kept with a staleness note beside it.
     *
     * The bug this exists to prevent: the view model reassigned the state unconditionally, so one
     * failed request after a successful scan replaced Ready with Unreachable — a working dip list
     * vanished and was replaced by an error, discarding information the app already held because of
     * a transient blip. Every other card here (gate, regime, heatmap) keeps its last reading and
     * sets an error beside it. This is that rule, made pure so it can be tested.
     *
     * ONLY [DipRadarState.Unreachable] is held through, and the exclusions are deliberate:
     *
     *  * [DipRadarState.NotConfigured] is not a failure. The user removed the backend URL, and
     *    continuing to show dips from a service they have disconnected would be stale data with no
     *    way to refresh it — worse than showing nothing.
     *  * [DipRadarState.NoScan] is the server ANSWERING, not failing. It told us it holds no scan,
     *    which is newer information than the scan we were holding, so it wins.
     *  * [DipRadarState.Loading] replaces nothing; a refresh in flight is not a reason to drop a
     *    list that is still perfectly readable.
     */
    fun holdThroughBlip(previous: DipRadarState, incoming: DipRadarState): StripUpdate {
        val held = previous as? DipRadarState.Ready ?: return StripUpdate(incoming, null)
        if (incoming is DipRadarState.Loading) return StripUpdate(held, null)
        if (incoming !is DipRadarState.Unreachable) return StripUpdate(incoming, null)
        return StripUpdate(held, incoming.message ?: "Couldn't refresh the scan.")
    }
}
