package com.stocktracker.app.ui.detail

import com.stocktracker.app.data.remote.FundCost
import com.stocktracker.app.data.remote.FundCostsResponse
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FC-1 — a fund's fee said in dollars, and the comparison line under it. */
class FundCostTextTest {

    private fun etf(sym: String, pct: Double?, fidelity: Boolean = false) =
        FundCost(symbol = sym, kind = "etf", expenseRatioPct = pct, fidelity = fidelity)

    private fun mf(sym: String, pct: Double?, fidelityOnly: Boolean = false) =
        FundCost(symbol = sym, kind = "mutual_fund", expenseRatioPct = pct, fidelity = true, fidelityOnly = fidelityOnly)

    // The S&P 500 group as the server returned it on 2026-09-26, cheapest first.
    private val sp500 = listOf(
        mf("FNILX", 0.0, fidelityOnly = true), mf("FXAIX", 0.015),
        etf("SPYM", 0.02), etf("IVV", 0.03), etf("VOO", 0.03), etf("SPY", 0.0945),
    )

    @Test fun `fees are dollars per ten thousand, whole when whole`() {
        assertEquals("$9.45", FundCostText.perTenK(0.0945))
        assertEquals("$3", FundCostText.perTenK(0.03))
        assertEquals("$1.50", FundCostText.perTenK(0.015))
        assertEquals("$8.40", FundCostText.perTenK(0.084))
        assertEquals("$150", FundCostText.perTenK(1.5))
        assertEquals("$0", FundCostText.perTenK(0.0))
        assertEquals("$9.45 a year per \$10,000", FundCostText.headline(0.0945))
    }

    @Test fun `an unknown fee is said as unknown, never as free`() {
        assertEquals("Yearly fee unknown", FundCostText.headline(null))
        assertEquals("No yearly fee", FundCostText.headline(0.0))
        assertEquals("—", FundCostText.rowFee(etf("X", null)))
        assertNull(FundCostText.compareLine(etf("X", null), sp500))
    }

    @Test fun `a holding's yearly cost, and dust is not rounded to zero`() {
        assertEquals("Your \$12,400: about \$11.72 a year", FundCostText.holdingLine(12_400.0, 0.0945))
        assertEquals("under 1¢", FundCostText.yearlyOn(5.0, 0.03))
        assertEquals("Your \$500: no yearly fee", FundCostText.holdingLine(500.0, 0.0))
    }

    @Test fun `the percentage keeps its real precision`() {
        assertEquals("0.0945%", FundCostText.percent(0.0945))
        assertEquals("0.03%", FundCostText.percent(0.03))
        assertEquals("0.015%", FundCostText.percent(0.015))
        assertEquals("1.50%", FundCostText.percent(1.5))
    }

    @Test fun `SPY is told the cheapest ETF and the cheapest Fidelity fund`() {
        val spy = sp500.last()
        assertEquals("Cheaper look-alikes: SPYM \$2 · Fidelity's FNILX \$0", FundCostText.compareLine(spy, sp500))
        assertFalse(FundCostText.isLowest(spy, sp500))
        assertEquals("FNILX", FundCostText.cheaper(spy, sp500).first().symbol)
        assertEquals("Held in FNILX instead: about \$11.72 less a year",
            FundCostText.savingLine(12_400.0, 0.0945, sp500.first()))
    }

    @Test fun `a tie is not cheaper, and the cheapest says so`() {
        val sectors = listOf(etf("FTEC", 0.084, fidelity = true), etf("VGT", 0.09))
        assertEquals("Lowest fee of the 2 look-alike funds", FundCostText.compareLine(sectors[0], sectors))
        assertTrue(FundCostText.isLowest(sectors[0], sectors))
        // VGT's cheaper look-alike is itself a Fidelity ETF: named once, as Fidelity's.
        assertEquals("Cheaper look-alikes: Fidelity's FTEC \$8.40", FundCostText.compareLine(sectors[1], sectors))

        val bitcoin = listOf(etf("BTC", 0.15), etf("FBTC", 0.25, fidelity = true), etf("IBIT", 0.25))
        assertEquals("Cheaper look-alikes: BTC \$15", FundCostText.compareLine(bitcoin[2], bitcoin))
        val dev = listOf(etf("VEA", 0.03), etf("SCHF", 0.03), etf("SPDW", 0.03))
        assertEquals("Tied for the lowest fee of the 3 look-alike funds", FundCostText.compareLine(dev[0], dev))
    }

