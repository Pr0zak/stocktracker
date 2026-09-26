package com.stocktracker.app.notify

import com.stocktracker.app.data.remote.FundCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FUND-6 — which fee moves are real changes worth an alert. */
class FundFeeNotifierTest {

    private fun row(sym: String, fee: Double?, source: String? = "yahoo", kind: String = "etf") =
        FundCost(symbol = sym, kind = kind, expenseRatioPct = fee, feeSource = source)

    @Test fun `the first look records without alerting`() {
        val (changes, next) = FundFeeNotifier.diff(emptyMap(), listOf(row("VOO", 0.03)))
        assertTrue(changes.isEmpty())
        assertEquals(mapOf("VOO" to 0.03), next)
    }

    @Test fun `a waiver ending is a change`() {
        val (changes, next) = FundFeeNotifier.diff(mapOf("HODL" to 0.0), listOf(row("HODL", 0.2, source = "issuer")))
        val c = changes.single()
        assertEquals("HODL's yearly fee went up", FundFeeNotifier.title(c))
        assertEquals("Now \$20 a year per \$10,000, was \$0.", FundFeeNotifier.body(c))
        assertEquals(0.2, next.getValue("HODL"), 1e-12)
    }

    @Test fun `rounding noise, unknowns and fallback figures are not changes`() {
        val seen = mapOf("SPY" to 0.095, "VTI" to 0.03, "IVV" to 0.03)
        val (changes, next) = FundFeeNotifier.diff(seen, listOf(
            row("SPY", 0.0945),                       // Yahoo rounding, not a fee change
            row("VTI", null, source = null),          // unknown today
            row("IVV", 0.05, source = "saved"),       // Yahoo was down: a saved figure is not news
            row("AAPL", null, kind = "other"),
        ))
        assertTrue(changes.isEmpty())
        assertEquals(0.03, next.getValue("VTI"), 1e-12)   // unknown keeps the last known figure
        assertEquals(0.03, next.getValue("IVV"), 1e-12)
    }

    @Test fun `the seen map survives a round trip through storage`() {
        val m = mapOf("VOO" to 0.03, "SPY" to 0.0945)
        assertEquals(m, FundFeeNotifier.decode(FundFeeNotifier.encode(m)))
        assertEquals(emptyMap<String, Double>(), FundFeeNotifier.decode(setOf("garbage")))
    }
}
