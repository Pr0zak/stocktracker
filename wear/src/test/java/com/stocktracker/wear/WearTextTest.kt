package com.stocktracker.wear

import com.stocktracker.shared.PortfolioSummary
import com.stocktracker.shared.Quote
import com.stocktracker.shared.WearPortfolioSnapshot
import com.stocktracker.shared.WearSnapshot
import com.stocktracker.shared.WearTickerSnapshot
import com.stocktracker.shared.wearContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WearText] never invents a freshness/partial judgment of its own -- these tests are about
 *  formatting for a fixed-width surface, not the honesty rules ([WearSnapshotTest] in `:shared`,
 *  reused verbatim here, covers those). */
class WearTextTest {

    private val asOf = 1_700_000_000_000L

    private fun tickerContent(price: Double = 150.0, error: String? = null, name: String = "Apple") =
        wearContent(
            WearSnapshot(ticker = WearTickerSnapshot(quote = Quote("AAPL", price, 1.5, 1.0, asOfEpochMs = asOf), error = error, displayName = name)),
            nowMs = asOf + 1_000L,
        )

    @Test
    fun `not configured shows the app name as a title and no headline`() {
        val content = wearContent(snapshot = null, nowMs = 1L)
        assertEquals("StockTracker", WearText.title(content))
        assertNull(WearText.headline(content))
        assertEquals("Open the phone app to configure", WearText.statusLine(content))
    }

    @Test
    fun `a priced ticker shows its display name, price, and signed percent`() {
        val content = tickerContent(price = 150.234)
        assertEquals("Apple", WearText.title(content))
        assertEquals("$150.23", WearText.headline(content))
        assertEquals("▲ +1.00%", WearText.changeLine(content))
        assertNull(WearText.statusLine(content)) // fresh -- nothing to disclose
    }

    @Test
    fun `a failed refresh discloses failure over the routine age line`() {
        val content = tickerContent(error = "429")
        assertEquals("Update failed", WearText.statusLine(content))
    }

    @Test
    fun `no quote at all is not rendered as a zero or blank price`() {
        val content = wearContent(WearSnapshot(ticker = WearTickerSnapshot(displayName = "Apple")), nowMs = 1L)
        assertNull(WearText.headline(content))
        assertEquals("Loading…", WearText.statusLine(content))
    }

    @Test
    fun `a partial portfolio total says so in the status line`() {
        val content = wearContent(
            WearSnapshot(portfolio = WearPortfolioSnapshot(
                summary = PortfolioSummary(totalValue = 500.0, holdingCount = 5, missingCount = 2),
                loaded = true,
            )),
            nowMs = 1L,
        )
        assertEquals("$500.00", WearText.headline(content))
        assertEquals("3 of 5 priced", WearText.statusLine(content))
    }

    @Test
    fun `complication short text stays within a watch-face slot`() {
        val content = tickerContent(price = 150.0)
        assertTrue(WearText.complicationShortText(content).length <= 7)
    }

    @Test
    fun `complication short text compacts a large portfolio total instead of truncating it blindly`() {
        val content = wearContent(
            WearSnapshot(portfolio = WearPortfolioSnapshot(
                summary = PortfolioSummary(totalValue = 128_400.0, holdingCount = 2),
                loaded = true,
            )),
            nowMs = 1L,
        )
        assertEquals("$128.4K", WearText.complicationShortText(content))
    }

    @Test
    fun `complication long text carries the title, price, and change together`() {
        val content = tickerContent(price = 150.0)
        assertEquals("Apple $150.00 ▲ +1.00%", WearText.complicationLongText(content))
    }
}
