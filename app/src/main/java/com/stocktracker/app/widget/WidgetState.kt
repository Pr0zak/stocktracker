package com.stocktracker.app.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString

/** Keys + decoders for what a single-ticker widget keeps in its Glance state. */
object TickerWidgetState {
    val CONFIG = stringPreferencesKey("config")
    val QUOTE = stringPreferencesKey("quote")
    val SPARK = stringPreferencesKey("spark")
    val ERROR = stringPreferencesKey("error")
    val LAST_REFRESH = longPreferencesKey("last_refresh")

    fun readConfig(prefs: Preferences): TickerWidgetConfig =
        prefs[CONFIG]?.let { runCatching { Http.json.decodeFromString<TickerWidgetConfig>(it) }.getOrNull() }
            ?: TickerWidgetConfig()

    fun readQuote(prefs: Preferences): Quote? =
        prefs[QUOTE]?.let { runCatching { Http.json.decodeFromString<Quote>(it) }.getOrNull() }

    fun readSpark(prefs: Preferences): List<Double> =
        prefs[SPARK]?.let { runCatching { Http.json.decodeFromString<List<Double>>(it) }.getOrNull() } ?: emptyList()
}
