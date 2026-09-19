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
import com.stocktracker.app.ui.portfolio.STALE_QUOTE_MS
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
            // Not due for a network fetch, but a widget that only ever redraws alongside a fetch
            // never advances its "as of Xh ago" label once the device goes offline — the periodic
            // worker requires NetworkType.CONNECTED, so there is no other trigger. Repaint the
            // existing data when it has crossed the staleness threshold, so the age text keeps
            // moving even though nothing was fetched this tick.
            if (shouldRepaintForStaleness(TickerWidgetState.readQuote(prefs), now)) {
                TickerWidget().update(context, glanceId)
            }
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
            // A transient failure (a single 429, an offline blip) must not blank a widget that still
            // has a perfectly usable quote sitting in state — PriceCache and the app's own screens
            // both carry a stale-but-real number through an outage rather than showing nothing. Keep
            // the stored payload UNLESS it belongs to a different symbol (a reconfigure), where
            // showing it would be wrong rather than merely old.
            val stored = TickerWidgetState.readQuote(prefs)
            updateAppWidgetState(context, glanceId) { mutable ->
                mutable[TickerWidgetState.ERROR] = e.message ?: "Update failed"
                if (!shouldKeepQuoteOnFailure(stored, config.symbol)) {
                    mutable.remove(TickerWidgetState.QUOTE)
                    mutable.remove(TickerWidgetState.SPARK)
                }
            }
        }
        TickerWidget().update(context, glanceId)
    }

    /**
     * Redraw every placed widget without fetching anything.
     *
     * Appearance-only changes go through here rather than through [WidgetRefreshScheduler.refreshNow],
     * which enqueues a worker that re-quotes every symbol and runs all six notifiers. Moving a colour
     * slider is not a reason to hit the network, and the quotes already on screen are still correct.
     */
    suspend fun repaintAll(context: Context) {
        runCatching { TickerWidget().updateAll(context) }
        runCatching { WatchlistWidget().updateAll(context) }
        runCatching { PortfolioWidget().updateAll(context) }
    }

    suspend fun refreshAllTickers(context: Context) {
        GlanceAppWidgetManager(context).getGlanceIds(TickerWidget::class.java)
            .forEach { refreshTicker(context, it, force = false) }
    }

    /**
     * Refresh every placed watchlist widget, each against its OWN configured list/sort/refresh
     * cadence (WGT-5).
     *
     * This used to fetch ONE shared set of rows and write it to every instance — so two watchlist
     * widgets were always the same widget twice, and there was no way to point one at "Crypto" and
     * the other at "All". [refreshWatchlistInstance] is where the actual per-instance fetch lives;
     * this just fans the periodic worker's tick out to every placed id.
     */
    suspend fun refreshWatchlist(context: Context) {
        GlanceAppWidgetManager(context).getGlanceIds(WatchlistWidget::class.java)
            .forEach { refreshWatchlistInstance(context, it, force = false) }
    }

    /**
     * @param force refresh regardless of this instance's configured interval (used right after
     * config, mirrors [refreshTicker]).
     */
    suspend fun refreshWatchlistInstance(context: Context, glanceId: GlanceId, force: Boolean = false) {
        val prefs: Preferences = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
        val config = WatchlistWidgetState.readConfig(prefs)
        val lastRefresh = prefs[WatchlistWidgetState.LAST_REFRESH] ?: 0L
        val now = System.currentTimeMillis()
        // Same attempt-before-fetch stamping as refreshTicker, for the same reason: stamping only on
        // success skews the next gate check just inside the window on a worker-driven cadence.
        if (!force && now - lastRefresh < config.refreshMinutes * 60_000L - REFRESH_SLACK_MS) {
            if (shouldRepaintWatchlistForStaleness(prefs[WatchlistWidgetState.LAST_SUCCESS] ?: 0L, now)) {
                WatchlistWidget().update(context, glanceId)
            }
            return // not due yet
        }
        updateAppWidgetState(context, glanceId) { it[WatchlistWidgetState.LAST_REFRESH] = now }

        val allAssets = ServiceLocator.watchlistStore.snapshot()
        val assets = filterWatchlistAssets(allAssets, config.listName)
        val fetchStartMs = System.currentTimeMillis()
        val rows = buildList {
            val markets = runCatching { ServiceLocator.repository.cryptoMarkets(assets) }.getOrDefault(emptyMap())
            for (asset in assets) {
                when (asset.type) {
                    AssetType.CRYPTO -> {
                        val m = markets[asset.coinGeckoId]
                        // CoinMarket carries no timestamp of its own (it's a batched snapshot) — the
                        // fetch just happened, so the moment this refresh started is as close to
                        // exact as this data gets.
                        if (m != null) {
                            add(WatchlistRow(
                                symbol = asset.symbol, name = asset.displayName, price = m.price,
                                changePercent = m.changePercent, changeAbs = m.change, asOfEpochMs = fetchStartMs,
                            ))
                        }
                    }
                    AssetType.STOCK -> {
                        // Fall back to the cache like the app's own screens do. Dropping the row
                        // instead meant a partially-failed fetch rendered a SUBSET of the watchlist
                        // as though it were the whole thing, with nothing marking the omission.
                        val q = runCatching { ServiceLocator.repository.quote(asset) }.getOrNull()
                            ?: ServiceLocator.priceCache.getQuote(asset.id)
                        if (q != null) {
                            ServiceLocator.priceCache.putQuote(asset.id, q)
                            add(WatchlistRow(
                                symbol = asset.symbol, name = asset.displayName, price = q.price,
                                changePercent = q.changePercent, currency = q.currency,
                                asOfEpochMs = q.asOfEpochMs, changeAbs = q.change,
                            ))
                        }
                    }
                }
            }
        }
        val sorted = sortWatchlistRows(rows, config.sortOrder)
        // A fetch failure (non-empty list but no rows) is distinct from an empty list -- and a
        // PARTIAL failure counts too: showing 6 of 9 tickers with no indication reads as a complete
        // list, so treat any missing row as a failure the widget must surface.
        val fetchFailed = assets.isNotEmpty() && rows.size < assets.size
        val hideZeroCents = ServiceLocator.settingsStore.hideZeroCents.first()
        val json = Http.json.encodeToString(sorted)
        updateAppWidgetState(context, glanceId) { mutable ->
            mutable[WatchlistWidgetState.ROWS] = json
            mutable[WatchlistWidgetState.EXPECTED_COUNT] = assets.size
            mutable[WatchlistWidgetState.HIDE_ZERO_CENTS] = hideZeroCents
            mutable[WatchlistWidgetState.LAST_REFRESH] = System.currentTimeMillis()
            if (rows.isNotEmpty()) mutable[WatchlistWidgetState.LAST_SUCCESS] = fetchStartMs
            if (fetchFailed) {
                mutable[WatchlistWidgetState.ERROR] = "Couldn't load prices"
            } else {
                mutable.remove(WatchlistWidgetState.ERROR)
            }
        }
        WatchlistWidget().update(context, glanceId)
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
                val now = System.currentTimeMillis()
                val markets = runCatching { ServiceLocator.repository.cryptoMarkets(held) }.getOrDefault(emptyMap())
                for (asset in held) {
                    val shares = asset.shares ?: continue      // no position — not a missing quote
                    val quoted = when (asset.type) {
                        AssetType.CRYPTO -> markets[asset.coinGeckoId]?.let { it.price to it.change }
                        AssetType.STOCK -> {
                            // Fall back to the cache like AlertChecker does, gated by STALE_QUOTE_MS
                            // so a cache entry too old to describe TODAY'S change is treated the same
                            // as no quote at all rather than folded into the total anyway.
                            val q = runCatching { ServiceLocator.repository.quote(asset) }.getOrNull()
                                ?.also { ServiceLocator.priceCache.putQuote(asset.id, it) }
                                ?: ServiceLocator.priceCache.getQuote(asset.id)
                                    ?.takeIf { it.asOfEpochMs <= 0L || now - it.asOfEpochMs <= STALE_QUOTE_MS }
                            q?.let { it.price to it.change }
                        }
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
            val now = System.currentTimeMillis()
            ids.forEach { id ->
                updateAppWidgetState(context, id) { mutable ->
                    mutable[PortfolioWidgetState.SUMMARY] = json
                    mutable[PortfolioWidgetState.HIDE_ZERO_CENTS] = hideZeroCents
                    mutable[PortfolioWidgetState.LAST_SUCCESS] = now
                    mutable.remove(PortfolioWidgetState.ERROR)
                }
            }
        } catch (e: Exception) {
            // Leave the previous SUMMARY (and its LAST_SUCCESS) in place — see portfolioDisplay():
            // the widget still has a real, if now-old, total to show, and disclosing its age is the
            // honest alternative to either blanking the widget or re-presenting it as current.
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
