package com.stocktracker.app.ui.report

import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.Report
import com.stocktracker.app.data.remote.ReportSummary
import com.stocktracker.app.notify.ReportNotifier
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * RPT-1 — the report's app-side rules:
 *   * the portfolio is priced at the two exact bounding closes; a holding without both is NAMED as
 *     left out, never priced from the day before and never silently dropped;
 *   * no holdings is "no portfolio", not a flat one;
 *   * every missing number reads "—", never 0.00%;
 *   * the notification leads with the user's own result, and only when the phone could price it.
 */
class ReportTest {

    /** A US daily bar: stamped at the 13:30 UTC open, like Yahoo's. */
    private fun bar(date: String, price: Double) = PricePoint(
        LocalDate.parse(date).atTime(13, 30).toInstant(ZoneOffset.UTC).toEpochMilli(), price,
    )

    private val start = LocalDate.parse("2026-09-18")
    private val end = LocalDate.parse("2026-09-25")

    @Test
    fun portfolioIsPricedBetweenTheTwoCloses() {
        val p = ReportPortfolioMath.compute(
            "week-2026-09-25", start, end,
            listOf(
                ReportPortfolioMath.Input("MSFT", "Microsoft", 4.0, listOf(bar("2026-09-17", 490.0), bar("2026-09-18", 493.78), bar("2026-09-25", 516.17))),
                ReportPortfolioMath.Input("GLDM", "Gold", 10.0, listOf(bar("2026-09-18", 86.59), bar("2026-09-25", 84.89))),
            ),
            nowMs = 1L,
        )!!
        assertEquals(4 * 493.78 + 10 * 86.59, p.startValue!!, 1e-9)
        assertEquals(4 * 516.17 + 10 * 84.89, p.endValue!!, 1e-9)
        assertEquals("MSFT", p.best?.symbol)
        assertEquals("GLDM", p.worst?.symbol)
        assertEquals(1, p.upCount)
        assertTrue(p.priced)
    }

    @Test
    fun aHoldingMissingAnEndCloseIsNamedNotPricedFromTheDayBefore() {
        val p = ReportPortfolioMath.compute(
            "week-2026-09-25", start, end,
            listOf(
                ReportPortfolioMath.Input("AAA", "A", 1.0, listOf(bar("2026-09-18", 10.0), bar("2026-09-24", 11.0))),
                ReportPortfolioMath.Input("BBB", "B", 1.0, listOf(bar("2026-09-18", 10.0), bar("2026-09-25", 12.0))),
            ),
            nowMs = 1L,
        )!!
        assertEquals(listOf("AAA"), p.unpriced)
        assertEquals(listOf("BBB"), p.holdings.map { it.symbol })
        assertEquals(20.0, p.changePct!!, 1e-9)
    }

    @Test
    fun noHoldingsIsNoPortfolioAndNothingPricedIsNotStoredAsPriced() {
        assertNull(ReportPortfolioMath.compute("w", start, end, emptyList(), 1L))
        assertNull(ReportPortfolioMath.compute("w", start, end, listOf(ReportPortfolioMath.Input("A", "A", 0.0, emptyList())), 1L))
        val none = ReportPortfolioMath.compute("w", start, end, listOf(ReportPortfolioMath.Input("A", "A", 2.0, emptyList())), 1L)!!
        assertFalse(none.priced)
        assertNull(none.changePct)
        assertEquals(listOf("A"), none.unpriced)
    }

    @Test
    fun aCryptoBarStampedAtUtcMidnightLandsOnItsOwnDay() {
        val midnight = LocalDate.parse("2026-09-25").atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(LocalDate.parse("2026-09-25"), ReportPortfolioMath.barDate(midnight))
    }

    @Test
    fun missingNumbersReadAsADashNeverZero() {
        assertEquals("—", ReportRead.pct(null))
        assertEquals("—", ReportRead.pct(Double.NaN))
        assertEquals("—", ReportRead.usd(null))
        assertNull(ReportRead.vsSp(null))
        assertEquals("▲ 1.21%", ReportRead.pct(1.21))
        assertEquals("▼ 0.33%", ReportRead.pct(-0.33))
        assertEquals("−$37.75", ReportRead.usd(-37.75))
        assertEquals("▼ 1.6 pts behind S&P", ReportRead.vsSp(-1.57))
        assertEquals("▲ 0.3 pts vs S&P", ReportRead.vsSp(0.26))
        // A gap too small to round to a tenth has no direction to claim.
        assertEquals("Even with the S&P", ReportRead.vsSp(-0.0499))
    }

    @Test
    fun titlesAndOverlinesReadTheWayPeopleSayThem() {
        assertEquals("WEEK OF SEP 21", ReportRead.overline("week", "Sep 21 – 25"))
        assertEquals("SEPTEMBER 2026", ReportRead.overline("month", "September 2026"))
        assertEquals("Fri Sep 25 close", ReportRead.closeLabel("2026-09-25"))
        assertEquals("Week of Sep 21", rowTitle(ReportSummary(kind = "week", label = "Sep 21 – 25")))
        assertEquals("September 2026", rowTitle(ReportSummary(kind = "month", label = "September 2026")))
    }

