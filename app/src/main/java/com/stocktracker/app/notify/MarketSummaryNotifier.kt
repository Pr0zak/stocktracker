package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.util.MarketClock
import com.stocktracker.app.util.MarketHolidays
import com.stocktracker.app.util.MarketPhase
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Posts a once-per-trading-day recap of the watchlist's biggest movers:
 *  - at the close (any time in the 16:00–20:00 ET after-hours window), a day-change recap; and
 *  - after the post session ends (≥ 20:00 ET), an after-hours-move recap.
 *
 * Runs from the existing 15-minute [com.stocktracker.app.widget.WidgetRefreshWorker]. Dedup is by ET
 * date string in settings, so each recap fires at most once per day even though the worker ticks
 * every 15 minutes. Crypto is excluded (no US session). Off when [marketSummaryEnabled] is false.
 *
 * Purely INFORMATIONAL — a snapshot of movers, not a trade signal. Decision logic lives in the pure
 * [MarketSummary]; this shell just gathers data, times it, and posts.
 */
object MarketSummaryNotifier {

    private val ET = ZoneId.of("America/New_York")

    suspend fun check(context: Context) {
        val settings = ServiceLocator.settingsStore
        if (!settings.marketSummaryEnabled.first()) return

        val nowEt = ZonedDateTime.now(ET)
        val etDate = nowEt.toLocalDate()
        val etSeconds = nowEt.hour * 3600 + nowEt.minute * 60 + nowEt.second
        val weekend = nowEt.dayOfWeek == DayOfWeek.SATURDAY || nowEt.dayOfWeek == DayOfWeek.SUNDAY
        val isTradingDay = !weekend && !MarketHolidays.isMarketHoliday(etDate)
        val phase = MarketClock.now().phase

        // Cheap pre-checks so we don't fetch quotes outside a firing window or after we've already sent.
        val inCloseWindow = phase == MarketPhase.AFTER
        val inAfterHoursWindow =
            phase == MarketPhase.CLOSED && etSeconds >= MarketClock.WINDOW_END && isTradingDay
        if (!inCloseWindow && !inAfterHoursWindow) return

        val dateStr = etDate.toString() // yyyy-MM-dd
        val alreadyClose = settings.lastCloseSummaryDate.first() == dateStr
        val alreadyAfterHours = settings.lastAfterHoursSummaryDate.first() == dateStr
        if (inCloseWindow && alreadyClose) return
        if (inAfterHoursWindow && alreadyAfterHours) return

        // Stocks + ETFs only — both are AssetType.STOCK (ETF-ness is a runtime Yahoo classification).
        val stocks = ServiceLocator.watchlistStore.snapshot().filter { it.type == AssetType.STOCK }
        if (stocks.isEmpty()) return

        val movers = stocks.mapNotNull { asset ->
            val q = runCatching { ServiceLocator.repository.quote(asset) }.getOrNull()
                ?: ServiceLocator.priceCache.getQuote(asset.id)
                ?: return@mapNotNull null
            Mover(asset.symbol.uppercase(), q.changePercent, q.postMarketChangePercent)
        }

        val summary = MarketSummary.build(
            phase = phase,
            etSecondsOfDay = etSeconds,
            isTradingDay = isTradingDay,
            movers = movers,
            alreadySentClose = alreadyClose,
            alreadySentAfterHours = alreadyAfterHours,
        ) ?: return

        val notifId = when (summary.kind) {
            MoverSummary.Kind.CLOSE -> "market_close".hashCode()
            MoverSummary.Kind.AFTER_HOURS -> "market_after_hours".hashCode()
        }
        AlertNotifier.notifyMarket(context, notifId, summary.title, summary.body)

        when (summary.kind) {
            MoverSummary.Kind.CLOSE -> settings.setLastCloseSummaryDate(dateStr)
            MoverSummary.Kind.AFTER_HOURS -> settings.setLastAfterHoursSummaryDate(dateStr)
        }
    }
}
