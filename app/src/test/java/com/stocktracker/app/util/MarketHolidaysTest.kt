package com.stocktracker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MarketHolidaysTest {

    @Test fun `new years day 2026`() {
        // Jan 1 2026 is a Thursday — observed on the day.
        assertTrue(MarketHolidays.isMarketHoliday(LocalDate.of(2026, 1, 1)))
    }

    @Test fun `independence day 2026 observed on friday`() {
        // Jul 4 2026 is a Saturday -> NYSE observes it the prior Friday, Jul 3.
        assertTrue(MarketHolidays.isMarketHoliday(LocalDate.of(2026, 7, 3)))
        assertFalse(MarketHolidays.isMarketHoliday(LocalDate.of(2026, 7, 4)))
    }

    @Test fun `good friday 2026`() {
        // Easter 2026 = Apr 5, so Good Friday = Apr 3.
        assertTrue(MarketHolidays.isMarketHoliday(LocalDate.of(2026, 4, 3)))
    }

    @Test fun `thanksgiving and christmas 2026`() {
        assertTrue(MarketHolidays.isMarketHoliday(LocalDate.of(2026, 11, 26))) // 4th Thursday
        assertTrue(MarketHolidays.isMarketHoliday(LocalDate.of(2026, 12, 25))) // Friday
    }

    @Test fun `regular trading day is not a holiday`() {
        assertFalse(MarketHolidays.isMarketHoliday(LocalDate.of(2026, 1, 2)))
        assertFalse(MarketHolidays.isMarketHoliday(LocalDate.of(2026, 3, 17)))
    }

    @Test fun `ten holidays per year`() {
        assertEquals(10, MarketHolidays.holidaysFor(2026).size)
    }
}
