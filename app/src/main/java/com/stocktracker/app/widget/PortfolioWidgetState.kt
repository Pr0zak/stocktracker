package com.stocktracker.app.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString

/**
 * Snapshot the portfolio widget renders: total value + day change across all held positions.
 *
 * The class itself now lives in `:shared` (`com.stocktracker.shared.PortfolioSummary`, WGT-7) so
 * the Wear tile/complication can decode the same payload and feed it into the same
 * `portfolioDisplay` the phone widget uses. This `typealias` keeps every existing reference in this
 * app resolving to the exact same type, unchanged.
 */
typealias PortfolioSummary = com.stocktracker.shared.PortfolioSummary

object PortfolioWidgetState {
    val SUMMARY = stringPreferencesKey("portfolio_summary")
    val ERROR = stringPreferencesKey("error")
    val HIDE_ZERO_CENTS = booleanPreferencesKey("hide_zero_cents")
    /** When [SUMMARY] last came from a successful refresh. A later failure leaves the old summary
     *  in place (see [com.stocktracker.app.widget.WidgetRefresh.refreshPortfolio]) so the widget has
     *  something to show, and this is what lets it disclose how old that something is rather than
     *  presenting it as current. Mirrors [TickerWidgetState.LAST_SUCCESS]. */
    val LAST_SUCCESS = longPreferencesKey("last_success")

    fun readSummary(prefs: Preferences): PortfolioSummary? =
        prefs[SUMMARY]?.let { runCatching { Http.json.decodeFromString<PortfolioSummary>(it) }.getOrNull() }
}
