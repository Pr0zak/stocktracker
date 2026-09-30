package com.stocktracker.app.ui.detail

import androidx.compose.ui.graphics.Color
import com.stocktracker.app.data.remote.CompanyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutAndSignalsWordingTest {
    private val vicr = CompanyProfile(
        symbol = "VICR", sector = "Technology", industry = "Electronic Components",
        summary = "Vicor Corporation designs power modules. It serves aircraft makers.",
        employees = 1092, city = "Andover", state = "MA", country = "United States", founded = 1981,
        exchange = "NasdaqGS", marketCap = 13_320_000_000.0, revenueGrowthPct = 49.3, profitMarginPct = 30.65, pe = 92.91,
    )

    @Test fun `market pills read sector then industry, a fund its category`() {
        assertEquals(listOf("Technology", "Electronic components"), AboutText.market(vicr))
        assertEquals(listOf("Large blend"), AboutText.market(CompanyProfile(category = "Large Blend")))
    }

    @Test fun `the plain line wins, else the first sentence of the description`() {
        assertEquals("Vicor Corporation designs power modules.", AboutText.lead(vicr))
        assertEquals("Makes power modules", AboutText.lead(vicr.copy(whatItDoes = "Makes power modules")))
        assertNull(AboutText.lead(CompanyProfile()))
    }

    @Test fun `key figures are named in plain words and absent ones are left out`() {
        val t = AboutText.tiles(vicr).associate { it.label to it.value }
        assertEquals("$13.3B", t["Company size"])
        assertEquals("+49%", t["Sales growth"])
        assertEquals("31¢", t["Kept as profit"])
        assertEquals("$93", t["Price per $1 of profit"])
        assertTrue(AboutText.tiles(CompanyProfile()).isEmpty())
    }

    @Test fun `a loss-maker shows money lost and no price-per-profit`() {
        val t = AboutText.tiles(CompanyProfile(profitMarginPct = -12.4, pe = -8.0)).map { it.label }
        assertEquals(listOf("Losing money"), t)
    }

    @Test fun `facts line`() {
        assertEquals("Andover, MA · 1,092 staff · since 1981 · NasdaqGS", AboutText.factsLine(vicr))
    }

    private fun f(name: String, bucket: Int) = SnapFactor(name, "x", "", bucket, Color.Gray)

    @Test fun `the signals lead names what points up and down`() {
        val l = SignalsWording.lead(listOf(f("Momentum", 1), f("Value", 0), f("Smart money", 1), f("Short pressure", 0)))
        assertEquals("Leaning up", l.title)
        assertEquals("Momentum and insiders point up. Nothing points down.", l.sentence)
        val m = SignalsWording.lead(listOf(f("Momentum", 1), f("Value", -1)))
        assertEquals("MIXED", m.pill)
        assertEquals("Momentum points up. Price vs value points down.", m.sentence)
    }

    @Test fun `month names expand`() {
        assertEquals("September", monthName("Sep"))
        assertEquals("Foo", monthName("Foo"))
    }
}
