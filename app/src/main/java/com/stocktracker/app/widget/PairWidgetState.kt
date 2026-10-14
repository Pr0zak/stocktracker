package com.stocktracker.app.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString

/**
 * Keys + decoders for what a pair widget keeps in its Glance state. Each side has its own quote AND
 * its own error: one symbol failing to refresh must not mark the other, freshly priced one as
 * "Update failed".
 */
object PairWidgetState {
    val CONFIG = stringPreferencesKey("config")
    val QUOTE_LEFT = stringPreferencesKey("quote_left")
    val QUOTE_RIGHT = stringPreferencesKey("quote_right")
    val ERROR_LEFT = stringPreferencesKey("error_left")
    val ERROR_RIGHT = stringPreferencesKey("error_right")
    /** When a refresh was last ATTEMPTED — stamped before the fetch; see [TickerWidgetState.LAST_REFRESH]. */
    val LAST_REFRESH = longPreferencesKey("last_refresh")
    val HIDE_ZERO_CENTS = booleanPreferencesKey("hide_zero_cents")

    fun readConfig(prefs: Preferences): PairWidgetConfig =
        prefs[CONFIG]?.let { runCatching { Http.json.decodeFromString<PairWidgetConfig>(it) }.getOrNull() }
            ?: PairWidgetConfig()

    fun readQuote(prefs: Preferences, key: Preferences.Key<String>): Quote? =
        prefs[key]?.let { runCatching { Http.json.decodeFromString<Quote>(it) }.getOrNull() }
}
