package com.stocktracker.app.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId

class SignalScanNotifierTest {

    private fun at(iso: String): Long = ZonedDateTime.of(
        java.time.LocalDateTime.parse(iso), ZoneId.of("America/Chicago"),
    ).toEpochSecond()

    @Test fun `a Friday morning scan alerts`() =
        assertTrue(SignalScanNotifier.madeOnTradingDay(at("2026-10-23T06:30:00")))

    @Test fun `Saturday and Sunday scans do not`() {
        assertFalse(SignalScanNotifier.madeOnTradingDay(at("2026-10-24T06:30:00")))
        assertFalse(SignalScanNotifier.madeOnTradingDay(at("2026-10-25T06:30:00")))
    }

    @Test fun `a market holiday scan does not`() =
        assertFalse(SignalScanNotifier.madeOnTradingDay(at("2026-11-26T06:30:00"))) // Thanksgiving

    @Test fun `the day is read in New York time, not the phone's`() {
        // 23:30 Friday in Chicago is 00:30 Saturday in New York: no session that day.
        assertFalse(SignalScanNotifier.madeOnTradingDay(at("2026-10-23T23:30:00")))
    }
}
