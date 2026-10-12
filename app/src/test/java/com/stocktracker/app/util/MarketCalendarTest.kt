package com.stocktracker.app.util

import com.stocktracker.app.util.MarketCalendar.Hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MarketCalendarTest {

    private fun day(y: Int, m: Int, d: Int) = MarketCalendar.day(LocalDate.of(y, m, d))!!

    @Test fun `columbus day 2026 - stocks open, bonds and banks closed`() {
        val d = day(2026, 10, 12)
        assertEquals(Hours.OPEN, d.stocks)
        assertEquals(Hours.CLOSED, d.bonds)
        assertTrue(d.banksClosed)
        assertEquals("Columbus Day", d.reason)
    }

    @Test fun `veterans day 2026 is a wednesday - bonds and banks closed`() {
        val d = day(2026, 11, 11)
        assertEquals(Hours.OPEN, d.stocks)
        assertEquals(Hours.CLOSED, d.bonds)
        assertTrue(d.banksClosed)
    }

    @Test fun `veterans day on a saturday - bonds observe friday, banks do not`() {
        // Nov 11 2028 is a Saturday.
        val d = day(2028, 11, 10)
        assertEquals(Hours.CLOSED, d.bonds)
        assertFalse(d.banksClosed)
    }

    @Test fun `thanksgiving week 2026`() {
        assertEquals(Hours.CLOSED, day(2026, 11, 26).stocks)
        assertEquals(Hours.CLOSED, day(2026, 11, 26).bonds)
        assertFalse(day(2026, 11, 26).banksClosed)
        val fri = day(2026, 11, 27)
        assertEquals(Hours.EARLY_CLOSE, fri.stocks)
        assertEquals(Hours.EARLY_CLOSE, fri.bonds)
    }

    @Test fun `independence day 2026 on saturday - closed friday, bonds early thursday, stocks normal thursday`() {
        assertEquals(Hours.CLOSED, day(2026, 7, 3).stocks)
        val thu = day(2026, 7, 2)
        assertEquals(Hours.OPEN, thu.stocks)
        assertEquals(Hours.EARLY_CLOSE, thu.bonds)
    }

    @Test fun `independence day 2025 on friday - both close early thursday`() {
        val thu = day(2025, 7, 3)
        assertEquals(Hours.EARLY_CLOSE, thu.stocks)
        assertEquals(Hours.EARLY_CLOSE, thu.bonds)
    }

    @Test fun `christmas eve 2026 thursday - both close early`() {
        val d = day(2026, 12, 24)
        assertEquals(Hours.EARLY_CLOSE, d.stocks)
        assertEquals(Hours.EARLY_CLOSE, d.bonds)
    }

    @Test fun `christmas 2027 on saturday - friday closed, bonds early thursday`() {
        assertEquals(Hours.CLOSED, day(2027, 12, 24).stocks)
        assertEquals(Hours.EARLY_CLOSE, day(2027, 12, 23).bonds)
        assertEquals(Hours.OPEN, day(2027, 12, 23).stocks)
    }

    @Test fun `new years eve - bonds early, stocks normal`() {
        val d = day(2026, 12, 31)
        assertEquals(Hours.OPEN, d.stocks)
        assertEquals(Hours.EARLY_CLOSE, d.bonds)
    }

    @Test fun `new years 2028 on saturday - friday dec 31 2027 is bonds early, not closed`() {
        val d = day(2027, 12, 31)
        assertEquals(Hours.OPEN, d.stocks)
        assertEquals(Hours.EARLY_CLOSE, d.bonds)
    }

    @Test fun `thursday before good friday 2026 - bonds early`() {
        assertEquals(Hours.EARLY_CLOSE, day(2026, 4, 2).bonds)
        assertEquals(Hours.CLOSED, day(2026, 4, 3).bonds)
    }

    @Test fun `friday before memorial day - bonds early`() {
        assertEquals(Hours.EARLY_CLOSE, day(2026, 5, 22).bonds)
    }

    @Test fun `ordinary day and weekend`() {
        assertFalse(day(2026, 10, 13).unusual)
        assertNull(MarketCalendar.day(LocalDate.of(2026, 10, 10)))
    }

    @Test fun `every stock holiday is a bond holiday`() {
        for (y in 2024..2035) {
            assertTrue(MarketCalendar.bondHolidays(y).keys.containsAll(MarketHolidays.holidaysFor(y)))
        }
    }

    @Test fun `early closes never land on a closure or a weekend`() {
        for (y in 2024..2035) {
            val closed = MarketCalendar.bondHolidays(y).keys
            for (d in MarketCalendar.bondEarlyCloses(y).keys + MarketCalendar.stockEarlyCloses(y).keys) {
                assertFalse("$d", d in closed)
                assertFalse("$d", d.dayOfWeek.value >= 6)
            }
        }
    }
}
