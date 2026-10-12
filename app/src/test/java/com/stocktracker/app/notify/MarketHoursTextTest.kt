package com.stocktracker.app.notify

import com.stocktracker.app.util.MarketCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MarketHoursTextTest {

    private val central = ZoneId.of("America/Chicago")
    private fun day(y: Int, m: Int, d: Int) = MarketCalendar.day(LocalDate.of(y, m, d))!!

    @Test fun `columbus day reads as bond market closed, stocks normal, banks shut`() {
        val (title, body) = MarketHoursText.today(day(2026, 10, 12), central)
        assertEquals("Bond market closed today — Columbus Day", title)
        assertEquals(
            "Stocks trade normal hours. Treasury and bond prices won't update. " +
                "Banks are closed, so transfers settle on the next business day.",
            body,
        )
    }

    @Test fun `thanksgiving names the early-close reopen in the phone's zone`() {
        val (title, body) = MarketHoursText.today(day(2026, 11, 26), central)
        assertEquals("Stock market closed today — Thanksgiving", title)
        assertTrue(body, body.endsWith("Stocks reopen Friday, closing early at 12:00 PM."))
    }

    @Test fun `early close shows local time`() {
        val (title, body) = MarketHoursText.today(day(2026, 12, 24), central)
        assertEquals("Stocks close early today at 12:00 PM — Christmas Eve", title)
        assertEquals("The bond market closes at 1:00 PM.", body)
    }

    @Test fun `friday heads-up for a monday closure names the day`() {
        // MLK Day 2027 = Monday Jan 18.
        val (title, body) = MarketHoursText.ahead(day(2027, 1, 18), LocalDate.of(2027, 1, 15), central)
        assertEquals("Monday: stock market closed — Martin Luther King Jr. Day", title)
        assertEquals("Stocks reopen Tuesday.", body)
    }

    @Test fun `eve of an early close says tomorrow`() {
        val (title, _) = MarketHoursText.ahead(day(2026, 11, 27), LocalDate.of(2026, 11, 26), central)
        assertEquals("Tomorrow: stocks close early at 12:00 PM — the day after Thanksgiving", title)
    }
}
