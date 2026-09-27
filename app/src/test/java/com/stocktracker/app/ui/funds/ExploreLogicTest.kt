package com.stocktracker.app.ui.funds

import com.stocktracker.app.data.remote.ExploreCopy
import com.stocktracker.app.data.remote.ExploreFund
import com.stocktracker.app.data.remote.FundExploreResponse
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FUND-8 — Explore's filter, sort, top picks and "like it" lists; unknown is never best. */
class ExploreLogicTest {

    private fun f(
        sym: String, name: String, cat: String, fee: Double?, r5: Double?, d5: Double?,
        group: String? = null, kind: String = "etf", size: Double? = 1e10, available: Boolean = true, r1: Double? = r5,
    ) = ExploreFund(
        symbol = sym, name = name, category = cat, expenseRatioPct = fee, groupId = group, kind = kind,
        netAssets = size, available = available,
        returns = mapOf("1y" to r1, "3y" to r5, "5y" to r5), drops = mapOf("1y" to d5, "3y" to d5, "5y" to d5),
    )

    private val voo = f("VOO", "S&P 500", "us", 0.03, 86.9, -24.5, "sp500", size = 1.7e12)
    private val spy = f("SPY", "S&P 500", "us", 0.0945, 86.3, -24.5, "sp500", size = 8e11)
    private val fxaix = f("FXAIX", "S&P 500", "us", 0.015, 87.0, -24.5, "sp500", kind = "mutual_fund")
    private val qqqm = f("QQQM", "Nasdaq-100", "us", 0.15, 107.8, -35.0, "nasdaq100")
    private val rsp = f("RSP", "S&P 500, equal weight", "us", 0.20, null, null, size = null)
    private val xlk = f("XLK", "Tech", "sector", 0.08, 150.0, -33.6)
    private val vgt = f("VGT", "Tech", "sector", 0.09, 140.0, -35.1, "tech")
    private val ftec = f("FTEC", "Tech", "sector", 0.084, 141.0, -35.0, "tech")
    private val smh = f("SMH", "Chip makers", "sector", 0.35, 360.6, -45.3)
    private val broken = f("BRK", "Broken", "sector", null, 999.0, -1.0, available = false)
    private val all = listOf(voo, spy, fxaix, qqqm, rsp, xlk, vgt, ftec, smh, broken)

    @Test fun `filter matches type, ticker start and plain name`() {
        assertEquals(listOf("XLK", "VGT", "FTEC", "SMH", "BRK"), ExploreLogic.filter(all, "sector", "").map { it.symbol })
        assertEquals(listOf("VOO"), ExploreLogic.filter(all, null, "vo").map { it.symbol })
        assertEquals(listOf("XLK", "VGT", "FTEC"), ExploreLogic.filter(all, null, "tech").map { it.symbol })
        assertTrue(ExploreLogic.filter(all, "us", "chip").isEmpty())
    }

    @Test fun `unknown figures sort last and an unreadable fund's numbers are ignored`() {
        val byReturn = ExploreLogic.sort(all, "5y", RankSort.RETURN).map { it.symbol }
        assertEquals("SMH", byReturn.first())
        // RSP has no 5-year figure; BRK's history could not be read, so its 999% is not believed.
        assertEquals(setOf("RSP", "BRK"), byReturn.takeLast(2).toSet())
        val byFee = ExploreLogic.sort(all, "5y", RankSort.FEE).map { it.symbol }
        assertEquals("FXAIX", byFee.first())
        assertEquals("BRK", byFee.last())
        val byDrop = ExploreLogic.sort(all, "5y", RankSort.DROP).map { it.symbol }
        assertEquals("FXAIX", byDrop.first())        // −24.5%, tied with VOO and SPY; the best return breaks it
        assertEquals(setOf("RSP", "BRK"), byDrop.takeLast(2).toSet())
    }

