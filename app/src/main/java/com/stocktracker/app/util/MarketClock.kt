package com.stocktracker.app.util

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

enum class MarketPhase { PRE, REGULAR, AFTER, CLOSED }

/** A session boundary as a timeline fraction (0f..1f) + a label in the device's timezone. */
data class SessionTick(val fraction: Float, val label: String)

data class MarketState(
    val phase: MarketPhase,
    val label: String,
    /** Position of "now" across the 4:00a–8:00p (ET) window (0f..1f); null when closed. */
    val markerFraction: Float?,
    val ticks: List<SessionTick>,
) {
    val isOpen: Boolean get() = phase == MarketPhase.REGULAR
}

/**
 * US equity session state (regular 9:30–16:00 ET, pre 4:00–9:30, after 16:00–20:00, Mon–Fri,
 * excluding [MarketHolidays]). Phase is derived from the actual instant so it's correct in any
 * timezone; the tick labels are rendered in the device's local timezone.
 */
object MarketClock {

    private val ET = ZoneId.of("America/New_York")

    const val WINDOW_START = 4 * 3600          // 4:00a ET
    const val PRE_END = 9 * 3600 + 30 * 60     // 9:30a ET
    const val REG_END = 16 * 3600              // 4:00p ET
    const val WINDOW_END = 20 * 3600           // 8:00p ET

    val preEndFraction = (PRE_END - WINDOW_START).toFloat() / (WINDOW_END - WINDOW_START)
    val regEndFraction = (REG_END - WINDOW_START).toFloat() / (WINDOW_END - WINDOW_START)

    fun now(
        exchangeZone: ZoneId = ET,
        deviceZone: ZoneId = ZoneId.systemDefault(),
    ): MarketState {
        val nowEt = ZonedDateTime.now(exchangeZone)
        val etDate = nowEt.toLocalDate()
        val weekend = nowEt.dayOfWeek == DayOfWeek.SATURDAY || nowEt.dayOfWeek == DayOfWeek.SUNDAY
        val holiday = MarketHolidays.isMarketHoliday(etDate)
        val sod = nowEt.hour * 3600 + nowEt.minute * 60 + nowEt.second

        val phase = when {
            weekend || holiday -> MarketPhase.CLOSED
            sod < WINDOW_START || sod >= WINDOW_END -> MarketPhase.CLOSED
            sod < PRE_END -> MarketPhase.PRE
            sod < REG_END -> MarketPhase.REGULAR
            else -> MarketPhase.AFTER
        }
        val label = when (phase) {
            MarketPhase.REGULAR -> "Market Open"
            MarketPhase.PRE -> "Pre-market"
            MarketPhase.AFTER -> "After-hours"
            MarketPhase.CLOSED -> if (holiday) "Market Holiday" else "Market Closed"
        }
        val marker = if (phase == MarketPhase.CLOSED) {
            null
        } else {
            ((sod - WINDOW_START).toFloat() / (WINDOW_END - WINDOW_START)).coerceIn(0f, 1f)
        }

        // Boundary times converted from ET into the device's timezone for display.
        fun tick(hour: Int, minute: Int, fraction: Float): SessionTick {
            val local = etDate.atTime(hour, minute).atZone(exchangeZone).withZoneSameInstant(deviceZone)
            val h12 = ((local.hour + 11) % 12) + 1
            val suffix = if (local.hour < 12) "a" else "p"
            return SessionTick(fraction, "%d:%02d%s".format(h12, local.minute, suffix))
        }
        val ticks = listOf(
            tick(4, 0, 0f),
            tick(9, 30, preEndFraction),
            tick(16, 0, regEndFraction),
            tick(20, 0, 1f),
        )

        return MarketState(phase, label, marker, ticks)
    }
}
