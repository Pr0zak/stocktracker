package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.util.MarketHolidays
import com.stocktracker.app.ui.Routes
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * MONEY-7: the symbols the user actually holds (>0 shares), for [AiDailyBriefNotifier.heldSymbols] —
 * bare symbols only, never shares/cost/lot dates, and crypto sent as `<SYM>-USD` to match how the
 * backend's own watchlist/movers already name it (see cfg's `crypto_watchlist` in the Signals
 * service), so a held BTC position lines up with the same tape entry the brief's overlap check reads.
 *
 * A top-level pure function (rather than inlined in the suspend read) so the filter+mapping is unit
 * testable without a DataStore-backed [Asset] list.
 */
internal fun heldHoldingSymbols(assets: List<Asset>): List<String> =
    assets.filter { (it.shares ?: 0.0) > 0.0 }
        .map { a -> if (a.type == AssetType.CRYPTO) "${a.symbol.uppercase()}-USD" else a.symbol.uppercase() }

/**
 * Posts a once-per-trading-day AI morning brief (AIE-3): the tape, the user's watchlist names on the
 * move, and any catalyst landing today, written by the analyst into a notification title + a couple of
 * sentences (GET /daily_brief on the Signals service).
 *
 * Runs from the existing 15-minute [com.stocktracker.app.widget.WidgetRefreshWorker]. It fires in a
 * MORNING window (08:30–10:00 ET, straddling the pre-open and the first half hour), deduped by ET date
 * in settings so it lands at most once per trading day even though the worker ticks every 15 minutes.
 *
 * Opt-in and gated three ways: the brief switch, the master AI switch (it costs an LLM call), and a
 * configured Signals URL. Purely INFORMATIONAL — a read of the morning, not a trade signal.
 *
 * MONEY-7: [heldSymbols] sends the brief what the user actually OWNS, so it can say when today's tape
 * touches the book specifically rather than only the market in general.
 */
object AiDailyBriefNotifier {

    private val ET = ZoneId.of("America/New_York")
    private const val WINDOW_START = 8 * 3600 + 30 * 60  // 08:30 ET
    private const val WINDOW_END = 10 * 3600             // 10:00 ET
    private val signalsApi = SignalsApiService()

    /**
     * Reads [ServiceLocator.watchlistStore] directly rather than going through a ViewModel: this runs
     * from the background worker with no screen alive, the same reason [WidgetRefresh.refreshPortfolio]
     * does the same read for the portfolio widget. See [heldHoldingSymbols] for the (unit-tested)
     * filter + symbol mapping.
     */
    private suspend fun heldSymbols(): List<String> = heldHoldingSymbols(ServiceLocator.watchlistStore.snapshot())

    suspend fun check(context: Context) {
        val settings = ServiceLocator.settingsStore
        if (!settings.aiDailyBriefEnabled.first()) return
        if (!settings.aiAnalystEnabled.first()) return
        val url = settings.signalsApiUrl.first()
        if (url.isBlank()) return

        // Only in the morning window, on a trading day.
        val nowEt = ZonedDateTime.now(ET)
        val etDate = nowEt.toLocalDate()
        val weekend = nowEt.dayOfWeek == DayOfWeek.SATURDAY || nowEt.dayOfWeek == DayOfWeek.SUNDAY
        if (weekend || MarketHolidays.isMarketHoliday(etDate)) return
        val etSeconds = nowEt.hour * 3600 + nowEt.minute * 60 + nowEt.second
        if (etSeconds < WINDOW_START || etSeconds >= WINDOW_END) return

        // Dedup: at most once per ET day.
        val dateStr = etDate.toString() // yyyy-MM-dd
        if (settings.lastDailyBriefDate.first() == dateStr) return

        val brief = signalsApi.dailyBrief(url, holdings = heldSymbols()) ?: return
        val title = brief.title.trim()
        val body = brief.body.trim()
        if (title.isEmpty() && body.isEmpty()) return // nothing worth posting; try again next tick

        val delivered = AlertNotifier.notifyBrief(
            context,
            "ai_daily_brief".hashCode(),
            title.ifEmpty { "Morning brief" },
            body,
            Routes.WATCHLIST,
        )
        // NOTIF-1: only mark today's brief as sent if it was actually delivered — otherwise a blocked
        // notification (permission/app/channel) burns the once-per-day dedup on a brief nobody saw, and
        // it can't retry until tomorrow. Leaving the date unset lets the next 15-minute tick inside
        // today's morning window try again.
        if (delivered) settings.setLastDailyBriefDate(dateStr)
    }

    /**
     * Fetch + post the brief RIGHT NOW, ignoring the morning window and the once-a-day dedup — for the
     * "Send a test brief" button in Settings. Still needs a Signals URL (the AI switch is bypassed here
     * so a test always works). Returns null on success, or a short human-readable reason on failure.
     */
    suspend fun sendNow(context: Context): String? {
        val settings = ServiceLocator.settingsStore
        val url = settings.signalsApiUrl.first()
        if (url.isBlank()) return "Set the Signals service URL in Settings first."
        val brief = signalsApi.dailyBrief(url, holdings = heldSymbols()) ?: return "Couldn't reach the brief service."
        val title = brief.title.trim()
        val body = brief.body.trim()
        if (title.isEmpty() && body.isEmpty()) return "The brief came back empty."
        val delivered = AlertNotifier.notifyBrief(
            context, "ai_daily_brief".hashCode(), title.ifEmpty { "Morning brief" }, body, Routes.WATCHLIST,
        )
        // NOTIF-1 point 5: this used to ignore notifyBrief's result and always report success, so a
        // blocked/muted notification made "Send a test brief now" lie about having worked.
        return if (delivered) null else "Notifications are blocked."
    }
}
