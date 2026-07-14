package com.stocktracker.app.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/**
 * US equity market (NYSE/Nasdaq) full-day holiday closures, computed algorithmically so they don't
 * go stale. Early-close half-days are not modeled.
 */
object MarketHolidays {

    fun isMarketHoliday(date: LocalDate): Boolean = holidaysFor(date.year).contains(date)

    fun holidaysFor(year: Int): Set<LocalDate> = buildSet {
        add(observedFixed(year, Month.JANUARY, 1, isNewYears = true)) // New Year's Day
        add(nthWeekday(year, Month.JANUARY, DayOfWeek.MONDAY, 3))     // MLK Jr. Day
        add(nthWeekday(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3))    // Washington's Birthday
        add(goodFriday(year))                                         // Good Friday
        add(lastWeekday(year, Month.MAY, DayOfWeek.MONDAY))           // Memorial Day
        add(observedFixed(year, Month.JUNE, 19))                      // Juneteenth
        add(observedFixed(year, Month.JULY, 4))                       // Independence Day
        add(nthWeekday(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1))   // Labor Day
        add(nthWeekday(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4))  // Thanksgiving
        add(observedFixed(year, Month.DECEMBER, 25))                  // Christmas Day
    }

    /** Fixed-date holiday with NYSE observation: Sat→prior Fri, Sun→next Mon (New Year's not shifted to Fri). */
    private fun observedFixed(year: Int, month: Month, day: Int, isNewYears: Boolean = false): LocalDate {
        val d = LocalDate.of(year, month, day)
        return when (d.dayOfWeek) {
            DayOfWeek.SATURDAY -> if (isNewYears) d else d.minusDays(1)
            DayOfWeek.SUNDAY -> d.plusDays(1)
            else -> d
        }
    }

    private fun nthWeekday(year: Int, month: Month, dow: DayOfWeek, n: Int): LocalDate {
        var d = LocalDate.of(year, month, 1)
        var count = 0
        while (true) {
            if (d.dayOfWeek == dow) {
                count++
                if (count == n) return d
            }
            d = d.plusDays(1)
        }
    }

    private fun lastWeekday(year: Int, month: Month, dow: DayOfWeek): LocalDate {
        var d = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1) // last day of month
        while (d.dayOfWeek != dow) d = d.minusDays(1)
        return d
    }

    /** Good Friday = 2 days before Easter Sunday (Anonymous Gregorian Computus). */
    private fun goodFriday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day).minusDays(2)
    }
}
