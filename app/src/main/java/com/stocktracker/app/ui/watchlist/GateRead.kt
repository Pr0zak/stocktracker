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
                headline = "No market-check reading",
                detail = "Nothing was measured, so there is no reading — this is not a failed check.",
                chip = "Market checks unavailable",
            )
        }
        return when (resp.passed) {
            true -> GateSummary(
                verdict = GateVerdict.OPEN,
                headline = "Market checks: all ${resp.legs.size.takeIf { it > 0 } ?: 5} pass",
                detail = passedDetail(resp.legs),
                chip = "Market checks pass",
            )
            false -> GateSummary(
                verdict = GateVerdict.SHUT,
                headline = shutHeadline(resp),
                detail = failingDetail(resp),
                chip = shutChip(resp),
            )
            // The one that must not read as a fail: nothing failed, something couldn't be read.
            null -> GateSummary(
                verdict = GateVerdict.UNMEASURED,
                headline = "Market checks: one couldn't be read",
                detail = unmeasuredDetail(resp),
                chip = "Market check incomplete",
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
        keys.orEmpty().map { k ->
            // The server's `failing`/`unmeasured` lists carry leg NAMES ("Breadth > 55%"); match on
            // either, and prefer the plain title of whichever leg it resolves to.
            val key = legs.firstOrNull { it.key == k || it.name == k }?.key ?: keyForName(k) ?: k
            plain(key)?.title ?: legs.firstOrNull { it.key == k }?.name?.takeIf { n -> n.isNotBlank() } ?: k
        }

    /** The server's leg names, for payloads that carry a `failing` list but no legs (older records). */
    fun keyForName(name: String): String? = when (name) {
        "SPY > 50-EMA" -> "spy_above_ema50"
        "QQQ > 50-EMA" -> "qqq_above_ema50"
        "Breadth > 55%" -> "breadth_55"
        "VIX < 20" -> "vix_under_20"
        "SPY 20-day momentum > 0" -> "spy_mom_20d"
        else -> null
    }

    private fun labels(legs: List<GateLeg>): List<String> = legs.map { legTitle(it) }

    // ------------------------------------------------------------------ plain language
    //
    // The server names its legs in trader shorthand ("SPY > 50-EMA", "Breadth > 55%"). Those names
    // are exact, and they stay in the payload, but they are not readable by someone who does not
    // already know what a 50-EMA or breadth is. Everything the app SHOWS goes through the titles
    // below; an unrecognised key still falls back to the server's own name rather than disappearing.

    /** A leg in plain words: what it checks, and what passing it means. */
    data class PlainLeg(val title: String, val meaning: String)

    fun plain(key: String?): PlainLeg? = when (key) {
        "spy_above_ema50" -> PlainLeg(
            "S&P 500 above its 50-day average",
            "The broad market's price is above its average of the last 50 trading days — it is trending up.",
        )
        "qqq_above_ema50" -> PlainLeg(
            "Nasdaq-100 above its 50-day average",
            "The big tech-heavy index is trending up the same way.",
        )
        "breadth_55" -> PlainLeg(
            "Most stocks in uptrends",
            "More than 55% of all stocks trade above their own 50-day average, so the rally is broad " +
                "rather than carried by a few giant companies.",
        )
        "vix_under_20" -> PlainLeg(
            "Fear index calm",
            "The VIX — a measure of how much turbulence traders expect — is under 20.",
        )
        "spy_mom_20d" -> PlainLeg(
            "S&P 500 up over the last month",
            "The S&P 500 is higher than it was 20 trading days ago.",
        )
        else -> null
    }

    /** The title every screen shows for a leg. */
    fun legTitle(leg: GateLeg): String = plain(leg.key)?.title ?: legLabel(leg)

    /** The leg's reading in words, with the bar it had to clear. Null when nothing was measured. */
    fun plainValue(leg: GateLeg): String? {
        val v = leg.value ?: return legValue(leg)
        return when (leg.key) {
            "breadth_55" -> "${num(v)}% of stocks (needs over ${num(leg.threshold ?: 55.0)}%)"
            "vix_under_20" -> "VIX ${num(v)} (needs under ${num(leg.threshold ?: 20.0)})"
            "spy_above_ema50", "qqq_above_ema50" ->
                leg.threshold?.let { "${num(v)}, average ${num(it)}" } ?: num(v)
            "spy_mom_20d" -> String.format(Locale.US, "%+.2f%% (needs above 0)", v)
            else -> legValue(leg)
        }
    }

    /** "Market checks: 4 of 5 pass" — counted from the legs sent, never a hardcoded five. */
    private fun shutHeadline(resp: GateResponse): String {
        val n = resp.legs.size
        val failed = resp.legs.count { it.ok == false }
        if (n == 0) {
            val f = resp.failing.orEmpty().size
            return if (f > 0) "Market checks: $f of 5 fail" else "Market checks: one or more fail"
        }
        return if (n > 0 && failed > 0) "Market checks: ${n - failed} of $n pass" else "Market checks: one or more fail"
    }

    /**
     * The shut gate compressed to a chip that says WHAT is wrong. One failing check is named in plain
     * words ("Narrow market: 36% of stocks in uptrends"); several are counted.
     */
    private fun shutChip(resp: GateResponse): String {
        val failing = resp.legs.filter { it.ok == false }.ifEmpty {
            // No legs sent (an older record): rebuild bare legs from the failing names so the chip can
            // still say WHICH check failed, without a reading it does not have.
            resp.failing.orEmpty().map { n -> GateLeg(name = n, key = keyForName(n) ?: n, ok = false) }
        }
        if (failing.size != 1) {
            return if (failing.isEmpty()) "Market checks fail" else "${failing.size} of ${resp.legs.size} market checks fail"
        }
        val leg = failing.single()
        val v = leg.value
        return when (leg.key) {
            "breadth_55" -> v?.let { "Narrow market: ${Math.round(it)}% of stocks in uptrends" } ?: "Narrow market"
            "spy_above_ema50" -> "S&P 500 below its 50-day average"
            "qqq_above_ema50" -> "Nasdaq below its 50-day average"
            "vix_under_20" -> v?.let { "Fear index high (VIX ${num(it)})" } ?: "Fear index high"
            "spy_mom_20d" -> "S&P 500 down over the last month"
            else -> "Failing: ${legTitle(leg)}"
        }
    }

    /**
     * One phrase for the collapsed strip when the AI's regime read and the market checks would
     * otherwise contradict each other side by side ("Risk-on uptrend · Narrow market"). Both are
     * true at once — the indexes are rising while most stocks are not — so say that. Null when there
     * is no contradiction to resolve; the strip then shows the two readings as before.
     */
    fun combinedStrip(regimeTrend: String?, gate: GateSummary?): String? {
        if (gate?.verdict != GateVerdict.SHUT) return null
        if (regimeTrend != "up") return null
        return if (gate.chip.startsWith("Narrow market")) "Indexes up, most stocks lagging"
        else "Indexes up, but ${gate.chip.replaceFirstChar { it.lowercase() }}"
    }

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
