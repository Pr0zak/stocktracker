package com.stocktracker.app.widget

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import kotlinx.serialization.Serializable

/** Per-widget configuration for a single-ticker widget. Stored in that widget's Glance state. */
@Serializable
data class TickerWidgetConfig(
    val symbol: String = "AAPL",
    val type: AssetType = AssetType.STOCK,
    val displayName: String = "Apple Inc.",
    val coinGeckoId: String? = null,
    val showChangePercent: Boolean = true,
    val showSparkline: Boolean = true,
    val showName: Boolean = false,
    val accentArgb: Long = 0xFFB4A0FF,
    val refreshMinutes: Int = 15,
) {
    fun toAsset() = Asset(symbol = symbol, type = type, displayName = displayName, coinGeckoId = coinGeckoId)

    companion object {
        val ACCENT_CHOICES = listOf(
            0xFFB4A0FFL, // indigo
            0xFF5EDD9CL, // green — GainGreen; the old 0xFF4ADE80 is the pastel INK-2 retired
            0xFF38BDF8L, // sky
            0xFFFB923CL, // orange
            0xFFF472B6L, // pink
        )
        val REFRESH_CHOICES = listOf(15, 30, 60, 120)
    }
}

/** How a watchlist widget instance orders its rows once they're priced (WGT-5). Sorting happens
 *  after the fetch, not before, because [CHANGE_DESC]/[CHANGE_ASC] need a price to sort by. */
@Serializable
enum class WatchlistSortOrder { MANUAL, ALPHABETICAL, CHANGE_DESC, CHANGE_ASC }

/** Whether a watchlist widget's change column reads as a percent move or a dollar move (WGT-5). */
@Serializable
enum class WatchlistValueMode { PERCENT, DOLLAR }

/**
 * Per-widget configuration for a watchlist widget instance. Stored in that widget's Glance state,
 * same pattern as [TickerWidgetConfig] (WGT-5) — this is what lets two placed watchlist widgets show
 * two different lists instead of an identical copy of whichever one a shared refresh wrote last.
 */
@Serializable
data class WatchlistWidgetConfig(
    /** [LIST_ALL], [LIST_STOCKS], [LIST_CRYPTO], or the name of a named list from
     *  [com.stocktracker.app.data.prefs.SettingsStore.watchlistGroups] (see [Asset.groups]). A list
     *  the user later renames or deletes just filters down to nothing — see
     *  [filterWatchlistAssets] — rather than erroring or silently falling back to "All". */
    val listName: String = LIST_ALL,
    val sortOrder: WatchlistSortOrder = WatchlistSortOrder.MANUAL,
    val valueMode: WatchlistValueMode = WatchlistValueMode.PERCENT,
    val accentArgb: Long = 0xFFB4A0FF,
    val refreshMinutes: Int = 15,
) {
    companion object {
        const val LIST_ALL = "All"
        const val LIST_STOCKS = "Stocks"
        const val LIST_CRYPTO = "Crypto"
        val BUILTIN_LISTS = listOf(LIST_ALL, LIST_STOCKS, LIST_CRYPTO)

        // Same choices as the ticker widget — one accent palette and one set of cadences for the
        // whole row of home-screen widgets, rather than a second list to keep in sync.
        val ACCENT_CHOICES = TickerWidgetConfig.ACCENT_CHOICES
        val REFRESH_CHOICES = TickerWidgetConfig.REFRESH_CHOICES
    }
}
