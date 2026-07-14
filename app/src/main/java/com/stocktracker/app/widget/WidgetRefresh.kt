package com.stocktracker.app.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.util.downsample
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString

/** Fetches fresh prices and pushes them into widget state. Used by the worker + config activity. */
object WidgetRefresh {

    /**
     * @param force refresh regardless of the widget's configured interval (used right after config).
     * Otherwise the fetch is skipped until [TickerWidgetConfig.refreshMinutes] has elapsed, so the
     * 15-min periodic worker honors each widget's chosen cadence.
     */
    suspend fun refreshTicker(context: Context, glanceId: GlanceId, force: Boolean = false) {
        val prefs: Preferences = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
        val config = TickerWidgetState.readConfig(prefs)
        val lastRefresh = prefs[TickerWidgetState.LAST_REFRESH] ?: 0L
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < config.refreshMinutes * 60_000L) return // not due yet

        val asset = config.toAsset()
        val hideZeroCents = ServiceLocator.settingsStore.hideZeroCents.first()
        try {
            val quote = ServiceLocator.repository.quote(asset)
            ServiceLocator.priceCache.putQuote(asset.id, quote)
            val spark = computeTickerSparkline(config)
            updateAppWidgetState(context, glanceId) { mutable ->
                mutable[TickerWidgetState.QUOTE] = Http.json.encodeToString(quote)
                mutable[TickerWidgetState.SPARK] = Http.json.encodeToString(spark)
                mutable[TickerWidgetState.LAST_REFRESH] = System.currentTimeMillis()
                mutable[TickerWidgetState.HIDE_ZERO_CENTS] = hideZeroCents
                mutable.remove(TickerWidgetState.ERROR)
            }
        } catch (e: Exception) {
            updateAppWidgetState(context, glanceId) { mutable ->
                mutable[TickerWidgetState.ERROR] = e.message ?: "Update failed"
            }
        }
        TickerWidget().update(context, glanceId)
    }

    suspend fun refreshAllTickers(context: Context) {
        GlanceAppWidgetManager(context).getGlanceIds(TickerWidget::class.java)
            .forEach { refreshTicker(context, it, force = false) }
    }

    suspend fun refreshWatchlist(context: Context) {
        val ids = GlanceAppWidgetManager(context).getGlanceIds(WatchlistWidget::class.java)
        if (ids.isEmpty()) return
        val assets = ServiceLocator.watchlistStore.snapshot()
        val rows = buildList {
            val markets = runCatching { ServiceLocator.repository.cryptoMarkets(assets) }.getOrDefault(emptyMap())
            for (asset in assets) {
                when (asset.type) {
                    AssetType.CRYPTO -> {
                        val m = markets[asset.coinGeckoId]
                        if (m != null) add(WatchlistRow(asset.symbol, asset.displayName, m.price, m.changePercent))
                    }
                    AssetType.STOCK -> {
                        val q = runCatching { ServiceLocator.repository.quote(asset) }.getOrNull()
                        if (q != null) {
                            ServiceLocator.priceCache.putQuote(asset.id, q)
                            add(WatchlistRow(asset.symbol, asset.displayName, q.price, q.changePercent, q.currency))
                        }
                    }
                }
            }
        }
        // A fetch failure (non-empty watchlist but no rows) is distinct from an empty watchlist.
        val fetchFailed = assets.isNotEmpty() && rows.isEmpty()
        val hideZeroCents = ServiceLocator.settingsStore.hideZeroCents.first()
        val json = Http.json.encodeToString(rows)
        ids.forEach { id ->
            updateAppWidgetState(context, id) { mutable ->
                mutable[WatchlistWidgetState.ROWS] = json
                mutable[WatchlistWidgetState.HIDE_ZERO_CENTS] = hideZeroCents
                if (fetchFailed) {
                    mutable[WatchlistWidgetState.ERROR] = "Couldn't load prices"
                } else {
                    mutable.remove(WatchlistWidgetState.ERROR)
                }
            }
        }
        WatchlistWidget().updateAll(context)
    }

    private suspend fun computeTickerSparkline(config: TickerWidgetConfig): List<Double> {
        val asset = config.toAsset()
        val raw = runCatching { ServiceLocator.repository.history(asset, ChartRange.DAY).map { it.price } }
            .getOrDefault(emptyList())
            .ifEmpty { ServiceLocator.priceCache.getBuffer(asset.id) }
        return raw.downsample(32)
    }
}
