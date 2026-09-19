package com.stocktracker.app.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/** Snapshot the portfolio widget renders: total value + day change across all held positions. */
@Serializable
data class PortfolioSummary(
    val totalValue: Double = 0.0,
    val dayChange: Double = 0.0,
    val dayChangePercent: Double = 0.0,
    val holdingCount: Int = 0,
    /** Holdings excluded because no quote could be fetched. Non-zero means [totalValue] covers only
     *  part of the portfolio — the widget has to say so rather than show a confidently short number. */
    val missingCount: Int = 0,
) {
    val isPartial: Boolean get() = missingCount > 0
    val isUp: Boolean get() = dayChange >= 0.0
}

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
