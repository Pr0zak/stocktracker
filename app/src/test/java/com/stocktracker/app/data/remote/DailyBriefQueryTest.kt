package com.stocktracker.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MONEY-7: [dailyBriefQuery] builds the `/daily_brief` query string, including the new `holdings`
 * param — what the user actually OWNS, sent as bare uppercased symbols so the brief can speak to the
 * book without being handed shares, cost basis, lot dates, or the taxable-account flag.
 */
class DailyBriefQueryTest {

    @Test fun `no deep and no holdings produces an empty query string`() {
        assertEquals("", dailyBriefQuery(deep = false, holdings = emptyList()))
    }

    @Test fun `deep alone`() {
        assertEquals("?deep=true", dailyBriefQuery(deep = true, holdings = emptyList()))
    }

    @Test fun `holdings alone are comma-joined and uppercased`() {
        assertEquals(
            "?holdings=AAPL,MSFT",
            dailyBriefQuery(deep = false, holdings = listOf("aapl", "MSFT")),
        )
    }

    @Test fun `deep and holdings together`() {
        assertEquals(
            "?deep=true&holdings=AAPL,BTC-USD",
            dailyBriefQuery(deep = true, holdings = listOf("AAPL", "BTC-USD")),
        )
    }

    @Test fun `a single holding still gets the holdings param`() {
        assertEquals("?holdings=NVDA", dailyBriefQuery(deep = false, holdings = listOf("nvda")))
    }
}
