package com.stocktracker.app.ui.funds

import com.stocktracker.app.data.remote.FundCost
import com.stocktracker.app.data.remote.FundGroup
import com.stocktracker.app.data.remote.FundHolding
import com.stocktracker.app.data.remote.FundOverlapResponse
import com.stocktracker.app.data.remote.FundPair
import com.stocktracker.app.data.remote.FundPerformanceResponse
import com.stocktracker.app.data.remote.FundProfile
import com.stocktracker.app.data.remote.FundSector
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FUND-1..6 — overlap verdicts, the before-you-buy read, fees you pay, and the plain wording. */
class FundsLogicTest {

    private fun fund(sym: String, fee: Double?, group: String? = null, tops: List<Pair<String, Double>> = emptyList(),
                     kind: String = "etf") = FundProfile(
        symbol = sym, kind = kind, expenseRatioPct = fee, groupId = group, profileOk = true,
        regionLabel = "US stocks", sectors = listOf(FundSector("technology", "Tech", 38.7), FundSector("financial_services", "Finance", 12.0)),
        topHoldings = tops.map { FundHolding(it.first, null, it.second) },
    )

    // Measured on 2026-09-26: SPY/VOO 1.000, VOO/QQQM 0.950, VOO/SPMO 0.871, VOO/SCHD 0.520.
    private val resp = FundOverlapResponse(
        funds = mapOf(
            "VOO" to fund("VOO", 0.03, "sp500", listOf("NVDA" to 8.08, "AAPL" to 7.03, "MSFT" to 5.7)),
            "SPY" to fund("SPY", 0.0945, "sp500", listOf("NVDA" to 8.1, "AAPL" to 7.0, "MSFT" to 5.7)),
            "QQQM" to fund("QQQM", 0.15, "nasdaq100", listOf("NVDA" to 8.5, "AAPL" to 7.41, "MSFT" to 6.0, "AMD" to 3.37)),
            "SCHD" to fund("SCHD", 0.06, null, listOf("MRK" to 4.78, "KO" to 4.2)),
        ),
        pairs = listOf(
            FundPair("VOO", "SPY", corr = 1.0, sharedTopCount = 10),
            FundPair("VOO", "QQQM", corr = 0.95, sharedTopCount = 3),
            FundPair("SPY", "QQQM", corr = 0.95, sharedTopCount = 3),
            FundPair("VOO", "SCHD", corr = 0.52), FundPair("SPY", "SCHD", corr = 0.52), FundPair("QQQM", "SCHD", corr = 0.33),
        ),
        sameBets = listOf(listOf("VOO", "SPY", "QQQM"), listOf("SCHD")),
    )

    @Test fun `verdict thresholds say what the correlation means`() {
        assertEquals(PairVerdict.SAME_FUND, PairVerdict.of(1.0))
        assertEquals(PairVerdict.SAME_FUND, PairVerdict.of(0.998))
        assertEquals(PairVerdict.MOVE_TOGETHER, PairVerdict.of(0.95))
        assertEquals(PairVerdict.OVERLAP_A_LOT, PairVerdict.of(0.871))
        assertEquals(PairVerdict.DIFFERENT, PairVerdict.of(0.52))
        assertEquals(PairVerdict.UNKNOWN, PairVerdict.of(null))
        assertEquals(PairVerdict.UNKNOWN, PairVerdict.of(Double.NaN))
    }

    @Test fun `the headline counts funds and bets`() {
        assertEquals("4 funds, 2 different bets", FundsLogic.betsHeadline(4, 2))
        assertEquals("1 fund, 1 different bet", FundsLogic.betsHeadline(1, 1))
    }

    @Test fun `before you buy leads with the closest fund you own`() {
        val lines = FundsLogic.beforeYouBuy("QQQM", resp, ownedFunds = listOf("VOO", "SCHD"), ownedStocks = listOf("AAPL", "TSLA"))
        assertEquals("Moves with your VOO (0.95): mostly the same bet.", lines[0])
        assertEquals("3 of its 4 biggest holdings are also among VOO's biggest.", lines[1])
        assertEquals("You already own AAPL directly: 7% of this fund.", lines[2])
    }

    @Test fun `the same fund twice is called that`() {
        val lines = FundsLogic.beforeYouBuy("SPY", resp, ownedFunds = listOf("VOO"), ownedStocks = emptyList())
        assertEquals("Same thing as your VOO (1.00): owning both is one bet.", lines[0])
    }

