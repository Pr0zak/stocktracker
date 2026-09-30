package com.stocktracker.app.ui.portfolio

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioAnswersTest {
    private fun h(sym: String, value: Double, day: Double, cost: Double?) =
        Holding(Asset(sym, AssetType.STOCK, sym), shares = 1.0, price = value, value = value, dayChange = day, costBasis = cost)

    private val book = listOf(
        h("VTI", 2619.68, -7.14, 2555.25),
        h("JNJ", 794.22, -8.49, 823.63),
        h("FBTC", 508.48, -0.49, 391.83),
    )

    @Test fun `names the biggest weight, the worst day and the best since bought`() {
        val s = PortfolioAnswers.standouts(book, 3922.38, todayKnown = true)
        assertEquals(listOf("Most in one place" to "VTI", "Biggest drop today" to "JNJ", "Best since bought" to "FBTC"),
                     s.map { it.title to it.symbol })
        assertEquals("67%", s[0].value)
        assertEquals("−1.06%", s[1].value)
        assertEquals("+29.8%", s[2].value)
    }

    @Test fun `no today answer from old quotes`() {
        val s = PortfolioAnswers.standouts(book, 3922.38, todayKnown = false)
        assertTrue(s.none { it.kind == PortfolioAnswers.Kind.TODAY })
    }

    @Test fun `an all-green day names the best mover instead`() {
        val up = listOf(h("A", 100.0, 2.0, null), h("B", 100.0, 5.0, null))
        val s = PortfolioAnswers.standouts(up, 200.0, todayKnown = true)
        assertEquals("Best today" to "B", s.first { it.kind == PortfolioAnswers.Kind.TODAY }.let { it.title to it.symbol })
    }
}
