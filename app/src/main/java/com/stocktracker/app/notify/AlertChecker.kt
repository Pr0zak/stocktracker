package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.portfolio.STALE_QUOTE_MS
import com.stocktracker.app.util.Formatting
import kotlinx.coroutines.flow.first

/** Evaluates each watchlist asset's alert thresholds and posts notifications on fresh crossings. */
object AlertChecker {

    suspend fun check(context: Context) {
        val assets = ServiceLocator.watchlistStore.snapshot().filter { it.alerts?.isEmpty == false }
        if (assets.isEmpty()) return

        AlertNotifier.ensureChannel(context)
        val stateStore = AlertStateStore(context)
        val fired = stateStore.fired().toMutableSet()
        val hideZeroCents = ServiceLocator.settingsStore.hideZeroCents.first()
        val now = System.currentTimeMillis()

        for (asset in assets) {
            val alerts = asset.alerts ?: continue
            // A cached quote with no age bound could be days old, so a threshold "crossing" could be
            // announced from a price that hasn't been current since a previous session. PriceCache
            // has no TTL, so the bound has to be applied here.
            val quote = runCatching { ServiceLocator.repository.quote(asset) }.getOrNull()
                ?: ServiceLocator.priceCache.getQuote(asset.id)
                    ?.takeIf { it.asOfEpochMs <= 0L || now - it.asOfEpochMs <= STALE_QUOTE_MS }
                ?: continue
            val price = quote.price
            val pct = quote.changePercent
            val priceStr = Formatting.price(price, quote.currency, hideZeroCents)
            val subtitle = "${asset.displayName} · $priceStr (${Formatting.percent(pct)} today)"

            var changed = false
            fun evaluate(name: String, triggered: Boolean, title: String) {
                val key = "${asset.id}:$name"
                if (triggered) {
                    if (fired.add(key)) {
                        AlertNotifier.notify(context, key.hashCode(), title, subtitle)
                        changed = true
                    }
                } else {
                    if (fired.remove(key)) changed = true
                }
            }

            alerts.priceAbove?.let {
                evaluate("above", price >= it, "${asset.symbol} rose above ${Formatting.price(it, quote.currency, hideZeroCents)}")
            }
            alerts.priceBelow?.let {
                evaluate("below", price <= it, "${asset.symbol} fell below ${Formatting.price(it, quote.currency, hideZeroCents)}")
            }
            alerts.percentUp?.let {
                evaluate("up", pct >= it, "${asset.symbol} up ${Formatting.percent(pct)} today")
            }
            alerts.percentDown?.let {
                evaluate("down", pct <= -it, "${asset.symbol} down ${Formatting.percent(pct)} today")
            }

            // Persist per ASSET, not once at the end. Notifications are posted inside this loop and
            // each remaining iteration makes a sequential network call, so saving only after the
            // whole loop meant a crash, a kill, or a doze-killed worker discarded the record of
            // every alert already delivered — and the next run re-announced all of them. The
            // remaining replay window is now the four evaluations of a single asset, which have no
            // I/O between them.
            if (changed) stateStore.save(fired)
        }

        stateStore.save(fired)
    }
}
