package com.stocktracker.app.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
) {
    val isUp: Boolean get() = changePercent >= 0.0
}

object WatchlistWidgetState {
    val ROWS = stringPreferencesKey("rows")
    val ERROR = stringPreferencesKey("error")
    val HIDE_ZERO_CENTS = booleanPreferencesKey("hide_zero_cents")

    fun readRows(prefs: Preferences): List<WatchlistRow> =
        prefs[ROWS]?.let { runCatching { Http.json.decodeFromString<List<WatchlistRow>>(it) }.getOrNull() }
            ?: emptyList()
}
