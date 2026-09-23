package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.DailyPickLevels
import com.stocktracker.app.data.remote.DailyPickResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.Routes
import com.stocktracker.app.ui.pick.DailyPickRead
import com.stocktracker.app.util.MarketHolidays
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The journal note [com.stocktracker.app.ui.pick.DailyPickViewModel.logBought] stamps on its entries. */
internal const val DAILY_PICK_JOURNAL_NOTE = "Logged from the Daily Pick card."

/**
 * The Daily Pick's notifications, run from the 15-minute [com.stocktracker.app.widget.WidgetRefreshWorker]:
 *
 * * DP-7 — the morning "today's pick" (or "no pick today"), 08:30–10:00 ET, once per trading day, with
 *   DP-14's report cards on past picks folded in as extra lines so the morning is one buzz, not two.
 * * DP-10 — intraday alerts during regular hours when today's pick crosses into its buy zone, runs past
 *   it, or reaches its stop or target; and stop/target alerts on earlier picks the user logged as bought
 *   and has not closed in the journal.
 *
 * Nothing is sent from a failed or stale run. A failed run is shown on the card; pushing it as "no pick
 * today" would be the absent-as-empty defect in notification form. Every dedupe mark is written only
 * after the notification was actually delivered, so a blocked post retries on the next tick.
 */
object DailyPickNotifier {

    private val ET = ZoneId.of("America/New_York")
    private const val MORNING_START = 8 * 3600 + 30 * 60
    private const val MORNING_END = 10 * 3600
    private const val OPEN = 9 * 3600 + 30 * 60
    private const val CLOSE = 16 * 3600
    private val api = SignalsApiService()
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    suspend fun check(context: Context) {
        val settings = ServiceLocator.settingsStore
        val url = settings.signalsApiUrl.first()
        if (url.isBlank()) return
        val now = ZonedDateTime.now(ET)
        val day = now.toLocalDate()
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY || MarketHolidays.isMarketHoliday(day)) return
        val secs = now.hour * 3600 + now.minute * 60
        val today = day.toString()

        val wantMorning = settings.dailyPickNotifyEnabled.first() && secs in MORNING_START until MORNING_END &&
            settings.lastDailyPickNotifyDate.first() != today
        val wantAlerts = settings.dailyPickAlertsEnabled.first() && secs in OPEN until CLOSE
        if (!wantMorning && !wantAlerts) return

        val resp = runCatching { api.dailyPick(url) }.getOrNull()
        if (wantMorning) morning(context, url, resp, today, day)
        if (wantAlerts) {
            if (resp != null) intraday(context, resp, today, now)
            heldEarlierPicks(context, today, now)
        }
    }

    private suspend fun morning(context: Context, url: String, resp: DailyPickResponse?, today: String, day: LocalDate) {
        val settings = ServiceLocator.settingsStore
        val note = DailyPickRead.morningNote(resp, today) ?: return
        val delivered = settings.dailyPickReportCards.first()
        val history = runCatching { api.dailyPickHistory(url, limit = 40) }.getOrNull()
        val due = history?.reportCards.orEmpty().filter { c ->
            val key = "${c.date}|${c.horizonSessions}"
            val age = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(c.date), day) }.getOrDefault(999)
            // A 5-day card is only "last week's" for a week or so; a card that old is history, not news.
            val fresh = if (c.horizonSessions == 5) age <= 12 else age <= 40
            key !in delivered && fresh && c.fwdPct != null
        }.take(2)
        val lines = due.mapNotNull { DailyPickRead.reportCardLine(it) }
        val body = (listOf(note.body) + lines).joinToString("\n")
        val ok = AlertNotifier.notifyPick(context, "daily_pick_morning".hashCode(), note.title, body, Routes.WATCHLIST)
        if (ok) {
            settings.setLastDailyPickNotifyDate(today)
            if (due.isNotEmpty()) settings.setDailyPickReportCards(delivered + due.map { "${it.date}|${it.horizonSessions}" })
        }
    }

    private suspend fun intraday(context: Context, resp: DailyPickResponse, today: String, now: ZonedDateTime) {
        if (!resp.isPick || resp.stale == true || resp.date != today) return
        // Alerts compare against the REGULAR-session price and only while the regular session is on.
        if (resp.live?.marketState?.uppercase(Locale.US) != "REGULAR") return
        val p = resp.pick ?: return
        val sym = p.symbol ?: return
        val price = resp.live.regularPrice ?: return
        val mine = ServiceLocator.verdictJournalStore.snapshot().any {
            it.symbol == sym && it.verdictDateIso == today && it.isTaken && it.exitPrice == null
        }
        fire(context, today, sym, price, p.levels, mine, now, zoneAlerts = true)
    }

    /** Stop/target alerts on earlier picks the user logged as bought and has not closed. */
    private suspend fun heldEarlierPicks(context: Context, today: String, now: ZonedDateTime) {
        val open = ServiceLocator.verdictJournalStore.snapshot().filter {
            it.notes == DAILY_PICK_JOURNAL_NOTE && it.isTaken && it.exitPrice == null && it.verdictDateIso != today
        }
        for (e in open.distinctBy { it.symbol }) {
            if (e.plan.stop == null && e.plan.target == null) continue
            val q = runCatching {
                ServiceLocator.repository.quote(Asset(e.symbol, AssetType.STOCK, e.symbol))
            }.getOrNull() ?: continue
            val levels = DailyPickLevels(stop = e.plan.stop, target = e.plan.target)
            fire(context, today, e.symbol, q.price, levels, mine = true, now = now, zoneAlerts = false)
        }
    }

    private suspend fun fire(
        context: Context, today: String, sym: String, price: Double, levels: DailyPickLevels?,
        mine: Boolean, now: ZonedDateTime, zoneAlerts: Boolean,
    ) {
        val settings = ServiceLocator.settingsStore
        val log = settings.dailyPickAlertLog.first()
        val state = DailyPickRead.priceState(price, levels)
        val prev = DailyPickRead.lastState(log, today, sym)
        val sent = DailyPickRead.sentAlerts(log, today, sym)
        val alert = DailyPickRead.alertFor(prev, state, sent)?.takeIf {
            zoneAlerts || it == DailyPickRead.Alert.HIT_STOP || it == DailyPickRead.Alert.HIT_TARGET
        }
        var fired: DailyPickRead.Alert? = null
        if (alert != null) {
            val note = DailyPickRead.alertNote(alert, sym, price, levels, now.format(TIME) + " ET", mine)
            val ok = AlertNotifier.notifyPick(
                context, "daily_pick_alert_${sym}_${alert.name}".hashCode(), note.title, note.body, Routes.WATCHLIST,
            )
            if (ok) fired = alert
        }
        // An alert that could not be delivered (notifications blocked) keeps the PREVIOUS state, so the
        // crossing is still a crossing on the next tick instead of being silently consumed.
        val recorded = if (alert != null && fired == null) prev else state
        settings.setDailyPickAlertLog(DailyPickRead.nextLog(log, today, sym, recorded, fired))
    }
}
