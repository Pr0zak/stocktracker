package com.stocktracker.app.ui.watchlist

import com.stocktracker.app.data.remote.GateLeg
import com.stocktracker.app.data.remote.GateResponse
import java.util.Locale

/**
 * SWT-13 — what the five-leg gate is ALLOWED TO SAY, given what it actually measured.
 *
 * The regime banner beside this one is the narrative read; the gate is the checkable half of the same
 * question, and "checkable" is the whole reason it exists — a verdict you can argue with because the
 * numbers behind it are printed next to it.
 *
 * `passed` is THREE-VALUED and each value gets its own outcome here, because that is the one place
 * this can go wrong:
 *   - true  → [GateVerdict.OPEN]
 *   - false → [GateVerdict.SHUT], and the failing legs are NAMED
 *   - null  → [GateVerdict.UNMEASURED] — a leg could not be read and none failed. This is NOT a shut
 *             gate, it never shares the shut colour, and it names what went unmeasured instead.
 * and `available == false` is a fourth outcome ([GateVerdict.UNAVAILABLE]): nothing was measured at
 * all, so there is no verdict to render, only the admission.
 *
 * Collapsing null into false would assert a bearish market on the strength of a failed fetch, which
 * is the exact defect the backend's gate.py, the DTO layer and this file all refuse to make.
 */
enum class GateVerdict {
    /** All five legs explicitly passed. */
    OPEN,

    /** At least one leg explicitly failed. */
    SHUT,

    /** A leg could not be measured and none failed. Not a verdict — an absence of one. */
    UNMEASURED,

    /** The evaluation measured nothing at all (`available == false`). */
    UNAVAILABLE,
}

/** How one leg's `ok` renders. [UNKNOWN] is a dash — never a cross, which would be a claim. */
enum class LegMark { PASS, FAIL, UNKNOWN }

/**
 * The card's verdict line.
 *
 * [detail] names the legs behind the verdict, which is the difference between a red light and a
 * reason. [chip] is the same fact compressed for the collapsed market-context summary, so the strip
 * and the card cannot disagree about whether the gate is open.
 */
data class GateSummary(
    val verdict: GateVerdict,
    val headline: String,
    val detail: String?,
    val chip: String,
)

object GateRead {

    /**
     * The verdict, or null when there is nothing to render at all (no response held yet).
     *
     * Null here means "the card has no reading" — the caller shows its loading/error state instead.
     * It never means "the gate is fine".
     */
    fun summary(resp: GateResponse?): GateSummary? {
        if (resp == null) return null
        // `available` gates everything else on the object: score is null, every leg's ok is null.
        // There is no verdict here to colour, only the admission that nothing was read.
        if (!resp.available) {
            return GateSummary(
                verdict = GateVerdict.UNAVAILABLE,
                // Worded apart from the UNMEASURED headline below on purpose: there, four legs were
                // read and one wasn't; here nothing was read at all. Same colourless treatment, but
                // a reader deserves to know which of the two they are looking at.
                headline = "No gate reading",
                detail = "Nothing was measured, so there is no reading — this is not a shut gate.",
                chip = "Gate unavailable",
            )
        }
        return when (resp.passed) {
            true -> GateSummary(
                verdict = GateVerdict.OPEN,
                headline = "Gate open",
                detail = passedDetail(resp.legs),
                chip = "Gate open",
            )
            false -> GateSummary(
                verdict = GateVerdict.SHUT,
                headline = "Gate shut",
                detail = failingDetail(resp),
                chip = "Gate shut",
            )
            // The one that must not read as a fail: nothing failed, something couldn't be read.
            null -> GateSummary(
                verdict = GateVerdict.UNMEASURED,
                headline = "Gate couldn't be measured",
                detail = unmeasuredDetail(resp),
                chip = "Gate unmeasured",
            )
        }
    }

