package com.stocktracker.shared

/**
 * The ticker/portfolio honesty-rule decisions shared by the phone's home-screen widgets AND the
 * Wear tile/complication (WGT-7).
 *
 * Moved here from `com.stocktracker.app.widget.WidgetDisplay` verbatim -- these functions never had
 * an Android dependency (that was the original point: they were pulled out of the Glance composables
 * specifically so they could run under a plain JVM test). Relocating them to a plain-Kotlin module
 * means the Wear module can call the EXACT SAME CODE the phone calls -- not a watch-side
 * reimplementation that could quietly drift from the phone's rules -- while `:app` is unaffected: it
 * imports these by their original names from this package (see `widget/WidgetDisplay.kt`), and every
 * existing call site (`TickerWidget.kt`, `PortfolioWidget.kt`, `WidgetDisplayTest.kt`) just gained an
 * import line pointing here instead of finding them in the same file.
 *
 * The watchlist-specific display logic ([com.stocktracker.app.widget] `watchlistDisplay` and
 * friends) stays behind in `:app` -- it depends on `Asset`/`AssetType`/watchlist config types that
 * are only meaningful on the phone, and WGT-7's scope is a single ticker or the portfolio total, not
 * the watchlist.
 */

/** Beyond this a shown value is no longer "now" and the display must say so. Shared by both phone
 *  widgets and the Wear tile/complication so "how stale is too stale" means the same thing on every
 *  surface this app has. */
const val WIDGET_STALE_AFTER_MS = 45L * 60 * 1000

/** "3m ago" / "2h ago" / "5d ago". */
fun widgetAgeLabel(ageMs: Long): String {
    val mins = ageMs / 60_000
    return when {
        mins < 120 -> "${mins}m ago"
        mins < 60 * 48 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

// -------------------------------------------------------------------------------------------
// Ticker (WGT-3, reused by the Wear tile/complication in WGT-7)
// -------------------------------------------------------------------------------------------

/** What a single-ticker surface (the phone widget, or the Wear tile/complication) should render,
 *  decided once from the stored values. */
sealed class TickerDisplay {
    /** Nothing to show at all -- never fetched, or fetched and failed with nothing cached. */
    data class NoData(val tapToOpen: Boolean) : TickerDisplay()
    /** A quote to show, plus the freshness line underneath it (null = nothing to disclose). */
    data class Priced(val quote: Quote, val ageLabel: String?) : TickerDisplay()
}

fun tickerDisplay(
    quote: Quote?,
    error: String?,
    nowMs: Long,
    staleAfterMs: Long = WIDGET_STALE_AFTER_MS,
): TickerDisplay {
    if (quote == null) return TickerDisplay.NoData(tapToOpen = error != null)
    val ageMs = if (quote.asOfEpochMs > 0L) nowMs - quote.asOfEpochMs else 0L
    val ageLabel = when {
        // An error alongside a kept quote means the number on screen is old, not that this refresh
        // confirmed it current -- say so plainly instead of the routine "as of" phrasing.
        error != null -> "Update failed"
        ageMs > staleAfterMs -> "as of " + widgetAgeLabel(ageMs)
        else -> null
    }
    return TickerDisplay.Priced(quote, ageLabel)
}

// -------------------------------------------------------------------------------------------
// Portfolio (WGT-1, reused by the Wear tile/complication in WGT-7)
// -------------------------------------------------------------------------------------------

sealed class PortfolioDisplay {
    data class Priced(
        val summary: PortfolioSummary,
        /** "N of M priced" -- non-null exactly when [PortfolioSummary.isPartial]. */
        val partialLabel: String?,
        /** "Update failed" / "as of Xh ago" / null. */
        val ageLabel: String?,
    ) : PortfolioDisplay()
    data class Message(val text: String) : PortfolioDisplay()
}

fun portfolioDisplay(
    summary: PortfolioSummary?,
    loaded: Boolean,
    error: String?,
    lastSuccessMs: Long,
    nowMs: Long,
    staleAfterMs: Long = WIDGET_STALE_AFTER_MS,
): PortfolioDisplay {
    if (summary != null && summary.holdingCount > 0) {
        // Every priced holding failed: the total is $0.00 of nothing, not a real number.
        if (summary.missingCount >= summary.holdingCount) {
            return PortfolioDisplay.Message("Couldn't load portfolio")
        }
        val partialLabel = if (summary.isPartial) {
            "${summary.holdingCount - summary.missingCount} of ${summary.holdingCount} priced"
        } else null
        val ageMs = if (lastSuccessMs > 0L) nowMs - lastSuccessMs else 0L
        val ageLabel = when {
            error != null -> "Update failed"
            ageMs > staleAfterMs -> "as of " + widgetAgeLabel(ageMs)
            else -> null
        }
        return PortfolioDisplay.Priced(summary, partialLabel, ageLabel)
    }
    if (error != null) return PortfolioDisplay.Message(error)
    if (!loaded) return PortfolioDisplay.Message("Loading…")
    return PortfolioDisplay.Message("Set shares on a ticker to track value")
}