    @Test fun `top picks never choose an unknown`() {
        val p = ExploreLogic.topPicks(all, "5y")
        assertEquals("SMH", p.best?.symbol)
        assertEquals("FXAIX", p.cheapest?.symbol)
        assertEquals("FXAIX", p.calmest?.symbol)
        val none = ExploreLogic.topPicks(listOf(rsp), "5y")
        assertNull(none.best)
        assertNull(none.calmest)
        assertEquals("RSP", none.cheapest?.symbol)
    }

    @Test fun `the default window is the longest most funds can show`() {
        val young = listOf(f("IBIT", "Bitcoin", "crypto", 0.25, null, null, r1 = -23.4), f("FBTC", "Bitcoin", "crypto", 0.25, null, null, r1 = -23.4))
        assertEquals("1y", ExploreLogic.defaultPeriod(young))
        assertEquals("5y", ExploreLogic.defaultPeriod(all))
    }

    @Test fun `same thing lists the measured copies, cheapest first`() {
        assertEquals(listOf("FXAIX", "SPY"), ExploreLogic.sameThing(voo, all).map { it.symbol })
        assertTrue(ExploreLogic.sameThing(xlk, all).isEmpty())
    }

    @Test fun `similar puts the same idea first and shows one fund per copy group`() {
        // XLK's look-alikes by name, VGT and FTEC, are one measured group: shown once, by its cheapest ETF.
        val sim = ExploreLogic.similar(xlk, all, "5y").map { it.symbol }
        assertEquals("FTEC", sim.first())
        assertTrue("VGT" !in sim)
        assertTrue("SMH" in sim)
        // VOO's own copies are never "similar": they are the same thing.
        assertTrue(ExploreLogic.similar(voo, all, "5y").none { it.groupId == "sp500" })
    }

    @Test fun `an exact ticker comes first in a search`() {
        val hits = ExploreLogic.sort(listOf(voo, spy, f("SPYG", "S&P 500 growth", "style", 0.04, 94.6, -32.7)), "5y", RankSort.RETURN)
        assertEquals("SPYG", hits.first().symbol)
        assertEquals("SPY", ExploreLogic.exactFirst(hits, "spy").first().symbol)
        assertEquals(hits, ExploreLogic.exactFirst(hits, "sp"))
    }

    @Test fun `tags say Fidelity and once-a-day plainly`() {
        assertEquals("Fidelity fund", ExploreLogic.tag(fxaix.copy(fidelity = true)))
        assertEquals("Fidelity only", ExploreLogic.tag(fxaix.copy(fidelity = true, fidelityOnly = true)))
        assertEquals("Mutual fund", ExploreLogic.tag(fxaix))
        assertNull(ExploreLogic.tag(voo))
    }

    @Test fun `drops read plainly`() {
        assertEquals("0%", dropText(0.0))
        assertEquals("−24.5%", dropText(-24.5))
        assertEquals("—", dropText(null))
    }

    @Test fun `a build older than a day says so`() {
        val now = 1_790_000_000_000L
        assertNull(ExploreLogic.staleLine(now / 1000.0 - 3600, now))
        assertEquals("Updated 2d ago", ExploreLogic.staleLine(now / 1000.0 - 2 * 86_400, now))
    }

    @Test fun `a real server payload decodes`() {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("fund_explore_sample.json")).bufferedReader().readText()
        val r = Http.json.decodeFromString<FundExploreResponse>(text)
        assertEquals("2026-09-25", r.alignedTo)
        val spy = r.funds.first { it.symbol == "SPY" }
        assertEquals("S&P 500", spy.name)
        assertEquals(ExploreCopy::class, spy.cheaper!!::class)
        assertEquals("SPYM", spy.cheaper!!.symbol)
        assertTrue(spy.returns["5y"] != null && spy.drops["5y"] != null)
        // A two-year-old bitcoin fund has no 5-year figures — null, not zero.
        val ibit = r.funds.first { it.symbol == "IBIT" }
        assertNull(ibit.returns["5y"])
        assertNull(ExploreLogic.drop(ibit, "5y"))
        assertTrue(r.categories.any { it.id == "crypto" && it.label == "Crypto" })
    }
}
