package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.Routes
import com.stocktracker.app.util.MarketCalendar
import com.stocktracker.app.util.MarketCalendar.Hours
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Tells the user when a market is shut or closes early, so a quiet ticker or a bond fund that did
 * not move is not mistaken for a broken app — and so a trade or transfer is not planned for a day
 * it cannot happen.
 *
 *  - The morning of (06:00–12:00 ET): any day stocks or bonds are closed or close early.
 *  - The evening before (16:00–22:00 ET on the prior weekday): a heads-up, only when STOCKS are
 *    closed or close early, since that is the one a user acts on ahead of time.
 *
 * Runs from the 15-minute [com.stocktracker.app.widget.WidgetRefreshWorker]; deduped by the date the
 * notice is about, and recorded as sent only once actually delivered. Times are shown in the phone's
 * timezone. The wording is in the pure [MarketHoursText].
 */
object MarketHoursNotifier {

    private val ET = ZoneId.of("America/New_York")

    suspend fun check(context: Context) {
        val settings = ServiceLocator.settingsStore
        if (!settings.marketHoursNotifyEnabled.first()) return

        val nowEt = ZonedDateTime.now(ET)
        val today = nowEt.toLocalDate()
        val t = nowEt.toLocalTime()
        val zone = ZoneId.systemDefault()

        if (t >= LocalTime.of(6, 0) && t < LocalTime.of(12, 0)) {
            val day = MarketCalendar.day(today) ?: return
            if (!day.unusual || settings.lastMarketHoursTodayDate.first() == today.toString()) return
            val (title, body) = MarketHoursText.today(day, zone)
            if (AlertNotifier.notifyMarketHours(context, "market_hours_today".hashCode(), title, body, Routes.WATCHLIST)) {
                settings.setLastMarketHoursTodayDate(today.toString())
            }
        } else if (t >= LocalTime.of(16, 0) && t < LocalTime.of(22, 0) && MarketCalendar.day(today) != null) {
            val next = MarketCalendar.day(MarketCalendar.nextWeekday(today)) ?: return
            if (next.stocks == Hours.OPEN || settings.lastMarketHoursAheadDate.first() == next.date.toString()) return
            val (title, body) = MarketHoursText.ahead(next, today, zone)
            if (AlertNotifier.notifyMarketHours(context, "market_hours_ahead".hashCode(), title, body, Routes.WATCHLIST)) {
                settings.setLastMarketHoursAheadDate(next.date.toString())
            }
        }
    }
}

/** The notice wording, kept pure so it can be unit-tested. */
object MarketHoursText {

    private val ET = ZoneId.of("America/New_York")
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** An ET close time on [date], as a clock time in [zone]. */
    internal fun clock(date: LocalDate, et: LocalTime, zone: ZoneId): String =
        date.atTime(et).atZone(ET).withZoneSameInstant(zone).format(CLOCK)

    fun today(day: MarketCalendar.Day, zone: ZoneId): Pair<String, String> {
        val why = day.reason?.let { " — $it" }.orEmpty()
        val stockClose = clock(day.date, MarketCalendar.STOCK_EARLY_CLOSE, zone)
        val bondClose = clock(day.date, MarketCalendar.BOND_EARLY_CLOSE, zone)
        val title = when (day.stocks) {
            Hours.CLOSED -> "Stock market closed today$why"
            Hours.EARLY_CLOSE -> "Stocks close early today at $stockClose$why"
            Hours.OPEN -> when (day.bonds) {
                Hours.CLOSED -> "Bond market closed today$why"
                else -> "Bond market closes early today at $bondClose$why"
            }
        }
        val lines = buildList {
            when (day.stocks) {
                Hours.CLOSED -> add("Stocks don't trade today, so prices hold at the last close.")
                Hours.OPEN -> add("Stocks trade normal hours.")
                Hours.EARLY_CLOSE -> {}
            }
            when (day.bonds) {
                Hours.CLOSED -> add(
                    if (day.stocks == Hours.CLOSED) "The bond market is closed too."
                    else "Treasury and bond prices won't update.",
                )
                Hours.EARLY_CLOSE -> add("The bond market closes at $bondClose.")
                Hours.OPEN -> {}
            }
            if (day.banksClosed) add("Banks are closed, so transfers settle on the next business day.")
            if (day.stocks == Hours.CLOSED) add(reopens(day.date, zone))
        }
        return title to lines.joinToString(" ")
    }

