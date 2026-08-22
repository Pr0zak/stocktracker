package com.stocktracker.app.ui

import com.stocktracker.app.data.remote.MarketScanRow
import com.stocktracker.app.ui.marketscan.MarketScanUiState
import com.stocktracker.app.ui.marketscan.MetricRank
import com.stocktracker.app.ui.marketscan.RANKED_SCAN_METRICS
import com.stocktracker.app.ui.marketscan.scanMetricFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SWT-4's rendering rules, pinned.
 *
 * The defect these exist to prevent is a single character: `?: 0.0`. A percentile of 0 is a
 * confident statement — "this name held the lowest value in the whole market that night" — and it
 * would be made about exactly the names nothing could be measured on. Every test below is a way
 * that sentence could get printed by accident.
 */
class MetricRankTest {

    @Test
    fun `an absent rank renders the raw value alone, never a zeroth percentile`() {
        assertNull(MetricRank.ordinal(null))
        assertNull(MetricRank.label(null, 3101))
        assertNull(MetricRank.short(null))
        assertNull("a null rank must draw NO bar — a zero-width one says worst-in-market",
            MetricRank.fraction(null))

        val line = MetricRank.line("81.4", null, 3101)
        assertEquals("81.4", line)
        assertFalse(line.contains("percentile"))
        assertFalse(line.contains("0th"))
        assertFalse(line.contains("—th"))
        assertFalse(line.contains("("))
    }

    @Test
    fun `a measured zero IS a rank and says so`() {
        // 0.0 is not the absent case: it means this name held the lowest MEASURED value in the
        // night's cross-section. Collapsing it into "no rank" would hide a real reading, exactly as
        // rendering the absent case as 0 would invent one.
        assertEquals("0th", MetricRank.ordinal(0.0))
        assertEquals("0th percentile of 3,101 scanned", MetricRank.label(0.0, 3101))
        assertEquals(0f, MetricRank.fraction(0.0)!!, 1e-6f)
        assertEquals("0.31× (0th percentile of 3,101 scanned)", MetricRank.line("0.31×", 0.0, 3101))
    }

    @Test
    fun `the top of the scale is the hundredth percentile and a full bar`() {
        assertEquals("100th percentile of 3,101 scanned", MetricRank.label(100.0, 3101))
        assertEquals(1f, MetricRank.fraction(100.0)!!, 1e-6f)
    }

    @Test
    fun `ordinals are English, including the teens`() {
        assertEquals("1st", MetricRank.ordinal(1.0))
        assertEquals("2nd", MetricRank.ordinal(2.0))
        assertEquals("3rd", MetricRank.ordinal(3.0))
        assertEquals("4th", MetricRank.ordinal(4.0))
        // 11th/12th/13th, not 11st/12nd/13rd.
        assertEquals("11th", MetricRank.ordinal(11.0))
        assertEquals("12th", MetricRank.ordinal(12.0))
        assertEquals("13th", MetricRank.ordinal(13.0))
        assertEquals("21st", MetricRank.ordinal(21.0))
        assertEquals("22nd", MetricRank.ordinal(22.0))
        assertEquals("23rd", MetricRank.ordinal(23.0))
        assertEquals("93rd", MetricRank.ordinal(93.0))
        // The store reports one decimal; the ordinal is the nearest whole rank.
        assertEquals("59th", MetricRank.ordinal(59.3))
        assertEquals("60th", MetricRank.ordinal(59.7))
    }

    @Test
    fun `a rank is never printed bare`() {
        // "96" reads as a score out of a hundred, and nothing here is scored: high RSI is not good,
        // and the 99th percentile of average daily range is the most volatile name, not the best.
        val long = MetricRank.label(96.0, 3101)!!
        assertTrue(long.contains("percentile"))
        assertTrue(long.contains("3,101"))
        val short = MetricRank.short(96.0)!!
        assertTrue(short.contains("pctile"))
        assertFalse(short == "96")
    }

