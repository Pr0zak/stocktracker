package com.stocktracker.app.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month

/**
 * What a weekday looks like for the two US markets a user cares about: stocks (NYSE/Nasdaq) and
 * bonds (the SIFMA-recommended schedule the Treasury market follows), plus whether banks are shut.
 *
 * Everything is computed by rule so it never goes stale. Two limits worth knowing:
 *  - SIFMA's schedule is a yearly *recommendation*. It occasionally deviates from the rule (most
 *    often an early close instead of a full close on a Good Friday that falls on a jobs-report day).
 *  - Unscheduled closures (a national day of mourning, a storm) cannot be predicted at all.
 */
object MarketCalendar {

    val STOCK_EARLY_CLOSE: LocalTime = LocalTime.of(13, 0) // ET
    val BOND_EARLY_CLOSE: LocalTime = LocalTime.of(14, 0)  // ET

    enum class Hours { OPEN, EARLY_CLOSE, CLOSED }

    data class Day(
        val date: LocalDate,
        val stocks: Hours,
        val bonds: Hours,
        /** The holiday behind a closure or early close, e.g. "Thanksgiving" or "Christmas Eve". */
        val reason: String?,
        /** Federal Reserve banks closed while stocks trade (Columbus Day, Veterans Day). */
        val banksClosed: Boolean,
    ) {
        /** True when either market departs from its normal full day. */
        val unusual: Boolean get() = stocks != Hours.OPEN || bonds != Hours.OPEN
    }

    /** Null on weekends: both markets are always shut then, which is not news. */
    fun day(date: LocalDate): Day? {
        if (isWeekend(date)) return null
        val stockHoliday = MarketHolidays.isMarketHoliday(date)
        val bondHoliday = bondHolidays(date.year)[date]
        val stockEarly = stockEarlyCloses(date.year)[date]
        val bondEarly = bondEarlyCloses(date.year)[date]
        val stocks = when {
            stockHoliday -> Hours.CLOSED
            stockEarly != null -> Hours.EARLY_CLOSE
            else -> Hours.OPEN
        }
        val bonds = when {
            bondHoliday != null -> Hours.CLOSED
            bondEarly != null -> Hours.EARLY_CLOSE
            else -> Hours.OPEN
        }
        // Banks follow the Fed, which does not move a Saturday holiday to Friday, so only the real date counts.
        val banksClosed = stocks != Hours.CLOSED && bondHoliday != null &&
            (bondHoliday == COLUMBUS || (bondHoliday == VETERANS && date.monthValue == 11 && date.dayOfMonth == 11))
        return Day(date, stocks, bonds, bondHoliday ?: stockEarly ?: bondEarly, banksClosed)
    }

    /** The next weekday after [date] (Friday → Monday). */
    fun nextWeekday(date: LocalDate): LocalDate {
        var d = date.plusDays(1)
        while (isWeekend(d)) d = d.plusDays(1)
        return d
    }

    /** The next day stocks trade at all, after [date]. */
    fun nextStockSession(date: LocalDate): LocalDate {
        var d = date.plusDays(1)
        while (isWeekend(d) || MarketHolidays.isMarketHoliday(d)) d = d.plusDays(1)
        return d
    }

    private const val COLUMBUS = "Columbus Day"
    private const val VETERANS = "Veterans Day"

