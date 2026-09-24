package com.stocktracker.app.widget

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.util.Formatting
import com.stocktracker.shared.PortfolioDisplay
import com.stocktracker.shared.TickerDisplay
import com.stocktracker.shared.WIDGET_STALE_AFTER_MS
import com.stocktracker.shared.portfolioDisplay
import com.stocktracker.shared.tickerDisplay
import com.stocktracker.shared.widgetAgeLabel

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

// -------------------------------------------------------------------------------------------
// Ticker widget (WGT-3)
// -------------------------------------------------------------------------------------------
//
// [WIDGET_STALE_AFTER_MS], [widgetAgeLabel], [TickerDisplay], and [tickerDisplay] now live in
// `:shared` (`com.stocktracker.shared`, WGT-7) so the Wear tile/complication can call the exact
// same functions instead of a watch-side reimplementation. Imported above.

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
//
// [PortfolioDisplay] and [portfolioDisplay] now live in `:shared` (WGT-7) alongside
// [PortfolioSummary] itself, for the same reason as the ticker functions above. Imported above.

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
/** The subtitle line's footprint, in dp -- reserved only when the widget is scoped to something
 *  other than the whole watchlist (WGT-5) and so needs to say which list it is, on top of the
 *  generic "Watchlist" title. Without this, two differently-configured instances would be
 *  indistinguishable at a glance even after each renders its own correct rows. */
internal const val WATCHLIST_SUBTITLE_DP = 16f
/** Cap used when the host hasn't reported a usable size yet -- the old fixed behaviour. */
internal const val WATCHLIST_FALLBACK_ROWS = 6

/** How many rows fit in a widget [heightDp] tall. [showSubtitle] reserves the extra line a
 *  non-default [WatchlistWidgetConfig.listName] draws under the title (WGT-5). */
fun watchlistRowBudget(heightDp: Float, reserveFooter: Boolean, showSubtitle: Boolean = false): Int {
    if (heightDp <= 0f) return WATCHLIST_FALLBACK_ROWS
    val reserved = WATCHLIST_HEADER_DP +
        (if (showSubtitle) WATCHLIST_SUBTITLE_DP else 0f) +
        (if (reserveFooter) WATCHLIST_FOOTER_DP else 0f)
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
    /** The subtitle text this instance is showing (from [watchlistListLabel]), or null for the
     *  default "whole watchlist" instance, which draws no subtitle at all (WGT-5). */
    listLabel: String? = null,
): WatchlistDisplay {
    val showSubtitle = listLabel != null
    if (rows.isNotEmpty()) {
        val partial = expectedCount > rows.size
        // Reserve footer space only if something will actually need to say something -- a full,
        // untruncated list draws no footer and gets that row's worth of space back.
        val noFooterBudget = watchlistRowBudget(heightDp, reserveFooter = false, showSubtitle = showSubtitle)
        val willTruncate = rows.size > noFooterBudget
        val reserveFooter = partial || willTruncate
        val budget = watchlistRowBudget(heightDp, reserveFooter, showSubtitle = showSubtitle)
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
    // A list scoped down to a named group (or Stocks/Crypto) that happens to be empty is a
    // different fact than a genuinely empty watchlist -- say which list came back empty rather
    // than the generic hint, which would otherwise read as "you have tracked nothing at all".
    return WatchlistDisplay.Message(
        if (listLabel != null) "No tickers in \"$listLabel\"" else "Add tickers in the app",
    )
}

/** A row's price/percent is a claim about TODAY; past [staleAfterMs] it stops being that, and the
 *  colour must stop asserting a direction with confidence. */
fun watchlistRowIsStale(row: WatchlistRow, nowMs: Long, staleAfterMs: Long = WIDGET_STALE_AFTER_MS): Boolean =
    row.asOfEpochMs > 0L && nowMs - row.asOfEpochMs > staleAfterMs

/** The subtitle a watchlist widget instance draws under its "Watchlist" title, or null when it's
 *  showing the whole watchlist and no extra label is needed (WGT-5). */
fun watchlistListLabel(listName: String): String? = listName.takeIf { it != WatchlistWidgetConfig.LIST_ALL }

/**
 * Which of the user's tracked assets a watchlist widget instance should show, before sorting
 * (WGT-5) -- the fix for every instance rendering an identical, shared set of rows. [LIST_ALL]
 * keeps everything; [LIST_STOCKS]/[LIST_CRYPTO] split by [AssetType]; anything else is treated as
 * the name of a group from [Asset.groups] -- a group the user has since renamed or deleted just
 * filters down to nothing (handled by [watchlistDisplay]'s empty-list message) rather than
 * silently falling back to the full watchlist, which would be a quieter but no less wrong version
 * of the original bug.
 */
fun filterWatchlistAssets(assets: List<Asset>, listName: String): List<Asset> = when (listName) {
    WatchlistWidgetConfig.LIST_ALL -> assets
    WatchlistWidgetConfig.LIST_STOCKS -> assets.filter { it.type == AssetType.STOCK }
    WatchlistWidgetConfig.LIST_CRYPTO -> assets.filter { it.type == AssetType.CRYPTO }
    else -> assets.filter { it.groups.contains(listName) }
}

/**
 * Orders the already-priced rows for display (WGT-5). This runs AFTER the fetch, not on the asset
 * list beforehand, because [WatchlistSortOrder.CHANGE_DESC]/[WatchlistSortOrder.CHANGE_ASC] need a
 * price to sort by. [WatchlistSortOrder.MANUAL] is a no-op: the rows already arrive in the
 * watchlist's own stored order, matching the in-app screen's default.
 */
fun sortWatchlistRows(rows: List<WatchlistRow>, sortOrder: WatchlistSortOrder): List<WatchlistRow> =
    when (sortOrder) {
        WatchlistSortOrder.MANUAL -> rows
        WatchlistSortOrder.ALPHABETICAL -> rows.sortedBy { it.symbol.uppercase() }
        WatchlistSortOrder.CHANGE_DESC -> rows.sortedByDescending { it.changePercent }
        WatchlistSortOrder.CHANGE_ASC -> rows.sortedBy { it.changePercent }
    }

/** The change column's text for one row, honouring the dollar-vs-percent choice (WGT-5). Mirrors
 *  [TickerWidget]'s showChangePercent branch, one column at a time instead of one whole widget. */
fun watchlistChangeText(row: WatchlistRow, valueMode: WatchlistValueMode, hideZeroCents: Boolean): String =
    when (valueMode) {
        WatchlistValueMode.PERCENT -> "${Formatting.arrow(row.isUp)} ${Formatting.percent(row.changePercent)}"
        WatchlistValueMode.DOLLAR -> "${Formatting.arrow(row.isUp)} ${Formatting.change(row.changeAbs, hideZeroCents, reference = row.price)}"
    }

/** Mirrors [shouldRepaintForStaleness] for the watchlist widget, which tracks staleness per
 *  instance via [WatchlistWidgetState.LAST_SUCCESS] rather than a single quote's timestamp -- so an
 *  offline stretch still advances a widget's "as of Xh ago" label even though nothing was fetched. */
fun shouldRepaintWatchlistForStaleness(
    lastSuccessMs: Long,
    nowMs: Long,
    staleAfterMs: Long = WIDGET_STALE_AFTER_MS,
): Boolean = lastSuccessMs > 0L && nowMs - lastSuccessMs > staleAfterMs