    @Test fun `a different fund says how close its nearest match is`() {
        val lines = FundsLogic.beforeYouBuy("SCHD", resp, ownedFunds = listOf("VOO", "QQQM"), ownedStocks = emptyList())
        assertEquals("A different bet: its closest match among your funds is VOO, at 0.52.", lines[0])
        assertEquals(1, lines.size)                            // no shared top holdings to report
    }

    @Test fun `owning nothing gives nothing to say, and the caller says so`() {
        assertTrue(FundsLogic.beforeYouBuy("VOO", resp, emptyList(), emptyList()).isEmpty())
    }

    @Test fun `a fund you already hold says so first`() {
        val lines = FundsLogic.beforeYouBuy("VOO", resp, listOf("SPY"), emptyList(), alreadyOwned = true)
        assertEquals("You already own VOO.", lines[0])
        assertEquals("Same thing as your SPY (1.00): owning both is one bet.", lines[1])
    }

    @Test fun `fees you pay, and the cheapest look-alike for each fund`() {
        val groups = mapOf(
            "sp500" to FundGroup("sp500", "the S&P 500", null, listOf(
                FundCost("FNILX", kind = "mutual_fund", expenseRatioPct = 0.0, fidelity = true, fidelityOnly = true),
                FundCost("SPYM", kind = "etf", expenseRatioPct = 0.02),
                FundCost("VOO", kind = "etf", expenseRatioPct = 0.03),
                FundCost("SPY", kind = "etf", expenseRatioPct = 0.0945),
            )),
        )
        val f = FundsLogic.feesYouPay(mapOf("SPY" to 12_400.0, "SCHD" to 5_000.0), resp.funds, groups)
        assertEquals(12_400 * 0.000945 + 5_000 * 0.0006, f.perYear, 1e-9)
        assertEquals(5_000 * 0.0006, f.cheapestPerYear!!, 1e-9) // SPY -> FNILX at 0%, SCHD has no look-alike
        assertEquals("FNILX", f.switches.single().to)
        assertEquals("Fidelity-only mutual fund", f.switches.single().toNote)
        assertEquals(11.718, f.switches.single().savesPerYear, 1e-9)
    }

    @Test fun `an unknown fee is left out and named, never counted as free`() {
        val funds = resp.funds + ("XYZ" to fund("XYZ", null))
        val f = FundsLogic.feesYouPay(mapOf("XYZ" to 1_000.0, "SCHD" to 1_000.0), funds, emptyMap())
        assertEquals(listOf("XYZ"), f.unknownFee)
        assertEquals(0.6, f.perYear, 1e-9)
        assertEquals(1_000.0, f.countedValue, 1e-9)            // the total says what it covers
        assertEquals(1, f.knownCount)
        val none = FundsLogic.feesYouPay(mapOf("XYZ" to 5_000.0), funds, emptyMap())
        assertEquals(0, none.knownCount)                       // nothing counted: not "$0 a year"
        assertEquals(0.0, none.countedValue, 1e-9)
    }

    @Test fun `a fund with no measured look-alike is never called the cheapest`() {
        val f = FundsLogic.feesYouPay(mapOf("SCHD" to 3_000.0), resp.funds, emptyMap())
        assertTrue(f.compared.isEmpty())
        assertEquals(listOf("SCHD"), f.notCompared)
        assertTrue(f.switches.isEmpty())
    }

    @Test fun `without the group list the cheaper side is unknown`() {
        assertNull(FundsLogic.feesYouPay(mapOf("SPY" to 1_000.0), resp.funds, null).cheapestPerYear)
    }

    @Test fun `fee drag compounds and matches the worked example`() {
        assertEquals(461.0, FundsLogic.feeDrag(0.0945, 0.03), 1.0)   // SPY vs VOO, $10k, 20y, 7%
        assertEquals(7751.0, FundsLogic.feeDrag(1.5, 0.25), 1.0)      // GBTC vs FBTC
        assertEquals(0.0, FundsLogic.feeDrag(0.03, 0.03), 1e-9)
    }

    @Test fun `sizes and small-fund warnings`() {
        assertEquals("\$1.76 trillion", FundsLogic.size(1.7569e12))
        assertEquals("\$104 billion", FundsLogic.size(104.376e9))
        assertEquals("\$38 million", FundsLogic.size(38e6))
        assertNull(FundsLogic.size(null))
        assertTrue(FundsLogic.smallFundWarning(38e6)!!.startsWith("Small fund (\$38 million)"))
        assertNull(FundsLogic.smallFundWarning(1e9))
        assertNull(FundsLogic.smallFundWarning(null))                 // unknown is not small
    }

