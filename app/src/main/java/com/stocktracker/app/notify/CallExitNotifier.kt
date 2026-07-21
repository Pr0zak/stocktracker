package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.first

/**
 * OC-4: for each tracked long call (OC-3), re-price it via /option_quote and post a notification when a
 * NEW exit condition trips (take-profit / stop / time-stop / expiry). Runs from the existing 15-minute
 * WidgetRefreshWorker.
 *
 * Deduped via [CallAlertStateStore] so an alert fires once per crossing, clears when its condition
 * clears (re-fires on a re-cross), and drops keys of deleted positions. A failed re-price soft-fails —
 * the P/L rules are skipped for that contract, the DTE rules still run, and the worker never crashes.
 *
 * No-op when no Signals URL is set (needs /option_quote). LLM-free — unaffected by the AI kill-switch.
 */
object CallExitNotifier {

    private val api = SignalsApiService()

    suspend fun check(context: Context) {
        val base = ServiceLocator.settingsStore.signalsApiUrl.first()
        if (base.isBlank()) return // gate: /option_quote requires a configured Signals service

        val positions = ServiceLocator.callPositionStore.snapshot()
        val stateStore = CallAlertStateStore(context)
        val fired = stateStore.fired().toMutableSet()

        // Drop dedupe keys for positions that no longer exist (deleted), so state can't leak forever.
        val liveIds = positions.map { it.id }.toSet()
        fired.retainAll { k -> liveIds.any { id -> k.startsWith("$id:") } }

        if (positions.isEmpty()) {
            stateStore.save(fired)
            return
        }
        AlertNotifier.ensureChannel(context)

        for (p in positions) {
            // Re-price; soft-fail on ANY error so one gone/closed contract never crashes the worker.
            val quote = runCatching { api.optionQuote(base, p.symbol, p.expiryTs, p.strike, p.type) }.getOrNull()
            val premium = quote?.contract?.currentPrice
            val itm = quote?.contract?.inTheMoney

            val alerts = CallExitRules.evaluate(p, premium, quote?.spot, itm)
            val activeKeys = alerts.map { it.key }.toSet()

            // Notify each NEWLY-true alert (fired.add is false when the key was already present).
            for (alert in alerts) {
                if (fired.add(alert.key)) {
                    AlertNotifier.notify(context, alert.key.hashCode(), alert.title, alert.message)
                }
            }

            // Clear this position's keys whose condition is no longer met — but ONLY for rule types we
            // could actually assess this run (the P/L rules can't be judged without a live premium, so
            // leave their keys untouched on a failed re-price rather than re-firing them next time).
            val assessable = mutableSetOf(CallExitAlert.Type.TIME_STOP, CallExitAlert.Type.EXPIRY)
            if (premium != null) {
                assessable += CallExitAlert.Type.TAKE_PROFIT
                assessable += CallExitAlert.Type.STOP
            }
            fired.removeAll { k ->
                k !in activeKeys && assessable.any { type -> k == CallExitAlert.keyFor(p.id, type) }
            }
        }

        stateStore.save(fired)
    }
}
