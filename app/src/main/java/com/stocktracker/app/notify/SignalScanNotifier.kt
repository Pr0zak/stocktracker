package com.stocktracker.app.notify

import android.content.Context
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

        val scan = runCatching { api.latestScan(base) }.getOrNull() ?: return
        val generated = scan.generatedAt?.toLong() ?: return
        val last = ServiceLocator.settingsStore.lastScanNotifiedAt.first()
        if (generated <= last) return // already processed this scan

        val flips = scan.results.filter { it.flipped }
        if (flips.isNotEmpty()) {
            val text = flips.joinToString(", ") { "${it.symbol} → ${it.signal.replace('_', ' ')}" }
            val title = if (flips.size == 1) "1 signal changed overnight" else "${flips.size} signals changed overnight"
            AlertNotifier.notify(context, "signal_scan".hashCode(), title, text)
        }
        ServiceLocator.settingsStore.setLastScanNotifiedAt(generated)
    }
}
