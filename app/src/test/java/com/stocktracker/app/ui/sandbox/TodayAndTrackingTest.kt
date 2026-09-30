package com.stocktracker.app.ui.sandbox

import com.stocktracker.app.data.remote.SandboxArmSeries
import com.stocktracker.app.data.remote.SandboxArmsNav
import com.stocktracker.app.data.remote.SandboxToday
import com.stocktracker.app.data.remote.SandboxTodayArm
import com.stocktracker.app.data.remote.SandboxTodayOrder
import com.stocktracker.app.data.remote.SandboxTodayTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TodayAndTrackingTest {
    private val chicago = ZoneId.of("America/Chicago")
    // 2026-09-30 19:37 UTC = 2:37 PM Central
    private val ranAt = 1_790_797_020.0

    @Test fun `today's run says TODAY with the clock in the phone's zone`() {
        assertEquals("TODAY · 2:37 PM",
            TodaySummary.heading("2026-09-30", ranAt, chicago, LocalDate.parse("2026-09-30")))
    }

    @Test fun `an older run names its date, and a missing clock is left off rather than guessed`() {
        assertEquals("SEP 29", TodaySummary.heading("2026-09-29", null, chicago, LocalDate.parse("2026-09-30")))
    }

    @Test fun `ran-and-held and did-not-run stay separate, and skip-only arms are listed with the traders`() {
        val t = SandboxToday(date = "2026-09-30", arms = listOf(
            SandboxTodayArm(arm = "main", ran = true),
            SandboxTodayArm(arm = "etf", ran = true, filled = listOf(SandboxTodayOrder("VTI", gross = 100.0))),
            SandboxTodayArm(arm = "picky", ran = true, skipped = listOf(SandboxTodayOrder("GLD", reason = "not enough cash"))),
            SandboxTodayArm(arm = "old", ran = false),
        ))
        val s = TodaySummary.of(t)
        assertEquals(listOf("etf", "picky"), s.traded.map { it.arm })
        assertEquals(listOf("main"), s.held.map { it.arm })
        assertEquals(listOf("old"), s.didNotRun.map { it.arm })
    }

    @Test fun `the totals pill counts buys and sells and their dollars`() {
        fun t(b: Int, s: Int, bought: Double, sold: Double) =
            SandboxToday(date = "d", totals = SandboxTodayTotals(buys = b, sells = s, bought = bought, sold = sold))
        assertEquals("5 buys · $4,756", TodaySummary.totalsLine(t(5, 0, 4756.05, 0.0)))
        assertEquals("1 sell · $640", TodaySummary.totalsLine(t(0, 1, 0.0, 640.0)))
        assertEquals("1 buy · 2 sells · $1,500 traded", TodaySummary.totalsLine(t(1, 2, 500.0, 1000.0)))
    }

    @Test fun `long account names shorten to the part before the parenthesis`() {
        assertEquals("Regime gate on", TodaySummary.shortName("Regime gate on (blocks buys when shut)"))
        assertEquals("+ Second opinion", TodaySummary.shortName("+ Second opinion"))
    }

    @Test fun `every arm is measured against its own shadow, so a late starter lines up at zero`() {
        val nav = SandboxArmsNav(
            dates = listOf("2026-09-26", "2026-09-28", "2026-09-29"),
            arms = listOf(
                SandboxArmSeries(arm = "main", equity = listOf(100.0, 110.0, 99.0), benchmarkValue = listOf(100.0, 100.0, 100.0)),
                SandboxArmSeries(arm = "etf", universe = "etf", equity = listOf(null, 50.0, 51.0), benchmarkValue = listOf(null, 50.0, 50.0)),
            ),
        )
        val rows = ArmGroups.vsShadow(nav).associate { it.first.arm to it.second }
        assertEquals(listOf(0.0, 10.0, -1.0), rows["main"]!!.map { Math.round(it!! * 1e6) / 1e6 })
        assertNull("no value before the arm existed", rows["etf"]!![0])
        assertEquals(0.0, rows["etf"]!![1]!!, 1e-9)
        assertEquals(2.0, rows["etf"]!![2]!!, 1e-9)
        assertEquals("main", ArmGroups.axisArm(ArmGroups.vsShadow(nav)))
    }
}
