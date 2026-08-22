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
        // No scan on the server = nothing happened that we know of. Every list in that payload is
        // null, and notifying off it would be posting silence as news.
        val results = scan.results?.takeIf { scan.hasScan } ?: return
        val generated = scan.generatedAt?.toLong() ?: return
        val last = ServiceLocator.settingsStore.lastScanNotifiedAt.first()
        if (generated <= last) return // already processed this scan

        val flips = results.filter { it.flipped }
        val squeezes = results.filter { it.squeezeChanged }
        if (flips.isNotEmpty() || squeezes.isNotEmpty()) {
            val parts = buildList {
                flips.forEach { add("${it.symbol} → ${it.signal.replace('_', ' ')}") }
                squeezes.forEach { add("${it.symbol} short pressure → ${it.squeeze?.uppercase()}") }
            }
            val n = flips.size + squeezes.size
            val title = if (n == 1) "1 signal changed overnight" else "$n signals changed overnight"
            // Per-batch id: a constant one made each new scan REPLACE an unread previous alert.
            AlertNotifier.notify(context, ("signal_scan:" + parts.joinToString(",")).hashCode(),
                                 title, parts.joinToString(", "))
        }
        // 200-week-line crosses — a stance-neutral "heads up" (below the line is long-term
        // mean-reversion context, NOT a buy signal); its own notification so it doesn't muddy flips.
        val crossed = scan.crossedBelow200wma.orEmpty()
        if (crossed.isNotEmpty()) {
            val n = crossed.size
            AlertNotifier.notify(
                context,
                ("wma_cross:" + crossed.joinToString(",")).hashCode(),
                if (n == 1) "1 name crossed below its 200-week line" else "$n names crossed below their 200-week line",
                crossed.joinToString(", "),
            )
        }
        // "Good time to add" dip alerts — a cue to add EXTRA cash on weakness, framed as such (never
        // "the bottom is in"). Deep dips get a distinct, more urgent title.
        val dipAlerts = scan.dipAlerts.orEmpty()
        if (dipAlerts.isNotEmpty()) {
            val hasMega = dipAlerts.any { it.dip == "mega_dip" }
            val body = dipAlerts.take(6).joinToString("\n") { a ->
                val tier = when (a.dip) {
                    "mega_dip" -> "rare deep dip"
                    "below_line" -> "below its 200-week line"
                    "oversold" -> "oversold"
                    "pullback_10" -> "−10% pullback"
                    else -> "small dip"
                }
                "${a.symbol.removeSuffix("-USD")}: $tier"
            }
            val title = if (hasMega) "📉 Deep dip — a moment to add extra" else "Good time to add"
            AlertNotifier.notify(context, ("dip_alerts:" + body).hashCode(), title, body)
        }
        // Key-date warnings get their own notification so they don't drown in signal noise.
        val dateAlerts = scan.dateAlerts.orEmpty()
        if (dateAlerts.isNotEmpty()) {
            AlertNotifier.notify(
                context,
                ("date_alerts:" + dateAlerts.joinToString(",")).hashCode(),
                "Market dates to watch",
                dateAlerts.joinToString("\n"),
            )
        }
        maybeWeeklyDigest(context, scan)
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

    /** Once every 7 days, post a roundup of the watchlist's current signals + short-pressure — a
     *  pulse even in weeks with no flips. Deduped by a stored timestamp. */
    private suspend fun maybeWeeklyDigest(context: Context, scan: com.stocktracker.app.data.remote.ScanLatest) {
        val results = scan.results?.takeIf { scan.hasScan }
        if (results.isNullOrEmpty()) return
        val store = ServiceLocator.settingsStore
        val last = store.lastDigestAt.first()
        val now = System.currentTimeMillis()
        if (now - last < 7L * 24 * 3600 * 1000) return

        val buys = results.filter { it.signal.contains("buy") }.sortedByDescending { it.conviction }
        val sells = results.filter { it.signal.contains("sell") }.sortedByDescending { it.conviction }
        val hot = results.filter { it.squeeze == "ignition" || it.squeeze == "fuel" }
        val belowLine = results.filter { it.below200wma == true }
        val lines = buildList {
            add("${results.size} tracked · ${buys.size} buy · ${sells.size} sell")
            if (buys.isNotEmpty()) add("Buy: " + buys.take(3).joinToString(", ") { "${it.symbol} ${it.conviction}" })
            if (sells.isNotEmpty()) add("Sell: " + sells.take(3).joinToString(", ") { "${it.symbol} ${it.conviction}" })
            if (hot.isNotEmpty()) add("Short pressure: " + hot.joinToString(", ") { "${it.symbol} ${it.squeeze?.uppercase()}" })
            if (belowLine.isNotEmpty()) add("Below 200-week line: " + belowLine.take(4).joinToString(", ") { it.symbol })
        }
        AlertNotifier.notify(context, "weekly_digest".hashCode(), "Weekly watchlist digest", lines.joinToString("\n"))
        store.setLastDigestAt(now)
    }

    private suspend fun pushWatchlist(base: String): Int {
        val assets = ServiceLocator.watchlistStore.snapshot()
        val stocks = assets.filter { it.type == AssetType.STOCK }.map { it.symbol.uppercase() }
        val cryptos = assets.filter { it.type == AssetType.CRYPTO }.map { "${it.symbol.uppercase()}-USD" }
        api.syncWatchlist(base, stocks, cryptos)
        return stocks.size + cryptos.size
    }
}
