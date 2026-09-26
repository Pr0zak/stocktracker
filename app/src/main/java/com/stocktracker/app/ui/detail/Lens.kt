package com.stocktracker.app.ui.detail

import com.stocktracker.app.data.remote.HttpStatusException

/**
 * What happened when the screen went looking for one lens.
 *
 * Nine of the detail screen's cards were hidden behind `state.x?.let { ... }`, which meant one blank
 * space stood for five different facts: nobody asked (no Signals service URL), the ask is still in
 * flight, the ask failed, the ask came back with genuinely nothing, or this lens does not exist for
 * this instrument. A reader looking at an empty stretch of screen where Congress trades ought to be
 * cannot tell "no politician has traded this" from "the server was down" — and the first is
 * information while the second is an invitation to try again.
 *
 * This is the recurring defect class in this app, written down: absent is not zero, stale is not
 * fresh, and failed is not calm.
 */
enum class LensStatus {
    /** Not asked. Usually because no Signals service URL is configured. */
    IDLE,
    LOADING,
    /** Asked, answered, and there is something to draw. */
    READY,
    /** Asked, answered, and the answer was "nothing here". A real finding, not a gap. */
    EMPTY,
    /** Could not ask, or the ask threw. Worth a retry — and only this state is. */
    FAILED,
    /** This lens does not exist for this instrument. No retry will ever change that. */
    NOT_APPLICABLE,
}

/**
 * One lens's value and the story of how it got there.
 *
 * [value] is non-null only in [LensStatus.READY], so existing call sites become `state.x.value?.let`
 * and keep working; the point is that the screen can now also ask WHY it is null.
 */
data class Lens<out T>(
    val status: LensStatus = LensStatus.IDLE,
    val value: T? = null,
) {
    val isFailed: Boolean get() = status == LensStatus.FAILED
    val isEmpty: Boolean get() = status == LensStatus.EMPTY
    val isNotApplicable: Boolean get() = status == LensStatus.NOT_APPLICABLE

    companion object {
        fun <T> ready(value: T) = Lens(LensStatus.READY, value)
        val loading = Lens<Nothing>(LensStatus.LOADING)
        val empty = Lens<Nothing>(LensStatus.EMPTY)
        val failed = Lens<Nothing>(LensStatus.FAILED)
        val notApplicable = Lens<Nothing>(LensStatus.NOT_APPLICABLE)
        val idle = Lens<Nothing>(LensStatus.IDLE)

        /**
         * The usual shape: a call that may throw, whose success may still be nothing worth drawing.
         * [worthShowing] is what separates EMPTY from READY, and it is a per-lens judgement — a
         * Congress block with zero trades is empty; a quality block with no flags and no metrics is
         * empty; a short-pressure block is worth showing whenever it exists.
         *
         * A 404 is an ANSWER, not a failure. The backend returns one for a name with under about
         * four years of weekly history, or with no filings of a given kind — there is nothing there
         * and no amount of retrying will produce it. Classifying those as FAILED would put a Retry
         * button under half the small caps on the list and make it mean nothing. Everything else a
         * throw can be — no network, a 500, a proxy that cannot reach the service, a timeout — is a
         * failure, and those are worth another tap.
         */
        fun <T> from(result: Result<T?>, worthShowing: (T) -> Boolean): Lens<T> {
            result.exceptionOrNull()?.let { e ->
                val code = (e as? HttpStatusException)?.code
                return if (code == 404 || code == 410 || code == 204) empty else failed
            }
            val v = result.getOrNull() ?: return empty
            return if (worthShowing(v)) ready(v) else empty
        }
    }
}

/** Every lens the detail screen can hold, for naming one in a footer or retrying it. */
enum class LensId(val label: String) {
    SHORT_PRESSURE("Short pressure"),
    INSIDER("Insider buying"),
    CONGRESS("Congress trades"),
    SEASONALITY("Seasonality"),
    QUALITY("Quality"),
    VALUE_TRAP("Value trap"),
    TREND("200-week line"),
    CYCLE("Halving cycle"),
    /** FC-1 — a fund's yearly fee and its look-alikes. Deliberately absent from the screen's
     *  not-applicable / nothing-to-show footers: on a single stock "What it costs" does not exist,
     *  and naming it under every company would be noise. */
    FUND_COST("What it costs"),
    /** FUND-2/6 — what a fund holds and how it overlaps what the user owns. Footer-exempt, like FUND_COST. */
    FUND_HOLDS("What it holds"),
}