    private fun isWeekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    /** SIFMA full closes: every stock holiday plus Columbus Day and Veterans Day. */
    internal fun bondHolidays(year: Int): Map<LocalDate, String> = buildMap {
        put(observed(year, Month.JANUARY, 1, isNewYears = true), "New Year's Day")
        put(nthWeekday(year, Month.JANUARY, DayOfWeek.MONDAY, 3), "Martin Luther King Jr. Day")
        put(nthWeekday(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3), "Presidents' Day")
        put(goodFriday(year), "Good Friday")
        put(lastWeekday(year, Month.MAY, DayOfWeek.MONDAY), "Memorial Day")
        put(observed(year, Month.JUNE, 19), "Juneteenth")
        put(observed(year, Month.JULY, 4), "Independence Day")
        put(nthWeekday(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labor Day")
        put(nthWeekday(year, Month.OCTOBER, DayOfWeek.MONDAY, 2), COLUMBUS)
        put(observed(year, Month.NOVEMBER, 11), VETERANS)
        put(thanksgiving(year), "Thanksgiving")
        put(observed(year, Month.DECEMBER, 25), "Christmas")
    }

    /** NYSE 1:00 PM ET closes: July 3 (Mon–Thu), the day after Thanksgiving, Christmas Eve (Mon–Thu). */
    internal fun stockEarlyCloses(year: Int): Map<LocalDate, String> = buildMap {
        val jul3 = LocalDate.of(year, Month.JULY, 3)
        if (jul3.dayOfWeek <= DayOfWeek.THURSDAY) put(jul3, "the day before Independence Day")
        put(thanksgiving(year).plusDays(1), "the day after Thanksgiving")
        val dec24 = LocalDate.of(year, Month.DECEMBER, 24)
        if (dec24.dayOfWeek <= DayOfWeek.THURSDAY) put(dec24, "Christmas Eve")
    }

    /**
     * SIFMA 2:00 PM ET closes: the last bond trading day before Good Friday, Memorial Day,
     * Independence Day, Christmas and New Year's Day, plus the day after Thanksgiving.
     */
    internal fun bondEarlyCloses(year: Int): Map<LocalDate, String> = buildMap {
        val closed = bondHolidays(year).keys + bondHolidays(year + 1).keys
        fun before(holiday: LocalDate): LocalDate {
            var d = holiday.minusDays(1)
            while (isWeekend(d) || d in closed) d = d.minusDays(1)
            return d
        }
        put(before(goodFriday(year)), "the day before Good Friday")
        put(before(lastWeekday(year, Month.MAY, DayOfWeek.MONDAY)), "the day before Memorial Day")
        put(before(observed(year, Month.JULY, 4)), "the day before Independence Day")
        put(thanksgiving(year).plusDays(1), "the day after Thanksgiving")
        put(before(observed(year, Month.DECEMBER, 25)), "the day before Christmas")
        // Next year's New Year's Day, unless it falls in this year (Jan 1 on a Saturday is not observed).
        val ny = observed(year + 1, Month.JANUARY, 1, isNewYears = true)
        val nyEve = before(ny)
        if (nyEve.year == year) put(nyEve, "the day before New Year's Day")
    }.filterKeys { it.year == year }

    private fun thanksgiving(year: Int) = nthWeekday(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)

    /** Sat → prior Fri, Sun → next Mon (a Saturday New Year's Day is not moved into the old year). */
    private fun observed(year: Int, month: Month, day: Int, isNewYears: Boolean = false): LocalDate {
        val d = LocalDate.of(year, month, day)
        return when (d.dayOfWeek) {
            DayOfWeek.SATURDAY -> if (isNewYears) d else d.minusDays(1)
            DayOfWeek.SUNDAY -> d.plusDays(1)
            else -> d
        }
    }

    private fun nthWeekday(year: Int, month: Month, dow: DayOfWeek, n: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        val offset = (dow.value - first.dayOfWeek.value + 7) % 7
        return first.plusDays(offset + 7L * (n - 1))
    }

    private fun lastWeekday(year: Int, month: Month, dow: DayOfWeek): LocalDate {
        var d = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1)
        while (d.dayOfWeek != dow) d = d.minusDays(1)
        return d
    }

    /** Good Friday — the stock calendar's own date, so the two calendars cannot disagree on it. */
    private fun goodFriday(year: Int): LocalDate =
        MarketHolidays.holidaysFor(year).first { it.monthValue in 3..4 && it.dayOfWeek == DayOfWeek.FRIDAY }
}
