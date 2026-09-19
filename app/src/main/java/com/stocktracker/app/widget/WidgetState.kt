package com.stocktracker.app.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    /** When a refresh was last ATTEMPTED — stamped before the fetch, so the interval gate measures
     *  attempts rather than successes (stamping after made every other run fall just inside the
     *  window and get skipped). */
    val LAST_REFRESH = longPreferencesKey("last_refresh")
    /** When a fetch last actually SUCCEEDED (as opposed to [LAST_REFRESH], which advances on every
     *  attempt). Kept for parity with the other two widgets' state and for diagnosing the refresh
     *  cadence; the displayed staleness itself is derived from [Quote.asOfEpochMs] (see
     *  [tickerDisplay]), not from this, because [com.stocktracker.app.data.MarketRepository]'s
     *  stale-while-error cache can hand back a successful call carrying an old quote — stamping
     *  "now" here for that call would suppress the age label on an hours-old price. */
    val LAST_SUCCESS = longPreferencesKey("last_success")
    val HIDE_ZERO_CENTS = booleanPreferencesKey("hide_zero_cents")

    fun readConfig(prefs: Preferences): TickerWidgetConfig =
        prefs[CONFIG]?.let { runCatching { Http.json.decodeFromString<TickerWidgetConfig>(it) }.getOrNull() }
            ?: TickerWidgetConfig()

    fun readQuote(prefs: Preferences): Quote? =
        prefs[QUOTE]?.let { runCatching { Http.json.decodeFromString<Quote>(it) }.getOrNull() }

    fun readSpark(prefs: Preferences): List<Double> =
        prefs[SPARK]?.let { runCatching { Http.json.decodeFromString<List<Double>>(it) }.getOrNull() } ?: emptyList()
}
