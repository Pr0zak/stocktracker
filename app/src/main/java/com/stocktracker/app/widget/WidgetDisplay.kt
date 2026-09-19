package com.stocktracker.app.widget

import com.stocktracker.app.data.model.Quote

/**
 * Pure label/branch decisions shared by the three home-screen widgets, pulled out of the Glance
 * composables so they can be covered by JVM tests -- Glance composables need an Android runtime and
 * cannot run under `testDebugUnitTest`.
 *
 * All three widgets follow the same shape, mirrored from [TickerWidgetState]'s original handling of
 * age and staleness: keep showing the last good data on a failure rather than blanking the widget,
 * but always disclose how current that data is, and never let a partial result pass itself off as
 * the whole answer.
 */

/** Beyond this a shown value is no longer "now" and the widget must say so. Shared by all three
 *  widgets so "how stale is too stale" means the same thing across the whole row of home screens. */
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
// Ticker widget (WGT-3)
// -------------------------------------------------------------------------------------------

/** What [TickerWidget] should render, decided once from the stored values. */
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

/** Whether a failed refresh may keep the previously stored quote/sparkline rather than blanking
 *  them. Only true for the SAME symbol -- otherwise a reconfigure would render the old ticker's
 *  price under the new ticker's name, which is wrong rather than merely stale. */
fun shouldKeepQuoteOnFailure(storedQuote: Quote?, configSymbol: String): Boolean =
    storedQuote != null && storedQuote.symbol.equals(configSymbol, ignoreCase = true)

/** Whether the not-due branch (no network call this tick) should still repaint, so an offline
 *  stretch's age label keeps advancing instead of freezing at whatever it said when connectivity
 *  was lost. */
fun shouldRepaintForStaleness(
    storedQuote: Quote?,
    nowMs: Long,
    staleAfterMs: Long = WIDGET_STALE_AFTER_MS,
): Boolean {
    val asOf = storedQuote?.asOfEpochMs ?: return false
    if (asOf <= 0L) return false
    return nowMs - asOf > staleAfterMs
}

// -------------------------------------------------------------------------------------------
// Portfolio widget (WGT-1)
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

// -------------------------------------------------------------------------------------------
// Watchlist widget (WGT-2)
// -------------------------------------------------------------------------------------------

sealed class WatchlistDisplay {
    data class Rows(
        val visible: List<WatchlistRow>,
        /** "N of M loaded" and/or "+K more", joined; null when nothing needs disclosing. */
        val footerLabel: String?,
    ) : WatchlistDisplay()
    data class Message(val text: String) : WatchlistDisplay()
}

/** Fixed vertical cost of the widget's own chrome, in dp: the title line, the spacer under it, and
 *  the column's top+bottom padding (14dp each). */
internal const val WATCHLIST_HEADER_DP = 54f
/** One row's footprint (a 13sp text line plus its 4dp top+bottom padding), in dp. */
internal const val WATCHLIST_ROW_DP = 28f
/** The amber footer line's footprint, in dp -- reserved only when it will actually be drawn. */
internal const val WATCHLIST_FOOTER_DP = 20f
/** Cap used when the host hasn't reported a usable size yet -- the old fixed behaviour. */
internal const val WATCHLIST_FALLBACK_ROWS = 6

/** How many rows fit in a widget [heightDp] tall. */
fun watchlistRowBudget(heightDp: Float, reserveFooter: Boolean): Int {
    if (heightDp <= 0f) return WATCHLIST_FALLBACK_ROWS
    val reserved = WATCHLIST_HEADER_DP + if (reserveFooter) WATCHLIST_FOOTER_DP else 0f
    val rows = ((heightDp - reserved) / WATCHLIST_ROW_DP).toInt()
    return rows.coerceAtLeast(1)
}

fun watchlistDisplay(
    rows: List<WatchlistRow>,
    expectedCount: Int,
    error: String?,
    loaded: Boolean,
    heightDp: Float,
    lastSuccessMs: Long = 0L,
    nowMs: Long = 0L,
): WatchlistDisplay {
    if (rows.isNotEmpty()) {
        val partial = expectedCount > rows.size
        // Reserve footer space only if something will actually need to say something -- a full,
        // untruncated list draws no footer and gets that row's worth of space back.
        val noFooterBudget = watchlistRowBudget(heightDp, reserveFooter = false)
        val willTruncate = rows.size > noFooterBudget
        val reserveFooter = partial || willTruncate
        val budget = watchlistRowBudget(heightDp, reserveFooter)
        val visible = rows.take(budget)
        val more = rows.size - visible.size
        val fetchLabel = if (partial) "${rows.size} of $expectedCount loaded" else null
        val truncateLabel = if (more > 0) "+$more more" else null
        val footer = listOfNotNull(fetchLabel, truncateLabel).joinToString(" · ").ifBlank { null }
        return WatchlistDisplay.Rows(visible, footer)
    }
    if (error != null) {
        val ageMs = if (lastSuccessMs > 0L) nowMs - lastSuccessMs else 0L
        val suffix = if (ageMs > 0L) " (last updated ${widgetAgeLabel(ageMs)})" else ""
        return WatchlistDisplay.Message(error + suffix)
    }
    if (!loaded) return WatchlistDisplay.Message("Loading…")
    return WatchlistDisplay.Message("Add tickers in the app")
}

/** A row's price/percent is a claim about TODAY; past [staleAfterMs] it stops being that, and the
 *  colour must stop asserting a direction with confidence. */
fun watchlistRowIsStale(row: WatchlistRow, nowMs: Long, staleAfterMs: Long = WIDGET_STALE_AFTER_MS): Boolean =
    row.asOfEpochMs > 0L && nowMs - row.asOfEpochMs > staleAfterMs
