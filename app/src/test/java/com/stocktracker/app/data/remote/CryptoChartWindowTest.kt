package com.stocktracker.app.data.remote

import com.stocktracker.app.data.model.ChartRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A crypto chart's window must be as wide as its label says.
 *
 * The windows carried slack copied from the STOCK path's reasoning, where a Monday morning has to
 * reach back over a weekend to find the last session: 2 days for DAY, 8 for WEEK, 32 / 95 / 370 for
 * the rest. Crypto trades 24/7, so there is no weekend to reach over and the slack was simply extra
 * chart under a label that promised less.
 *
 * Measured against live BTC-USD on 2026-09-03: the "1D" chart spanned 48.0 hours and read -0.40%,
 * while the true trailing 24 hours was +1.32%. The opposite SIGN, not merely a wrong magnitude — and
 * `asPercentChange` rebases on the series' first point, so % mode printed that number directly. The
 * same series backs `MarketRepository.sparkline`, so the watchlist rows and the home-screen widget
 * drew two days of shape with the previous-close baseline sitting mid-line instead of at the edge.
 */
class CryptoChartWindowTest {

    private companion object {
        const val NOW = 1_788_500_000L
        const val DAY = 86_400L
        val svc = YahooFinanceService()

        /** The window's width in days, parsed back out of the query string. */
        fun spanDays(range: ChartRange): Double {
            val p = svc.cryptoChartParams(range, NOW)
            val from = Regex("period1=(\\d+)").find(p)!!.groupValues[1].toLong()
            val to = Regex("period2=(\\d+)").find(p)!!.groupValues[1].toLong()
            return (to - from) / DAY.toDouble()
        }

        fun interval(range: ChartRange): String =
            Regex("interval=([^&]+)").find(svc.cryptoChartParams(range, NOW))!!.groupValues[1]
    }

    @Test fun `each window is exactly as wide as its label`() {
        assertEquals("1D must be one day, not two", 1.0, spanDays(ChartRange.DAY), 0.0)
        assertEquals(7.0, spanDays(ChartRange.WEEK), 0.0)
        assertEquals(30.0, spanDays(ChartRange.MONTH), 0.0)
        assertEquals("a quarter, not 95 days", 91.0, spanDays(ChartRange.QUARTER), 0.0)
        assertEquals(365.0, spanDays(ChartRange.YEAR), 0.0)
        assertEquals(1095.0, spanDays(ChartRange.THREE_YEAR), 0.0)
    }

    /**
     * The regression, stated as the thing a reader would check. A 1D chart twice as wide as a day
     * does not merely look odd: `asPercentChange` rebases on the first point, so the number printed
     * beside a "1D" label is a two-day change.
     */
    @Test fun `the day window cannot silently become a two-day window again`() {
        val p = svc.cryptoChartParams(ChartRange.DAY, NOW)
        assertTrue("period1 must be exactly 24h back, was: $p", p.contains("period1=${NOW - DAY}"))
        assertTrue(p.contains("period2=$NOW"))
    }

    @Test fun `every window ends now, so the view is trailing rather than calendar-aligned`() {
        // "1D" on a market that never closes means the last 24 hours, not "since midnight UTC".
        ChartRange.entries.forEach { r ->
            assertTrue("$r must end at now", svc.cryptoChartParams(r, NOW).contains("period2=$NOW"))
        }
    }

    @Test fun `intervals keep enough detail for the span without flooding it`() {
        assertEquals("5m", interval(ChartRange.DAY))
        assertEquals("30m", interval(ChartRange.WEEK))
        assertEquals("1d", interval(ChartRange.MONTH))
        assertEquals("1d", interval(ChartRange.QUARTER))
        assertEquals("1d", interval(ChartRange.YEAR))
        assertEquals("1d", interval(ChartRange.THREE_YEAR))
        assertEquals("1wk", interval(ChartRange.ALL))
    }

    /**
     * ALL is deliberately NOT a trailing width: `range=max&interval=1wk` silently truncates crypto to
     * about three years, so the start is pinned before any of these coins traded.
     */
    @Test fun `ALL reaches back further than any coin's history rather than a fixed width`() {
        val p = svc.cryptoChartParams(ChartRange.ALL, NOW)
        assertTrue(p.contains("period1=1262304000"))   // 2010-01-01, pre-dating BTC's listing
        assertTrue(p.contains("interval=1wk"))
    }

    @Test fun `no range asks for a window that has not happened yet`() {
        ChartRange.entries.forEach { r ->
            val from = Regex("period1=(\\d+)").find(svc.cryptoChartParams(r, NOW))!!.groupValues[1].toLong()
            assertTrue("$r starts in the future", from < NOW)
        }
    }
}
