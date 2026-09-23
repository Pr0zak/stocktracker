package com.stocktracker.app.ui.pick

import com.stocktracker.app.data.remote.DailyPick
import com.stocktracker.app.data.remote.DailyPickComparison
import com.stocktracker.app.data.remote.DailyPickLevels
import com.stocktracker.app.data.remote.DailyPickReportCard
import com.stocktracker.app.data.remote.DailyPickResponse
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.ui.pick.DailyPickRead.Alert
import com.stocktracker.app.ui.pick.DailyPickRead.PriceState
import com.stocktracker.app.ui.pick.DailyPickRead.Shape
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DP-8 — the Daily Pick card's honesty rules on the app side:
 *   * a failed LOAD shows the failure, never the previous pick;
 *   * a failed RUN is "failed", never "no pick today", and sends no morning notification;
 *   * yesterday's pick is headed with its own date;
 *   * an absent level or percentile is absent — never $0, never the 0th percentile;
 *   * intraday alerts fire on real crossings only, once each, and stop/target end the day.
 */
class DailyPickReadTest {

    private val levels = DailyPickLevels(entryLow = 98.0, entryHigh = 101.0, stop = 94.0, target = 112.0)

    private fun pick(date: String = "2026-09-22", stale: Boolean = false) = DailyPickResponse(
        available = true, stale = stale, date = date, status = "pick",
        pick = DailyPick(symbol = "BRK-B", conviction = 72, thesis = "Quality holding its trend.", levels = levels),
    )

    // ------------------------------------------------------------ shape

    @Test fun `a load error wins over any response`() {
        val s = DailyPickRead.shape(configured = true, loading = false, resp = pick(), error = "boom")
        assertTrue(s is Shape.LoadFailed)
    }

    @Test fun `a fresh card is loading, not a failed load`() {
        // Before the first request answers there is no response to report on. The card used to
        // start with loading = false and claim "No response from the Signals service".
        assertTrue(DailyPickUiState().shape is Shape.Loading)
    }

    @Test fun `times read in the phone's zone, not Eastern`() {
        val ct = java.time.ZoneId.of("America/Chicago")
        // 2026-09-23 12:05 UTC = 8:05 AM EDT = 7:05 AM CDT.
        assertEquals("picked 7:05 AM CDT", DailyPickRead.pickedAt(1790165100.0, ct))
        // The 8:05 ET run, said in Central. CDT or CST depending on the season, always an hour earlier.
        assertTrue(DailyPickRead.etClock(8, 5, ct).startsWith("7:05 AM C"))
        assertTrue(DailyPickRead.staleNote(ct).contains("7:05 AM C"))
        assertTrue(DailyPickRead.etWindow(8, 30, 10, 0, ct).startsWith("7:30–9:00 AM C"))
    }

    @Test fun `re-check headlines compare against the morning`() {
        fun rc(status: String, sym: String?, morning: String?, conv: Int? = 68, why: String? = null) =
            com.stocktracker.app.data.remote.DailyPickRecheck(status = status, morningSymbol = morning, noneReason = why,
                pick = sym?.let { DailyPick(symbol = it, conviction = conv) })
        // Short on purpose: one line on the card, the reasons behind "Why".
        assertEquals("Still DK (68/100), was 72", DailyPickRead.recheckHeadline(rc("pick", "DK", "DK"), 72))
        assertEquals("Now prefers XOM (68/100) over DK", DailyPickRead.recheckHeadline(rc("pick", "XOM", "DK"), 72))
        assertEquals("Now picks XOM (68/100)", DailyPickRead.recheckHeadline(rc("pick", "XOM", null), null))
        assertEquals("No longer a pick", DailyPickRead.recheckHeadline(rc("none", null, "DK", why = "It fell 6% on news."), 72))
        assertEquals("Still no pick", DailyPickRead.recheckHeadline(rc("none", null, null), null))
        assertNull(DailyPickRead.recheckHeadline(rc("failed", null, "DK"), 72))
        assertNull(DailyPickRead.recheckStamp(null))
    }

    @Test fun `a failed run is not no-pick`() {
        val r = DailyPickResponse(available = true, stale = false, date = "2026-09-22", status = "failed", error = "analyst failed")
        val s = DailyPickRead.shape(true, false, r, null)
        assertTrue(s is Shape.RunFailed)
        assertEquals("TODAY'S PICK FAILED", DailyPickRead.header(s))
    }

    @Test fun `a stale pick is headed with its own date`() {
        val s = DailyPickRead.shape(true, false, pick(date = "2026-09-21", stale = true), null)
        assertEquals("PICK FROM MON SEP 21", DailyPickRead.header(s))
    }

    @Test fun `never run and not configured are their own states`() {
        assertTrue(DailyPickRead.shape(false, false, null, null) is Shape.NotConfigured)
        val never = DailyPickResponse(available = false, reason = "not yet")
        assertEquals(Shape.NeverRun("not yet"), DailyPickRead.shape(true, false, never, null))
    }

