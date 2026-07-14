package com.stocktracker.app.util

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

enum class MarketPhase { PRE, REGULAR, AFTER, CLOSED }

data class MarketState(
    val phase: MarketPhase,
    val label: String,
    /** Position of "now" across the 4:00a–8:00p window (0f..1f); null when closed. */
    val markerFraction: Float?,
) {
    val isOpen: Boolean get() = phase == MarketPhase.REGULAR
}

/**
 * US equity session state (regular 9:30–16:00 ET, pre 4:00–9:30, after 16:00–20:00, Mon–Fri).
 * Holidays are not accounted for in this version.
 */
object MarketClock {

    private val ET = ZoneId.of("America/New_York")

    const val WINDOW_START = 4 * 3600          // 4:00a
    const val PRE_END = 9 * 3600 + 30 * 60     // 9:30a
    const val REG_END = 16 * 3600              // 4:00p
    const val WINDOW_END = 20 * 3600           // 8:00p

    /** Fraction of the timeline where the regular session begins (9:30a) / ends (4:00p). */
    val preEndFraction = (PRE_END - WINDOW_START).toFloat() / (WINDOW_END - WINDOW_START)
    val regEndFraction = (REG_END - WINDOW_START).toFloat() / (WINDOW_END - WINDOW_START)

    fun now(zone: ZoneId = ET): MarketState {
        val zdt = ZonedDateTime.now(zone)
        val weekend = zdt.dayOfWeek == DayOfWeek.SATURDAY || zdt.dayOfWeek == DayOfWeek.SUNDAY
        val sod = zdt.hour * 3600 + zdt.minute * 60 + zdt.second
        val phase = when {
            weekend -> MarketPhase.CLOSED
            sod < WINDOW_START || sod >= WINDOW_END -> MarketPhase.CLOSED
            sod < PRE_END -> MarketPhase.PRE
            sod < REG_END -> MarketPhase.REGULAR
            else -> MarketPhase.AFTER
        }
        val label = when (phase) {
            MarketPhase.REGULAR -> "Market Open"
            MarketPhase.PRE -> "Pre-market"
            MarketPhase.AFTER -> "After-hours"
            MarketPhase.CLOSED -> "Market Closed"
        }
        val marker = if (phase == MarketPhase.CLOSED) {
            null
        } else {
            ((sod - WINDOW_START).toFloat() / (WINDOW_END - WINDOW_START)).coerceIn(0f, 1f)
        }
        return MarketState(phase, label, marker)
    }
}