    @Test fun `an unknown fee in the group is never counted as cheaper`() {
        val g = listOf(etf("A", null), etf("B", 0.05))
        assertTrue(FundCostText.cheaper(g[1], g).isEmpty())
        // B is not "the lowest of 2" when the other fee is unknown — there is nothing to compare.
        assertNull(FundCostText.compareLine(g[1], g))
        assertFalse(FundCostText.isLowest(g[1], g))
    }

    @Test fun `row tags say what changes a decision`() {
        assertEquals("this fund", FundCostText.tags(etf("SPY", 0.0945), isSelf = true))
        assertEquals("Fidelity mutual fund · priced once a day · Fidelity-only",
            FundCostText.tags(sp500.first(), isSelf = false))
        assertEquals("Fidelity ETF", FundCostText.tags(etf("FBTC", 0.25, fidelity = true), isSelf = false))
        val waived = FundCost(symbol = "ZZZ", kind = "etf", listedZero = true)
        assertEquals("Yahoo's 0% is out of date", FundCostText.tags(waived, isSelf = false))
        assertEquals("$20*", FundCostText.rowFee(FundCost(symbol = "HODL", kind = "etf", expenseRatioPct = 0.2, feeSource = "issuer")))
    }

    @Test fun `long Yahoo names lose the words every fund shares`() {
        assertEquals("Vanguard FTSE Developed Markets",
            FundCostText.shortName("Vanguard FTSE Developed Markets Index Fund ETF Shares"))
        assertEquals("iShares Core S&P 500", FundCostText.shortName("iShares Core S&P 500 ETF"))
        assertEquals("State Street SPDR S&P 500", FundCostText.shortName("State Street SPDR S&P 500 ETF Trust"))
        assertEquals("SPDR Gold Shares", FundCostText.shortName("SPDR Gold Shares"))
        assertEquals("", FundCostText.shortName(null))
    }

    @Test fun `the source line quotes the oldest live read and dates the rest`() {
        val now = 1_790_000_000_000L
        val rows = listOf(
            FundCost(symbol = "A", expenseRatioPct = 0.03, feeSource = "yahoo", feeCheckedAt = (now - 3 * 3_600_000) / 1000.0),
            FundCost(symbol = "B", expenseRatioPct = 0.03, feeSource = "yahoo", feeCheckedAt = (now - 60_000) / 1000.0),
            FundCost(symbol = "C", expenseRatioPct = 0.2, feeSource = "issuer", feeDated = "2026-09-26"),
        )
        assertEquals("Fees from Yahoo Finance, checked 3h ago · * from the fund company, Sep 26",
            FundCostText.sourceLine(rows, live = true, now = now))
        assertTrue(FundCostText.sourceLine(rows, live = false, now = now).startsWith("Yahoo didn't answer"))
    }

    @Test fun `the server payload decodes, nulls staying null`() {
        val body = """{"funds":{"SPY":{"symbol":"SPY","name":"State Street SPDR S&P 500 ETF Trust","kind":"etf",
            "expense_ratio_pct":0.0945,"fee_source":"yahoo","fee_checked_at":1790000000.0,"fee_dated":null,
            "listed_zero":false,"fidelity":false,"fidelity_only":false,"staking":false,
            "group":{"id":"sp500","label":"the S&P 500","note":null,"funds":[
              {"symbol":"SPYM","name":null,"kind":"etf","expense_ratio_pct":0.02,"fee_source":"saved",
               "fee_checked_at":null,"fee_dated":"2026-08-27","listed_zero":false,"fidelity":false,
               "fidelity_only":false,"staking":false},
              {"symbol":"X","kind":"etf","expense_ratio_pct":null,"fee_source":null}]}}},
            "live":true,"as_of":1790000000.0}"""
        val r = Http.json.decodeFromString<FundCostsResponse>(body)
        val spy = r.funds.getValue("SPY")
        assertEquals(0.0945, spy.expenseRatioPct!!, 1e-12)
        assertEquals("sp500", spy.group!!.id)
        assertEquals("$2†", FundCostText.rowFee(spy.group!!.funds[0]))
        assertNull(spy.group!!.funds[1].expenseRatioPct)       // null stays unknown, not 0.0
        assertTrue(r.live)
    }
}