    @Test fun `an unknown status is a failure to understand, not a pick`() {
        val odd = DailyPickResponse(available = true, status = "maybe")
        assertTrue(DailyPickRead.shape(true, false, odd, null) is Shape.LoadFailed)
    }

    // ------------------------------------------------------------ decoding

    @Test fun `explicit nulls decode to null, not zero`() {
        val json = """{"available":true,"status":"pick","date":"2026-09-22","pick":{"symbol":"X",
            "levels":{"entry_low":null,"entry_high":null,"stop":null,"target":null},
            "factors":[{"key":"rsi","label":"RSI","display":"RSI 52","value":52.0,"pctile":null}]}}"""
        val r = Http.json.decodeFromString<DailyPickResponse>(json)
        assertNull(r.pick!!.levels!!.stop)
        assertNull(r.pick.factors.single().pctile)
    }

    // ------------------------------------------------------------ ladder / labels

    @Test fun `the ladder skips absent levels and needs two prices`() {
        val marks = DailyPickRead.ladder(DailyPickLevels(entryLow = null, entryHigh = 101.0, stop = null, target = null), 100.0)!!
        assertEquals(setOf(DailyPickRead.Mark.Kind.ZONE_HIGH, DailyPickRead.Mark.Kind.PRICE), marks.map { it.kind }.toSet())
        assertNull(DailyPickRead.ladder(DailyPickLevels(), 100.0))
        assertNull(DailyPickRead.ladder(null, null))
    }

    @Test fun `ladder positions are ordered by price`() {
        val m = DailyPickRead.ladder(levels, 100.0)!!.associate { it.kind to it.x }
        assertTrue(m[DailyPickRead.Mark.Kind.STOP]!! < m[DailyPickRead.Mark.Kind.ZONE_LOW]!!)
        assertTrue(m[DailyPickRead.Mark.Kind.PRICE]!! < m[DailyPickRead.Mark.Kind.TARGET]!!)
    }

    @Test fun `risk line never invents the missing half`() {
        assertEquals("risk \$5.50 a share · no target given", DailyPickRead.riskLine(5.5, null, null))
        assertNull(DailyPickRead.riskLine(null, null, null))
    }

    @Test fun `money renders absence as a dash`() {
        assertEquals("—", DailyPickRead.money(null))
    }

    @Test fun `rank words read as top or bottom and absent stays absent`() {
        assertNull(DailyPickRead.rankWords(null))
        assertEquals("top 12%", DailyPickRead.rankWords(88.2))
        assertEquals("top 1%", DailyPickRead.rankWords(100.0))
        assertEquals("bottom 30%", DailyPickRead.rankWords(30.0))
    }

    @Test fun `ordinal of an absent percentile is absent`() {
        assertNull(DailyPickRead.ordinal(null))
        assertEquals("88th", DailyPickRead.ordinal(88.2))
        assertEquals("11th", DailyPickRead.ordinal(11.0))
        assertEquals("1st", DailyPickRead.ordinal(1.0))
    }

    @Test fun `price age says unavailable rather than now`() {
        assertEquals("price unavailable", DailyPickRead.priceAge(null, 0))
        assertEquals("price 5m ago", DailyPickRead.priceAge(1000.0, 1000_000L + 5 * 60_000L))
    }

    @Test fun `comparison says when there is nothing yet and when it is thin`() {
        assertEquals("No pick has a 20-day result yet.", DailyPickRead.comparisonLine(DailyPickComparison(horizonSessions = 20), 20))
        val thin = DailyPickRead.comparisonLine(DailyPickComparison(horizonSessions = 5, nDays = 3, aiBetter = 2, ruleBetter = 1), 20)!!
        assertTrue(thin.endsWith("too few days to mean anything yet"))
    }

    // ------------------------------------------------------------ notifications

    @Test fun `no morning note from a failed, stale or other-day run`() {
        assertNull(DailyPickRead.morningNote(DailyPickResponse(available = true, date = "2026-09-22", status = "failed"), "2026-09-22"))
        assertNull(DailyPickRead.morningNote(pick(stale = true), "2026-09-22"))
        assertNull(DailyPickRead.morningNote(pick(date = "2026-09-21"), "2026-09-22"))
        assertNull(DailyPickRead.morningNote(null, "2026-09-22"))
    }

    @Test fun `morning note for a pick and for no pick`() {
        assertEquals("Today's pick: BRK-B — confidence 72", DailyPickRead.morningNote(pick(), "2026-09-22")!!.title)
        val none = DailyPickResponse(available = true, date = "2026-09-22", status = "none", noneReason = "gate shut")
        assertEquals(DailyPickRead.Note("No pick today", "gate shut"), DailyPickRead.morningNote(none, "2026-09-22"))
    }

