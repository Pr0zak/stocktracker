package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.di.ServiceLocator
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

        for (asset in assets) {
            val alerts = asset.alerts ?: continue
            val quote = runCatching { ServiceLocator.repository.quote(asset) }.getOrNull()
                ?: ServiceLocator.priceCache.getQuote(asset.id)
                ?: continue
            val price = quote.price
            val pct = quote.changePercent
            val priceStr = Formatting.price(price, quote.currency, hideZeroCents)
            val subtitle = "${asset.displayName} · $priceStr (${Formatting.percent(pct)} today)"

            fun evaluate(name: String, triggered: Boolean, title: String) {
                val key = "${asset.id}:$name"
                if (triggered) {
                    if (fired.add(key)) AlertNotifier.notify(context, key.hashCode(), title, subtitle)
                } else {
                    fired.remove(key)
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
        }

        stateStore.save(fired)
    }
}
