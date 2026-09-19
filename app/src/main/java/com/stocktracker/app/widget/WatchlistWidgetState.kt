package com.stocktracker.app.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/** One row rendered by the watchlist widget. */
@Serializable
data class WatchlistRow(
    val symbol: String,
    val name: String,
    val price: Double,
    val changePercent: Double,
    val currency: String = "USD",
    /** When this row's price/change were fetched. 0 means unknown (never set by an older payload
     *  written before this field existed) — treated as "not stale" rather than "infinitely stale". A
     *  row served from PriceCache carries its ORIGINAL fetch time here, not the time of this refresh,
     *  which is what lets the widget stop drawing an old move in a confident colour. */
    val asOfEpochMs: Long = 0L,
    /** Absolute (dollar) change over the day, alongside [changePercent] — WGT-5's dollar-vs-percent
     *  toggle needs both. Defaults to 0.0 so a row cached before this field existed still decodes;
     *  such a row simply can't show a dollar figure until the next refresh repopulates it. */
    val changeAbs: Double = 0.0,
) {
    val isUp: Boolean get() = changePercent >= 0.0
}

object WatchlistWidgetState {
    val ROWS = stringPreferencesKey("rows")
    val ERROR = stringPreferencesKey("error")
    val HIDE_ZERO_CENTS = booleanPreferencesKey("hide_zero_cents")
    /** How many watchlist assets the last refresh attempted to price — may exceed [ROWS]'s size when
     *  some failed. Needed to render "N of M loaded" since [ROWS] alone can't distinguish a short
     *  watchlist from a partially-failed long one. */
    val EXPECTED_COUNT = intPreferencesKey("expected_count")
    /** When [ROWS] last came from a refresh that loaded at least one price. Mirrors
     *  [TickerWidgetState.LAST_SUCCESS] / [PortfolioWidgetState.LAST_SUCCESS]. */
    val LAST_SUCCESS = longPreferencesKey("last_success")
    /** This instance's serialized [WatchlistWidgetConfig] — WGT-5. Absent on a widget placed before
     *  the watchlist widget could be configured, or one whose config JSON somehow fails to parse;
     *  [readConfig] falls back to the default config in both cases rather than erroring. */
    val CONFIG = stringPreferencesKey("config")
    /** When a refresh was last ATTEMPTED for THIS instance — mirrors [TickerWidgetState.LAST_REFRESH]
     *  (stamped before the fetch, for the same reason: gating on attempts rather than successes so a
     *  worker tick landing just inside the window doesn't get skipped and halve the effective rate). */
    val LAST_REFRESH = longPreferencesKey("last_refresh")

    fun readRows(prefs: Preferences): List<WatchlistRow> =
        prefs[ROWS]?.let { runCatching { Http.json.decodeFromString<List<WatchlistRow>>(it) }.getOrNull() }
            ?: emptyList()

    fun readConfig(prefs: Preferences): WatchlistWidgetConfig =
        prefs[CONFIG]?.let { runCatching { Http.json.decodeFromString<WatchlistWidgetConfig>(it) }.getOrNull() }
            ?: WatchlistWidgetConfig()
}