    @Test
    fun `an unstated population becomes words, never a borrowed number`() {
        // The denominator is the night's whole cross-section. When the server did not say, the only
        // honest thing is to say so — substituting the rows on screen or a filtered total would
        // label the rank with a population it was never computed over.
        assertEquals("96th percentile of the night's scan", MetricRank.label(96.0, null))
        // A count of zero cannot have produced a rank, so it is treated as unsaid.
        assertEquals("96th percentile of the night's scan", MetricRank.label(96.0, 0))
        assertEquals("96th percentile of the night's scan", MetricRank.label(96.0, -5))
    }

    @Test
    fun `a small population is printed rather than hidden`() {
        // A limited smoke run ranks over a handful of names. Printing the denominator is the whole
        // defence: "98th percentile of 50 scanned" cannot be misread as a claim about the market.
        assertEquals("98th percentile of 50 scanned", MetricRank.label(98.0, 50))
    }

    @Test
    fun `an impossible rank is refused rather than clamped into shape`() {
        // A broken producer must not be rendered as though it had been understood. 105 clamped to
        // 100 publishes a number nobody computed.
        assertNull(MetricRank.ordinal(105.0))
        assertNull(MetricRank.ordinal(-5.0))
        assertNull(MetricRank.ordinal(Double.NaN))
        assertNull(MetricRank.ordinal(Double.POSITIVE_INFINITY))
        assertNull(MetricRank.fraction(Double.NaN))
        assertEquals("7.1", MetricRank.line("7.1", Double.NaN, 3101))
        // Float noise at the ends of a (position)/(n-1) scale is tolerated, not refused.
        assertEquals("100th", MetricRank.ordinal(100.00000000000001))
        assertEquals("0th", MetricRank.ordinal(-1e-14))
    }

    @Test
    fun `a missing measurement takes its rank down with it`() {
        // Nothing for the rank to be a rank OF. A lone "(96th percentile)" beside a dash invites the
        // reader to supply the missing number themselves.
        assertEquals(MetricRank.NA, MetricRank.line(null, 96.0, 3101))
        assertEquals(MetricRank.NA, MetricRank.line("", 96.0, 3101))
        assertEquals(MetricRank.NA, MetricRank.line("   ", 96.0, 3101))
        assertEquals(MetricRank.NA, MetricRank.line(MetricRank.NA, 96.0, 3101))
    }

    @Test
    fun `a full reading puts the value first and the rank beside it`() {
        assertEquals("1.40× (96th percentile of 3,101 scanned)", MetricRank.line("1.40×", 96.0, 3101))
    }

    // ------------------------------------------------------------------ the metric catalogue

    @Test
    fun `every ranked metric reads its own percentile field, and an unmeasured row shows neither`() {
        val full = MarketScanRow(
            symbol = "NVDA", price = 184.2, adr20Pct = 2.9, adx14 = 31.4, relVolume = 0.78,
            dollarVolume20d = 2.7e10, mom20d = 8.1, mom60d = 22.5, rsi14 = 63.2,
            pctOff52wHigh = -1.2, relStrength3mo = 14.2, ema20SlopePct = 0.9, atr14Pct = 2.8,
            relStrength3moPctile = 86.0, relVolumePctile = 59.3, adr20PctPctile = 71.2,
            adx14Pctile = 88.8, mom20dPctile = 90.1, mom60dPctile = 93.4, rsi14Pctile = 77.7,
            pctOff52wHighPctile = 99.1, ema20SlopePctPctile = 81.0, dollarVolume20dPctile = 100.0,
        )
        // Every metric the server ranks gets a percentile out of THIS row, not out of a lookup that
        // could quietly fall back to another metric's number.
        RANKED_SCAN_METRICS.filter { it.rankedByServer }.forEach { m ->
            assertNotNull("${m.key} lost its rank", m.percentile(full))
            assertTrue("${m.key} did not render its rank", m.line(full, 3101).contains("percentile of 3,101 scanned"))
        }
        assertEquals("0.78× (59th percentile of 3,101 scanned)",
            RANKED_SCAN_METRICS.first { it.key == "rel_volume" }.line(full, 3101))
        assertEquals("$27.00B (100th percentile of 3,101 scanned)",
            RANKED_SCAN_METRICS.first { it.key == "dollar_volume_20d" }.line(full, 3101))

        // A night stored before the ranking pass ran: raw numbers, no ranks, no zeros.
        val unranked = MarketScanRow(symbol = "NVDA", relVolume = 0.78, rsi14 = 63.2)
        RANKED_SCAN_METRICS.forEach { m -> assertNull("${m.key} invented a rank", m.percentile(unranked)) }
        assertEquals("0.78×", RANKED_SCAN_METRICS.first { it.key == "rel_volume" }.line(unranked, 3101))
        assertEquals("63.2", RANKED_SCAN_METRICS.first { it.key == "rsi14" }.line(unranked, 3101))

        // A row the metric was never measured on: an em dash, and no rank hanging off it.
        val blank = MarketScanRow(symbol = "IPO")
        RANKED_SCAN_METRICS.forEach { m ->
            assertEquals("${m.key} rendered something out of nothing", MetricRank.NA, m.line(blank, 3101))
        }
    }