    @Test fun `trading cost is per round trip, and a mutual fund has none`() {
        val now = 1_790_000_000_000L
        assertEquals("Buying and selling \$10,000 once costs about \$0.33 in the bid-ask gap (checked 2h ago)",
            FundsLogic.tradingCost(0.0033, (now - 2 * 3_600_000) / 1000.0, isMutualFund = false, nowMs = now))
        assertTrue(FundsLogic.tradingCost(null, null, isMutualFund = true)!!.startsWith("No trading gap"))
        assertNull(FundsLogic.tradingCost(null, null, isMutualFund = false))
    }

    @Test fun `percentages, worst drops and cost ranges read plainly`() {
        assertEquals("+86.9%", FundsLogic.pct(86.87))
        assertEquals("−24.5%", FundsLogic.pct(-24.5))
        assertEquals("—", FundsLogic.pct(null))
        assertEquals("−24.5% (Jan–Oct 2022)", FundsLogic.worstDrop(-24.5, "2022-01-03", "2022-10-12"))
        assertEquals("−53.4% (Oct 2025–Jun 2026)", FundsLogic.worstDrop(-53.4, "2025-10-06", "2026-06-30"))
        val g = FundGroup("x", "x", null, listOf(FundCost("A", expenseRatioPct = 0.0), FundCost("B", expenseRatioPct = 0.0945), FundCost("C")))
        assertEquals("\$0–\$9.45 per \$10,000", FundsLogic.costRange(g))
    }

    @Test fun `covers says region and sectors, or that holdings are unknown`() {
        assertEquals("US stocks · Tech 39% · Finance 12%", FundsLogic.covers(resp.funds.getValue("VOO")))
        val broken = FundProfile(symbol = "X", profileOk = false, regionLabel = null)
        assertEquals("holdings unavailable right now", FundsLogic.covers(broken))
    }

    @Test fun `holding the coin and its ETF is one bet`() {
        assertEquals("You also own bitcoin directly: same bet.", FundsLogic.directCryptoNote(listOf("bitcoin"), listOf("BTC")))
        assertNull(FundsLogic.directCryptoNote(listOf("sp500"), listOf("BTC")))
    }

    @Test fun `the server payloads decode`() {
        val ov = Http.json.decodeFromString<FundOverlapResponse>("""
            {"funds":{"VOO":{"symbol":"VOO","kind":"etf","expense_ratio_pct":0.03,"profile_ok":true,
              "region":"us","region_label":"US stocks","sectors":[{"key":"technology","label":"Tech","pct":38.7}],
              "top_holdings":[{"symbol":"NVDA","name":"NVIDIA","pct":8.08}],"top10_pct":39.1,"net_assets":1.7e12,
              "spread_pct":null,"spread_at":null,"group_id":"sp500","history_start":"2021-09-27"},
              "FBTC":{"symbol":"FBTC","kind":"etf","profile_ok":true,"region":"crypto","region_label":"Bitcoin",
              "sectors":[],"top_holdings":[]}},
             "not_funds":["AAPL"],"pairs":[{"a":"VOO","b":"FBTC","corr":0.312,"corr_basis":"weekly","corr_points":100,
              "shared_top":[],"shared_top_count":0,"shared_top_min_pct":0.0,"sector_alike_pct":null}],
             "same_bets":[["VOO"],["FBTC"]],"same_bet_corr":0.9,"live":true,"as_of":1.0}""")
        assertEquals(0.312, ov.pair("FBTC", "VOO")!!.corr!!, 1e-12)
        assertEquals(listOf("AAPL"), ov.notFunds)
        assertTrue(ov.funds.getValue("FBTC").topHoldings!!.isEmpty())   // known-empty, not unknown
        val pf = Http.json.decodeFromString<FundPerformanceResponse>("""
            {"funds":{"VOO":{"symbol":"VOO","available":true,"returns":{"1y":18.56,"3y":85.46,"5y":null},
              "worst_drop_pct":-24.5,"worst_drop_from":"2022-01-03","worst_drop_to":"2022-10-12"},
              "BAD":{"symbol":"BAD","available":false}},
             "chart":{"dates":["2021-09-27","2021-10-04"],"lines":{"VOO":[0.0,1.2]}},"as_of":1.0}""")
        assertNull(pf.funds.getValue("VOO").returns["5y"])
        assertEquals(1.2, pf.chart!!.lines.getValue("VOO")[1], 1e-12)
        assertTrue(!pf.funds.getValue("BAD").available)
    }
}
