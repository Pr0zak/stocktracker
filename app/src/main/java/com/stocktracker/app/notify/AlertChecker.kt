package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.data.model.ChartRange
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

            // ----- Technical conditions -----
            //
            // Fetched once per asset, and only when a condition is armed, because this is a year of
            // daily bars against a 5-minute history TTL in a worker that runs every 15 minutes and is
            // usually cold: a naive loop would refetch ~96x/symbol/day to observe at most one state
            // change. THREE_YEAR rather than whatever the chart last showed — ChartRange.ALL is
            // weekly, so an SMA(200) computed there is a 200-WEEK average wearing a 200-day label.
            if (alerts.conditions.isNotEmpty()) {
                val bars = runCatching {
                    ServiceLocator.repository.history(asset, ChartRange.THREE_YEAR)
                }.getOrNull()

                for (cond in alerts.conditions) {
                    val key = "${asset.id}:cond:${cond.key}"
                    val result = if (bars.isNullOrEmpty()) {
                        ConditionResult.CouldNotCheck("price history could not be loaded")
                    } else {
                        AlertConditions.evaluate(cond, bars, now)
                    }
                    when (result) {
                        is ConditionResult.Triggered ->
                            if (fired.add(key)) {
                                AlertNotifier.notify(
                                    context, key.hashCode(),
                                    "${asset.symbol} ${cond.label.replaceFirstChar { it.lowercase() }}",
                                    subtitle,
                                )
                                changed = true
                            }
                        is ConditionResult.NotTriggered ->
                            if (fired.remove(key)) changed = true
                        is ConditionResult.CouldNotCheck -> {
                            // A condition that cannot be evaluated must not read as one that did not
                            // fire. Leaving the fired key in place would also re-announce it the
                            // moment data returns, so the state is held and the user is told once.
                            val warnKey = "$key:unchecked"
                            if (fired.add(warnKey)) {
                                AlertNotifier.notify(
                                    context, warnKey.hashCode(),
                                    "${asset.symbol}: alert could not be checked",
                                    "${cond.label} — ${result.reason}",
                                )
                                changed = true
                            }
                        }
                    }
                    if (result !is ConditionResult.CouldNotCheck) {
                        if (fired.remove("$key:unchecked")) changed = true
                    }
                }
            }

            // A deleted condition leaves its fired key behind, and a key left set suppresses the
            // first legitimate fire after the condition is re-armed. Prune anything this asset no
            // longer arms.
            val liveKeys = alerts.conditions.flatMap {
                listOf("${asset.id}:cond:${it.key}", "${asset.id}:cond:${it.key}:unchecked")
            }.toSet()
            val stale = fired.filter { it.startsWith("${asset.id}:cond:") && it !in liveKeys }
            if (stale.isNotEmpty()) {
                fired.removeAll(stale.toSet())
                changed = true
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
