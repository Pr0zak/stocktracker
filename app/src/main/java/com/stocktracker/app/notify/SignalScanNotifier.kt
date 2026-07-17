package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.first

/**
 * Polls the Tier-2 signals service for the latest nightly scan and posts one notification per new
 * scan that contains flips (a symbol whose signal changed vs. the prior run). Deduped by the scan's
 * generated-at timestamp so the 15-minute worker never re-notifies the same scan. No-op when no
 * Signals API URL is configured.
 */
object SignalScanNotifier {

    private val api = SignalsApiService()

    suspend fun check(context: Context) {
        val base = ServiceLocator.settingsStore.signalsApiUrl.first()
        if (base.isBlank()) return

        // Keep the backend's nightly-scan watchlist in sync with the app's — so there's no separate
        // list to maintain on the server; the app is the source of truth.
        runCatching { pushWatchlist(base) }

        val scan = runCatching { api.latestScan(base) }.getOrNull() ?: return
        val generated = scan.generatedAt?.toLong() ?: return
        val last = ServiceLocator.settingsStore.lastScanNotifiedAt.first()
        if (generated <= last) return // already processed this scan

        val flips = scan.results.filter { it.flipped }
        val squeezes = scan.results.filter { it.squeezeChanged }
        if (flips.isNotEmpty() || squeezes.isNotEmpty()) {
            val parts = buildList {
                flips.forEach { add("${it.symbol} → ${it.signal.replace('_', ' ')}") }
                squeezes.forEach { add("${it.symbol} short pressure → ${it.squeeze?.uppercase()}") }
            }
            val n = flips.size + squeezes.size
            val title = if (n == 1) "1 signal changed overnight" else "$n signals changed overnight"
            AlertNotifier.notify(context, "signal_scan".hashCode(), title, parts.joinToString(", "))
        }
        ServiceLocator.settingsStore.setLastScanNotifiedAt(generated)
    }

    /**
     * Force an immediate watchlist push to the configured service (the "Sync now" button). Returns
     * the number of symbols pushed; throws on a missing URL or a network/HTTP failure so the caller
     * can surface it. Unlike the periodic [check], errors here are not swallowed.
     */
    suspend fun syncNow(): Int {
        val base = ServiceLocator.settingsStore.signalsApiUrl.first()
        require(base.isNotBlank()) { "Set the Signals service URL first" }
        return pushWatchlist(base)
    }

    private suspend fun pushWatchlist(base: String): Int {
        val assets = ServiceLocator.watchlistStore.snapshot()
        val stocks = assets.filter { it.type == AssetType.STOCK }.map { it.symbol.uppercase() }
        val cryptos = assets.filter { it.type == AssetType.CRYPTO }.map { "${it.symbol.uppercase()}-USD" }
        api.syncWatchlist(base, stocks, cryptos)
        return stocks.size + cryptos.size
    }
}