    @Test
    fun `a metric the server does not rank renders as a bare number`() {
        // atr14_pct is measured and sortable but never ranked — the volatility axis is ranked through
        // adr20_pct. "Not ranked" must render as the number alone, never as a rank of zero.
        val m = RANKED_SCAN_METRICS.first { it.key == "atr14_pct" }
        assertFalse(m.rankedByServer)
        val row = MarketScanRow(symbol = "A", atr14Pct = 2.8, adr20PctPctile = 71.2)
        assertNull(m.percentile(row))
        assertEquals("2.8%", m.line(row, 3101))
    }

    @Test
    fun `a non-finite measurement renders as a dash rather than NaN percent`() {
        val row = MarketScanRow(symbol = "A", rsi14 = Double.NaN, rsi14Pctile = 50.0)
        assertEquals(MetricRank.NA, RANKED_SCAN_METRICS.first { it.key == "rsi14" }.line(row, 3101))
    }

    // ------------------------------------------------- the one line that frames the whole list

    @Test
    fun `the list says once, in words, what its percentiles are ranks within`() {
        val st = MarketScanUiState(
            asOf = "20260821", percentilesOver = 3101, totalMatching = 812,
            rows = listOf(MarketScanRow(symbol = "NVDA")),
        )
        assertEquals(
            "Percentiles are ranks within the 2026-08-21 scan of 3,101 names — not scores, and not a buy signal.",
            st.rankFooter,
        )
    }

    @Test
    fun `an unstated population is never filled in from the rows on screen`() {
        // 812 matched the filter and 1 row is displayed. Neither is the population the ranks were
        // computed over, so neither may appear in the sentence that names it.
        val st = MarketScanUiState(
            asOf = "20260821", percentilesOver = null, totalMatching = 812,
            rows = listOf(MarketScanRow(symbol = "NVDA")),
        )
        assertEquals("Percentiles are ranks within the 2026-08-21 scan — not scores, and not a buy signal.",
            st.rankFooter)
        assertFalse(st.rankFooter.contains("812"))
        assertFalse(st.rankFooter.contains(" 1 "))

        // Nothing known at all still produces a sentence that cannot be read as a score.
        val empty = MarketScanUiState()
        assertEquals("Percentiles are ranks within that night's scan — not scores, and not a buy signal.",
            empty.rankFooter)
    }

    @Test
    fun `a sort key finds its metric from either direction, and an unknown one finds nothing`() {
        assertEquals("atr14_pct", scanMetricFor("-atr14_pct")?.key)
        assertEquals("rel_strength_3mo", scanMetricFor("rel_strength_3mo")?.key)
        // No fallback to the first metric: labelling some other number with the sort's name would be
        // worse than showing nothing.
        assertNull(scanMetricFor("price"))
        assertNull(scanMetricFor(null))
        assertNull(scanMetricFor("  "))
    }
}
