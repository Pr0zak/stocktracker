package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.PortfolioReviewResponse
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the backend says it cannot weight the book, the app must not print a number anyway.
 *
 * `cash_pct` comes back null when any holding has neither a price nor a cost basis — the denominator
 * is missing a whole position, so no percentage is computable. `Http.json` sets
 * `coerceInputValues = true`, so a non-nullable `Double = 0.0` would turn that null into a confident
 * "0% cash" — the same defect the backend fix exists to prevent, one layer up.
 */
class PortfolioWeightsTest {

    @Test
    fun `a null cash percentage stays null instead of becoming zero`() {
        val json = """
            {"review":{"health":"ok"},
             "portfolio":{"total_value":2000.0,"cash_pct":null,
               "positions":[{"symbol":"AAPL","weight_pct":null,"value":1000.0}],
               "unpriced":[{"symbol":"VXUS","shares":10.0,"value_at_cost":null}],
               "unvalued":["VXUS"],"weights_approximate":true}}
        """.trimIndent()
        val r = Http.json.decodeFromString<PortfolioReviewResponse>(json)
        assertNull("a null cash_pct rendered as 0% cash", r.portfolio.cashPct)
        assertNull(r.portfolio.positions.first().weightPct)
        assertEquals(listOf("VXUS"), r.portfolio.unvalued)
        assertTrue(r.portfolio.weightsApproximate)
    }

    @Test
    fun `a fully priced book still reports its percentages`() {
        val json = """
            {"review":{"health":"ok"},
             "portfolio":{"total_value":2000.0,"cash_pct":50.0,
               "positions":[{"symbol":"AAPL","weight_pct":50.0,"value":1000.0}]}}
        """.trimIndent()
        val r = Http.json.decodeFromString<PortfolioReviewResponse>(json)
        assertEquals(50.0, r.portfolio.cashPct!!, 0.001)
        assertTrue(r.portfolio.unvalued.isEmpty())
        assertTrue(!r.portfolio.weightsApproximate)
    }

    @Test
    fun `a mixed-currency total is flagged so the dialogs can caveat it`() {
        // No FX rate is applied server-side, so a GBP holding enters the USD total at face value.
        val json = """
            {"review":{"health":"ok"},
             "portfolio":{"total_value":2000.0,"cash_pct":0.0,
               "positions":[{"symbol":"AAPL","weight_pct":50.0,"value":1000.0}],
               "mixed_currencies":["GBP"]}}
        """.trimIndent()
        val r = Http.json.decodeFromString<PortfolioReviewResponse>(json)
        assertEquals(listOf("GBP"), r.portfolio.mixedCurrencies)
    }

    @Test
    fun `a single-currency book carries no currency caveat`() {
        val json = """{"review":{"health":"ok"},"portfolio":{"total_value":1.0,"positions":[]}}"""
        assertTrue(Http.json.decodeFromString<PortfolioReviewResponse>(json).mixedCurrenciesEmpty())
    }

    private fun com.stocktracker.app.data.remote.PortfolioReviewResponse.mixedCurrenciesEmpty() =
        portfolio.mixedCurrencies.isEmpty()
}