    @Test fun `a report card without a mark says nothing`() {
        assertNull(DailyPickRead.reportCardLine(DailyPickReportCard(date = "2026-09-15", symbol = "X", horizonSessions = 5)))
        assertEquals(
            "Last week's pick X: +2.1% vs S&P +0.8%",
            DailyPickRead.reportCardLine(DailyPickReportCard("2026-09-15", "X", 5, 2.1, 0.8, 1.3)),
        )
    }

    // ------------------------------------------------------------ DP-10 alerts

    @Test fun `price states`() {
        assertEquals(PriceState.BELOW_STOP, DailyPickRead.priceState(94.0, levels))
        assertEquals(PriceState.AT_TARGET, DailyPickRead.priceState(112.0, levels))
        assertEquals(PriceState.IN_ZONE, DailyPickRead.priceState(100.0, levels))
        assertEquals(PriceState.BELOW_ZONE, DailyPickRead.priceState(96.0, levels))
        assertEquals(PriceState.ABOVE_ZONE, DailyPickRead.priceState(102.0, levels))
        assertEquals(PriceState.RAN_PAST, DailyPickRead.priceState(103.0, levels))
        assertNull(DailyPickRead.priceState(null, levels))
        assertNull(DailyPickRead.priceState(100.0, DailyPickLevels()))
    }

    @Test fun `null stop and target never fire`() {
        val noExit = levels.copy(stop = null, target = null)
        assertEquals(PriceState.BELOW_ZONE, DailyPickRead.priceState(1.0, noExit))
        assertNull(DailyPickRead.alertFor(null, DailyPickRead.priceState(1.0, noExit), emptySet()))
    }

    @Test fun `entering the zone needs a real crossing`() {
        assertNull(DailyPickRead.alertFor(null, PriceState.IN_ZONE, emptySet()))
        assertNull(DailyPickRead.alertFor(PriceState.BELOW_ZONE, PriceState.IN_ZONE, emptySet()))
        assertEquals(Alert.ENTERED_ZONE, DailyPickRead.alertFor(PriceState.ABOVE_ZONE, PriceState.IN_ZONE, emptySet()))
        assertEquals(Alert.ENTERED_ZONE, DailyPickRead.alertFor(PriceState.RAN_PAST, PriceState.IN_ZONE, emptySet()))
    }

    @Test fun `each alert fires once a day`() {
        assertEquals(Alert.RAN_PAST, DailyPickRead.alertFor(null, PriceState.RAN_PAST, emptySet()))
        assertNull(DailyPickRead.alertFor(PriceState.IN_ZONE, PriceState.RAN_PAST, setOf(Alert.RAN_PAST)))
    }

    @Test fun `stop fires even skipping straight past the zone, and ends the day`() {
        assertEquals(Alert.HIT_STOP, DailyPickRead.alertFor(PriceState.RAN_PAST, PriceState.BELOW_STOP, emptySet()))
        assertNull(DailyPickRead.alertFor(PriceState.BELOW_STOP, PriceState.AT_TARGET, setOf(Alert.HIT_STOP)))
        assertNull(DailyPickRead.alertFor(PriceState.ABOVE_ZONE, PriceState.IN_ZONE, setOf(Alert.HIT_TARGET)))
    }

    @Test fun `the log keeps today only and replaces the last state`() {
        val log = setOf("2026-09-21|X|RAN_PAST", "2026-09-22|X|last:ABOVE_ZONE", "2026-09-22|Y|last:IN_ZONE")
        val next = DailyPickRead.nextLog(log, "2026-09-22", "X", PriceState.IN_ZONE, Alert.ENTERED_ZONE)
        assertEquals(setOf("2026-09-22|X|last:IN_ZONE", "2026-09-22|X|ENTERED_ZONE", "2026-09-22|Y|last:IN_ZONE"), next)
        assertEquals(PriceState.IN_ZONE, DailyPickRead.lastState(next, "2026-09-22", "X"))
        assertEquals(setOf(Alert.ENTERED_ZONE), DailyPickRead.sentAlerts(next, "2026-09-22", "X"))
        assertTrue(DailyPickRead.sentAlerts(log, "2026-09-22", "X").isEmpty())
    }

    @Test fun `alert text names the price and when it was read`() {
        val n = DailyPickRead.alertNote(Alert.HIT_STOP, "BRK-B", 93.5, levels, "10:15 AM ET", mine = true)
        assertEquals("Your BRK-B position hit its exit price", n.title)
        assertTrue(n.body.startsWith("\$93.50 at 10:15 AM ET"))
    }

    @Test fun `every factor key the server can send has an explanation`() {
        val keys = listOf("trend", "rel_strength", "momentum", "rsi", "extension", "range_52w", "long_cycle", "volume",
            "volatility", "track_record", "insider", "quality", "short_interest", "seasonality", "macro", "earnings", "regime", "today_move")
        assertTrue(keys.all { it in DailyPickRead.explanations })
    }
}