    @Test
    fun theNotificationLeadsWithTheUsersOwnResultOnlyWhenItWasPriced() {
        assertEquals("Your week: you ▲ 1.28%, S&P ▲ 1.21%", ReportRead.notificationTitle("week", "Sep 21 – 25", 1.28, 1.21))
        assertEquals("Week in review: S&P ▲ 1.21%", ReportRead.notificationTitle("week", "Sep 21 – 25", null, 1.21))
        assertEquals("September: you ▲ 1.56%, S&P ▲ 0.75%", ReportRead.notificationTitle("month", "September 2026", 1.56, 0.75))
        val body = ReportRead.notificationBody(ReportSummary(sandboxPct = -0.33, headline = "Big tech lifted the S&P. Most stocks fell."))
        assertEquals("AI sandbox ▼ 0.33%. Big tech lifted the S&P. Most stocks fell.", body)
        assertEquals("Tap to read the report.", ReportRead.notificationBody(ReportSummary()))
    }

    @Test
    fun theDailyPickLineSaysWhenItDidNotRunEveryDay() {
        assertEquals("Ran 4 of 5 days. Too few stocks were rising", ReportRead.pickLine(4, 5, "Too few stocks were rising"))
        assertEquals("0 of 5 days. Too few stocks were rising", ReportRead.pickLine(5, 5, "Too few stocks were rising"))
    }

    @Test
    fun onlyARecentlyEndedPeriodIsNews() {
        val sat = LocalDate.parse("2026-09-26")
        assertTrue(ReportRead.isNews("2026-09-25", sat, "week"))
        assertFalse(ReportRead.isNews("2026-08-31", sat, "month"))   // a catch-up rebuild of August
        assertTrue(ReportRead.isNews("2026-09-30", LocalDate.parse("2026-10-01"), "month"))
        assertFalse(ReportRead.isNews(null, sat, "week"))
    }

    @Test
    fun theNextReportIsTheNextUnpublishedPeriod() {
        val none: (LocalDate) -> Boolean = { false }
        val sat = LocalDate.parse("2026-09-26")
        // Saturday: this week's report is out, so the next weekly one is Friday Oct 2.
        assertEquals(LocalDate.parse("2026-10-02"), ReportRead.nextReportEnd("week", sat, none, setOf("week-2026-09-25")))
        // Friday morning, before it is built: today's.
        assertEquals(LocalDate.parse("2026-09-25"), ReportRead.nextReportEnd("week", LocalDate.parse("2026-09-25"), none, emptySet()))
        // A Good-Friday week ends on the Thursday.
        val goodFriday: (LocalDate) -> Boolean = { it == LocalDate.parse("2026-04-03") }
        assertEquals(LocalDate.parse("2026-04-02"), ReportRead.nextReportEnd("week", LocalDate.parse("2026-03-30"), goodFriday, emptySet()))
        assertEquals(LocalDate.parse("2026-09-30"), ReportRead.nextReportEnd("month", sat, none, setOf("month-2026-08-31")))
        assertEquals(LocalDate.parse("2026-10-30"), ReportRead.nextReportEnd("month", LocalDate.parse("2026-10-01"), none, setOf("month-2026-09-30")))
    }

    @Test
    fun aQuickLossIsWordedAndOthersAreNot() {
        assertEquals("Sold at a loss after 11 days", ReportRead.fillFlag("quick_loss", 11))
        assertNull(ReportRead.fillFlag(null, 45))
    }

    @Test
    fun announcedIdsArePrunedPerKind() {
        val ids = (1..150).map { "week-2026-%03d".format(it) }.toSet() + setOf("month-2026-08-31", "month-2026-09-30")
        val kept = ReportNotifier.prune(ids)
        assertTrue("month-2026-08-31" in kept && "month-2026-09-30" in kept)
        assertEquals(100, kept.count { it.startsWith("week") })
        assertTrue("week-2026-150" in kept && "week-2026-001" !in kept)
    }

    @Test
    fun anUnavailableReportDecodesWithoutNumbers() {
        val r = Http.json.decodeFromString<Report>("""{"available": false, "kind": "week", "reason": "No report yet."}""")
        assertFalse(r.available)
        assertNull(r.sp500Pct)
        val full = Http.json.decodeFromString<Report>(
            """{"available": true, "id": "week-2026-09-25", "kind": "week",
               "market": {"indexes": [{"key": "sp500", "measured": true, "pct": 1.21, "close": 7743.41},
                                      {"key": "small_caps", "measured": false, "pct": null}]},
               "sandbox": {"available": true, "main": {"change_pct": -0.33, "change_usd": null}}}""",
        )
        assertEquals(1.21, full.sp500Pct!!, 0.0)
        assertNull(full.index("small_caps")?.pct)
        assertNull(full.sandbox?.main?.changeUsd)
    }
}
