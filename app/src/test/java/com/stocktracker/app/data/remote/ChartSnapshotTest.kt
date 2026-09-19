package com.stocktracker.app.data.remote

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DATA-6 — the quote, sparkline and 52-week range must all come out of ONE decoded chart payload.
 *
 * Before this, [YahooFinanceService.quote], `.sparkline()` (via `history(DAY)`) and
 * `.fiftyTwoWeek()` each asked Yahoo separately for the same symbol's chart, even though every one
 * of those responses carries the exact same `meta` block. [YahooFinanceService.parseChartSnapshot]
 * is the pure parse behind the merged fetch — exercised here against decoded fixtures so the parse
 * itself is proven correct without a network call.
 */
class ChartSnapshotTest {

    private val svc = YahooFinanceService()

    private fun decode(json: String): YahooChartResponse = Http.json.decodeFromString(json)

    /** A realistic minute-bar payload: a null-padded opening tick (a real gap Yahoo sends), and a
     *  mid-session bar that is the true day high/low rather than the first bar. */
    private fun payload(
        dayHigh: String = "188.0",
        dayLow: String = "184.5",
        fiftyTwoWeekHigh: String = "199.62",
        fiftyTwoWeekLow: String = "124.17",
    ) = """
        {"chart":{"result":[{
          "meta":{
            "regularMarketPrice":187.5,"previousClose":185.0,"chartPreviousClose":185.0,
            "regularMarketDayHigh":$dayHigh,"regularMarketDayLow":$dayLow,
            "regularMarketVolume":1234567,"currency":"USD","instrumentType":"EQUITY",
            "fiftyTwoWeekHigh":$fiftyTwoWeekHigh,"fiftyTwoWeekLow":$fiftyTwoWeekLow,
            "exchangeTimezoneName":"America/New_York","marketState":"REGULAR"
          },
          "timestamp":[1700000000,1700000060,1700000120],
          "indicators":{"quote":[{
            "open":[null,186.0,186.5],
            "high":[186.2,189.5,187.0],
            "low":[185.8,183.9,186.4],
            "close":[186.1,186.6,186.9],
            "volume":[1000,1500,1200]
          }]}
        }]}}
    """.trimIndent()

    @Test
    fun `quote sparkline and 52-week all come from the one payload`() {
        val snap = svc.parseChartSnapshot("AAPL", decode(payload()))

        assertEquals("AAPL", snap.quote?.symbol)
        assertEquals(187.5, snap.quote!!.price, 1e-9)
        assertEquals(199.62 to 124.17, snap.fiftyTwoWeek)
        assertEquals(3, snap.sparkline.size)
        assertEquals(listOf(186.1, 186.6, 186.9), snap.sparkline.map { it.price })
    }

    @Test
    fun `the sparkline never carries extended-hours bars`() {
        // chartSnapshot always asks with includePrePost=false, so nothing in the response may be
        // flagged extended — a sparkline is a regular-session-only SHAPE by design.
        val snap = svc.parseChartSnapshot("AAPL", decode(payload()))
        assertTrue(snap.sparkline.none { it.extended })
    }

    @Test
    fun `day high and low prefer the live meta over the bars`() {
        val snap = svc.parseChartSnapshot("AAPL", decode(payload(dayHigh = "188.0", dayLow = "184.5")))
        assertEquals(188.0, snap.quote!!.high!!, 1e-9)
        assertEquals(184.5, snap.quote!!.low!!, 1e-9)
    }

    /**
     * The regression this test pins: with the OLD quote() parse (built for a single 1d/1d bar),
     * a missing meta high/low fell back to `q0.high?.firstOrNull()` — correct when there is exactly
     * one bar, wrong once the merged fetch's minute bars are plugged in, because the first minute's
     * high is not the day's. It must fall back to the max/min of the WHOLE array instead.
     */
    @Test
    fun `a missing meta high-low falls back to the whole array's extreme, not the first bar`() {
        val snap = svc.parseChartSnapshot(
            "AAPL",
            decode(
                """
                {"chart":{"result":[{
                  "meta":{"regularMarketPrice":187.5,"previousClose":185.0},
                  "timestamp":[1700000000,1700000060,1700000120],
                  "indicators":{"quote":[{
                    "open":[186.0,186.0,186.5],
                    "high":[186.2,189.5,187.0],
                    "low":[185.8,183.9,186.4],
                    "close":[186.1,186.6,186.9]
                  }]}
                }]}}
                """.trimIndent(),
            ),
        )
        assertEquals("first bar's high (186.2), not the day's", 189.5, snap.quote!!.high!!, 1e-9)
        assertEquals("first bar's low (185.8), not the day's", 183.9, snap.quote!!.low!!, 1e-9)
    }

    @Test
    fun `open skips a null-padded opening tick rather than reporting no open at all`() {
        val snap = svc.parseChartSnapshot("AAPL", decode(payload()))
        assertEquals(186.0, snap.quote!!.open!!, 1e-9)
    }

    @Test
    fun `a 52-week bound present on only one side is not a range`() {
        val snap = svc.parseChartSnapshot("AAPL", decode(payload(fiftyTwoWeekHigh = "199.62", fiftyTwoWeekLow = "null")))
        assertNull(snap.fiftyTwoWeek)
    }

    @Test
    fun `a symbol Yahoo has no data for yields an empty snapshot, not an exception`() {
        val snap = svc.parseChartSnapshot("DEADTICKER", decode("""{"chart":{"result":null}}"""))
        assertNull(snap.quote)
        assertTrue(snap.sparkline.isEmpty())
        assertNull(snap.fiftyTwoWeek)
    }

    @Test
    fun `change and percent are measured against the previous close`() {
        val snap = svc.parseChartSnapshot("AAPL", decode(payload()))
        assertEquals(187.5 - 185.0, snap.quote!!.change, 1e-9)
        assertEquals((187.5 - 185.0) / 185.0 * 100.0, snap.quote!!.changePercent, 1e-9)
    }
}
