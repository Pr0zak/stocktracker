package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.PortfolioReviewResponse
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A holding the backend could not price must reach the UI.
 *
 * The backend used to DROP unpriceable holdings, which inflated every surviving weight and the cash
 * percentage with no signal anywhere. It now carries them at cost and reports them in `unpriced`; if
 * the app silently ignores that field, the dialog still presents a partial book as the user's real
 * allocation — the same defect one layer up.
 */
class UnpricedHoldingTest {

    @Test
    fun `unpriced holdings are parsed rather than ignored`() {
        val json = """
            {"review":{"health":"ok"},
             "portfolio":{"total_value":2800.0,"cash_pct":35.7,
               "positions":[{"symbol":"AAPL","weight_pct":35.7,"value":1000.0}],
               "unpriced":[{"symbol":"VXUS","shares":10.0,"value_at_cost":800.0}]}}
        """.trimIndent()
        val r = Http.json.decodeFromString<PortfolioReviewResponse>(json)
        assertEquals(1, r.portfolio.unpriced.size)
        assertEquals("VXUS", r.portfolio.unpriced.first().symbol)
        assertEquals(800.0, r.portfolio.unpriced.first().valueAtCost, 0.001)
    }

    @Test
    fun `a fully priced book reports nothing and stays quiet`() {
        val json = """
            {"review":{"health":"ok"},
             "portfolio":{"total_value":2000.0,"cash_pct":50.0,
               "positions":[{"symbol":"AAPL","weight_pct":50.0,"value":1000.0}]}}
        """.trimIndent()
        val r = Http.json.decodeFromString<PortfolioReviewResponse>(json)
        assertTrue("a clean book must not raise a warning", r.portfolio.unpriced.isEmpty())
    }
}
