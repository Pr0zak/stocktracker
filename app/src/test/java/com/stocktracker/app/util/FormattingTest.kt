package com.stocktracker.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test fun `price with thousands and cents`() {
        assertEquals("$1,234.50", Formatting.price(1234.5))
    }

    @Test fun `hide zero cents drops trailing decimals on whole dollars`() {
        assertEquals("$1,000", Formatting.price(1000.0, hideZeroCents = true))
        assertEquals("$1,000.00", Formatting.price(1000.0, hideZeroCents = false))
    }

    @Test fun `sub-dollar prices keep four decimals`() {
        assertEquals("$0.1234", Formatting.price(0.1234))
    }

    @Test fun `percent has explicit sign`() {
        assertEquals("+1.20%", Formatting.percent(1.2))
        assertEquals("-3.00%", Formatting.percent(-3.0))
    }

    @Test fun `change line combines arrow value and percent`() {
        assertEquals("▲ +2.71 (+1.20%)", Formatting.changeLine(2.71, 1.2, up = true))
    }

    @Test fun `shares drop trailing zeros`() {
        assertEquals("10", Formatting.shares(10.0))
        assertEquals("2.5", Formatting.shares(2.5))
    }

    @Test fun `compact volume uses K M B T suffixes`() {
        assertEquals("34.55M", Formatting.compact(34_554_391.0))
        assertEquals("29.75B", Formatting.compact(29_747_648_290.0))
        assertEquals("1.20K", Formatting.compact(1200.0))
        assertEquals("950", Formatting.compact(950.0))
    }
}