    /** The heads-up for [next], sent on [today]. Only called when stocks are not on normal hours. */
    fun ahead(next: MarketCalendar.Day, today: LocalDate, zone: ZoneId): Pair<String, String> {
        val whenWord = if (next.date == today.plusDays(1)) "Tomorrow" else dayName(next.date)
        val why = next.reason?.let { " — $it" }.orEmpty()
        val stockClose = clock(next.date, MarketCalendar.STOCK_EARLY_CLOSE, zone)
        val title = when (next.stocks) {
            Hours.CLOSED -> "$whenWord: stock market closed$why"
            else -> "$whenWord: stocks close early at $stockClose$why"
        }
        val lines = buildList {
            if (next.stocks == Hours.CLOSED) {
                add(reopens(next.date, zone))
            } else {
                add("Last chance to trade is $stockClose.")
            }
            when (next.bonds) {
                Hours.CLOSED -> if (next.stocks != Hours.CLOSED) add("The bond market is closed.")
                Hours.EARLY_CLOSE -> add(
                    "The bond market closes at ${clock(next.date, MarketCalendar.BOND_EARLY_CLOSE, zone)}.",
                )
                Hours.OPEN -> {}
            }
        }
        return title to lines.joinToString(" ")
    }

    /**
     * The one-line in-app banner for [now] (in ET), or null when there is nothing to say. Today's
     * closure or early close wins; otherwise, after the 4:00 PM ET close or over a weekend, the next
     * weekday's stock closure or early close.
     */
    fun banner(now: ZonedDateTime, zone: ZoneId): String? {
        val nowEt = now.withZoneSameInstant(ET)
        val today = nowEt.toLocalDate()
        MarketCalendar.day(today)?.takeIf { it.unusual }?.let { d ->
            val why = d.reason?.let { " · $it" }.orEmpty()
            return when (d.stocks) {
                Hours.CLOSED -> "Stock market closed today$why"
                Hours.EARLY_CLOSE -> "Stocks close early today at ${clock(today, MarketCalendar.STOCK_EARLY_CLOSE, zone)}$why"
                Hours.OPEN -> if (d.bonds == Hours.CLOSED) {
                    "Bond market closed$why · stocks open"
                } else {
                    "Bonds close at ${clock(today, MarketCalendar.BOND_EARLY_CLOSE, zone)} · stocks open"
                }
            }
        }
        val afterClose = MarketCalendar.day(today) == null || nowEt.toLocalTime() >= LocalTime.of(16, 0)
        if (!afterClose) return null
        val next = MarketCalendar.day(MarketCalendar.nextWeekday(today)) ?: return null
        if (next.stocks == Hours.OPEN) return null
        val whenWord = if (next.date == today.plusDays(1)) "Tomorrow" else dayName(next.date)
        val why = next.reason?.let { " · $it" }.orEmpty()
        return when (next.stocks) {
            Hours.CLOSED -> "$whenWord: stock market closed$why"
            else -> "$whenWord: stocks close early at ${clock(next.date, MarketCalendar.STOCK_EARLY_CLOSE, zone)}$why"
        }
    }

    private fun reopens(closed: LocalDate, zone: ZoneId): String {
        val back = MarketCalendar.nextStockSession(closed)
        val early = MarketCalendar.day(back)?.stocks == Hours.EARLY_CLOSE
        val tail = if (early) {
            ", closing early at ${clock(back, MarketCalendar.STOCK_EARLY_CLOSE, zone)}"
        } else {
            ""
        }
        return "Stocks reopen ${dayName(back)}$tail."
    }

    private fun dayName(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
}