    /** "All 5 conditions hold." — the count comes from the legs we were sent, never a hardcoded five. */
    private fun passedDetail(legs: List<GateLeg>): String? {
        if (legs.isEmpty()) return null
        return "All ${legs.size} conditions hold."
    }

    /**
     * Which legs failed, by name. `failing` is the server's own list; when it is absent (an older
     * history row) the legs themselves still carry `ok == false`, so fall back to those rather than
     * printing a verdict with nothing behind it.
     */
    private fun failingDetail(resp: GateResponse): String {
        val named = names(resp.failing, resp.legs)
            .ifEmpty { labels(resp.legs.filter { it.ok == false }) }
        return if (named.isEmpty()) {
            "At least one condition failed — the gate didn't name which."
        } else {
            "Failing: " + named.joinToString(", ")
        }
    }

    /** Which legs went unread. Same fallback, same refusal to name nothing and imply everything. */
    private fun unmeasuredDetail(resp: GateResponse): String {
        val named = names(resp.unmeasured, resp.legs)
            .ifEmpty { labels(resp.legs.filter { it.ok == null }) }
        return if (named.isEmpty()) {
            "A condition couldn't be read — the gate didn't name which. Nothing failed."
        } else {
            "Couldn't measure: " + named.joinToString(", ") + ". Nothing failed."
        }
    }

    /**
     * Keys → the human labels on the legs. A key with no matching leg prints as itself: an
     * unrecognised name is still the server telling us WHICH leg, and dropping it would shorten the
     * list into a lie.
     */
    private fun names(keys: List<String>?, legs: List<GateLeg>): List<String> =
        keys.orEmpty().map { k -> legs.firstOrNull { it.key == k }?.name?.takeIf { n -> n.isNotBlank() } ?: k }

    private fun labels(legs: List<GateLeg>): List<String> = legs.map { legLabel(it) }

    /** A leg's display name, degrading to its key. Never blank — a nameless row is unreadable. */
    fun legLabel(leg: GateLeg): String =
        leg.name.takeIf { it.isNotBlank() } ?: leg.key.takeIf { it.isNotBlank() } ?: "Unnamed leg"

    /** true → tick, false → cross, null → dash. The dash is load-bearing; see [LegMark]. */
    fun mark(ok: Boolean?): LegMark = when (ok) {
        true -> LegMark.PASS
        false -> LegMark.FAIL
        null -> LegMark.UNKNOWN
    }

    /**
     * "54.1 vs 55" — the number and the bar it had to clear, which is what makes the verdict
     * checkable. Each half is printed only if it arrived; both absent prints nothing rather than a
     * zero.
     */
    fun legValue(leg: GateLeg): String? {
        val v = leg.value
        val t = leg.threshold
        return when {
            v != null && t != null -> "${num(v)} vs ${num(t)}"
            v != null -> num(v)
            t != null -> "needs ${num(t)}"
            else -> null
        }
    }

    /** The 0-100 plotting aid, or null. Null whenever any leg was unmeasured — never averaged over a hole. */
    fun scoreText(resp: GateResponse?): String? =
        resp?.marketScore?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) }

    /**
     * "cached · 4m old" — how old the reading is when the server served it from its cache.
     *
     * Null when it was computed fresh, and null when the server didn't say how old: an unknown age
     * is not a young one.
     */
    fun cachedNote(resp: GateResponse?): String? {
        if (resp == null || !resp.cached) return null
        val s = resp.cachedAgeSeconds ?: return "cached"
        if (s < 0) return "cached"
        return "cached · " + when {
            s < 90 -> "${s}s old"
            s < 5400 -> "${s / 60}m old"
            else -> "${s / 3600}h old"
        }
    }

    /** Up to two decimals, trailing zeros trimmed, locale-safe ("55", "54.12", "-2.3"). */
    private fun num(v: Double): String {
        if (!v.isFinite()) return "—"
        val s = String.format(Locale.US, "%.2f", v)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }
}
