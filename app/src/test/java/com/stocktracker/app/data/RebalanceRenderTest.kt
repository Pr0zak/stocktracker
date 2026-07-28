package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.RebalanceResponse
import com.stocktracker.app.util.Formatting
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rebalance plan is copied onto a broker ticket, so nothing on it may render as something else. */
class RebalanceRenderTest {

    @Test
    fun `a fractional crypto share count is not rendered as zero`() {
        // The dialog formatted shares with "%.2f", so a real 0.004 BTC move displayed as "0.00 sh".
        assertEquals("0.004", Formatting.shares(0.004))
        assertEquals("0.0125", Formatting.shares(0.0125))
        assertTrue("a real move must never render as nothing", Formatting.shares(0.004) != "0.00")
    }

    @Test
    fun `whole share counts stay clean`() {
        assertEquals("12", Formatting.shares(12.0))
        assertEquals("1,250", Formatting.shares(1250.0))
    }

    @Test
    fun `server corrections to the plan reach the app`() {
        val json = """
            {"plan":{"summary":"s","moves":[],"resulting_top_weight_pct":52.4,"cash_after":999.23},
             "plan_warnings":["AAPL: plan sold 40 shares but only 10 are held — capped",
                              "this plan still leaves the largest position at 52.4%, above the 25% target"],
             "max_position_pct":25.0}
        """.trimIndent()
        val r = Http.json.decodeFromString<RebalanceResponse>(json)
        assertEquals(2, r.planWarnings.size)
        assertTrue(r.planWarnings.any { it.contains("only 10 are held") })
    }

    @Test
    fun `a clean plan carries no warnings`() {
        val json = """{"plan":{"summary":"s","moves":[]},"max_position_pct":25.0}"""
        assertTrue(Http.json.decodeFromString<RebalanceResponse>(json).planWarnings.isEmpty())
    }
}
