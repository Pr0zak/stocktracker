package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.util.MarketHolidays
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

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
 */
object AiDailyBriefNotifier {

    private val ET = ZoneId.of("America/New_York")
    private const val WINDOW_START = 8 * 3600 + 30 * 60  // 08:30 ET
    private const val WINDOW_END = 10 * 3600             // 10:00 ET
    private val signalsApi = SignalsApiService()

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

        val brief = signalsApi.dailyBrief(url) ?: return
        val title = brief.title.trim()
        val body = brief.body.trim()
        if (title.isEmpty() && body.isEmpty()) return // nothing worth posting; try again next tick

        AlertNotifier.notifyBrief(
            context,
            "ai_daily_brief".hashCode(),
            title.ifEmpty { "Morning brief" },
            body,
        )
        settings.setLastDailyBriefDate(dateStr)
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
        val brief = signalsApi.dailyBrief(url) ?: return "Couldn't reach the brief service."
        val title = brief.title.trim()
        val body = brief.body.trim()
        if (title.isEmpty() && body.isEmpty()) return "The brief came back empty."
        AlertNotifier.notifyBrief(context, "ai_daily_brief".hashCode(), title.ifEmpty { "Morning brief" }, body)
        return null
    }
}
