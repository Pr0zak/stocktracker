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

    /** Tolerance on the refresh interval. The driving worker fires on its own ~15-minute cadence, so
     *  an exact comparison lands microseconds inside the window and skips the run entirely. */
    private const val REFRESH_SLACK_MS = 60_000L

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
        // Stamp the attempt BEFORE fetching, not after. LAST_REFRESH used to be written only once the
        // quote and sparkline had come back, so on a 15-minute widget driven by a 15-minute worker
        // the elapsed time at the next tick was (15 min - fetch duration) — just under the interval —
        // and the gate skipped it. The widget then refreshed every 30 minutes instead of every 15,
        // systematically dropping every other run.
        if (!force && now - lastRefresh < config.refreshMinutes * 60_000L - REFRESH_SLACK_MS) {
            return // not due yet
        }
        updateAppWidgetState(context, glanceId) { it[TickerWidgetState.LAST_REFRESH] = now }

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
                mutable[TickerWidgetState.LAST_SUCCESS] = System.currentTimeMillis()
                mutable[TickerWidgetState.HIDE_ZERO_CENTS] = hideZeroCents
                mutable.remove(TickerWidgetState.ERROR)
            }
        } catch (e: Exception) {
            updateAppWidgetState(context, glanceId) { mutable ->
                mutable[TickerWidgetState.ERROR] = e.message ?: "Update failed"
                // Drop the previous payload too. Writing only ERROR left the OLD symbol's price and
                // sparkline in state, so after a reconfigure the widget rendered the previous
                // ticker's numbers under the new ticker's name — not stale, just wrong.
                mutable.remove(TickerWidgetState.QUOTE)
                mutable.remove(TickerWidgetState.SPARK)
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
                        // Fall back to the cache like the app's own screens do. Dropping the row
                        // instead meant a partially-failed fetch rendered a SUBSET of the watchlist
                        // as though it were the whole thing, with nothing marking the omission.
                        val q = runCatching { ServiceLocator.repository.quote(asset) }.getOrNull()
                            ?: ServiceLocator.priceCache.getQuote(asset.id)
                        if (q != null) {
                            ServiceLocator.priceCache.putQuote(asset.id, q)
                            add(WatchlistRow(asset.symbol, asset.displayName, q.price, q.changePercent, q.currency))
                        }
                    }
                }
            }
        }
        // A fetch failure (non-empty watchlist but no rows) is distinct from an empty watchlist.
        // A PARTIAL failure counts too: showing 6 of 9 tickers with no indication reads as a
        // complete list, so treat any missing row as a failure the widget must surface.
        val fetchFailed = assets.isNotEmpty() && rows.size < assets.size
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

    suspend fun refreshPortfolio(context: Context) {
        val ids = GlanceAppWidgetManager(context).getGlanceIds(PortfolioWidget::class.java)
        if (ids.isEmpty()) return
        val held = ServiceLocator.watchlistStore.snapshot().filter { (it.shares ?: 0.0) > 0.0 }
        val hideZeroCents = ServiceLocator.settingsStore.hideZeroCents.first()
        try {
            var total = 0.0
            var day = 0.0
            // A failed quote used to `continue` silently, so the widget's total was a sum over an
            // arbitrary subset presented as the whole portfolio — and unlike the in-app screen there
            // was no other number nearby to notice the discrepancy against. Count the misses.
            var missing = 0
            if (held.isNotEmpty()) {
                val markets = runCatching { ServiceLocator.repository.cryptoMarkets(held) }.getOrDefault(emptyMap())
                for (asset in held) {
                    val shares = asset.shares ?: continue      // no position — not a missing quote
                    val quoted = when (asset.type) {
                        AssetType.CRYPTO -> markets[asset.coinGeckoId]?.let { it.price to it.change }
                        AssetType.STOCK -> runCatching { ServiceLocator.repository.quote(asset) }
                            .getOrNull()?.let { it.price to it.change }
                    }
                    if (quoted == null) { missing++; continue }
                    total += shares * quoted.first
                    day += shares * quoted.second
                }
            }
            val prev = total - day
            val pct = if (prev != 0.0) day / prev * 100.0 else 0.0
            val summary = PortfolioSummary(total, day, pct, held.size, missing)
            val json = Http.json.encodeToString(summary)
            ids.forEach { id ->
                updateAppWidgetState(context, id) { mutable ->
                    mutable[PortfolioWidgetState.SUMMARY] = json
                    mutable[PortfolioWidgetState.HIDE_ZERO_CENTS] = hideZeroCents
                    mutable.remove(PortfolioWidgetState.ERROR)
                }
            }
        } catch (e: Exception) {
            ids.forEach { id ->
                updateAppWidgetState(context, id) { mutable ->
                    mutable[PortfolioWidgetState.ERROR] = "Couldn't load portfolio"
                }
            }
        }
        PortfolioWidget().updateAll(context)
    }

    private suspend fun computeTickerSparkline(config: TickerWidgetConfig): List<Double> {
        val asset = config.toAsset()
        val raw = runCatching { ServiceLocator.repository.history(asset, ChartRange.DAY).map { it.price } }
            .getOrDefault(emptyList())
            .ifEmpty { ServiceLocator.priceCache.getBuffer(asset.id) }
        return raw.downsample(32)
    }
}
