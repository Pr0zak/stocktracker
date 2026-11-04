package com.stocktracker.app.ui.portfolio

import com.stocktracker.app.notify.CorePlanNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CorePlanTest {

    private val plan = CorePlan(slots = CorePlans.US_INTL_80_20, depositAmount = 500.0, depositDay = 1)

    @Test
    fun `copies of a fund count toward the same group, and the rest sits outside the core`() {
        val s = CorePlanMath.evaluate(plan, listOf("VOO" to 6000.0, "SPYM" to 2000.0, "VXUS" to 2000.0, "MSFT" to 5000.0))
        assertEquals(10000.0, s.coreValue, 1e-9)
        assertEquals(80.0, s.slots[0].pct!!, 1e-9)
        assertEquals(listOf("VOO", "SPYM"), s.slots[0].held)
        assertEquals("VOO", s.slots[0].buySymbol)
        assertEquals(listOf("MSFT"), s.outsideSymbols)
        assertEquals(10000.0 / 15000.0 * 100.0, s.corePctOfBook!!, 1e-9)
        assertTrue(s.onTarget)
    }

    @Test
    fun `a symbol in two groups counts toward the first only`() {
        val p = CorePlan(slots = listOf(CoreSlot("A", listOf("VOO"), 50.0), CoreSlot("B", listOf("VOO", "VXUS"), 50.0)))
        val s = CorePlanMath.evaluate(p, listOf("VOO" to 100.0))
        assertEquals(100.0, s.slots[0].value, 1e-9)
        assertEquals(0.0, s.slots[1].value, 1e-9)
    }

    @Test
    fun `off target beyond the band, and the drift is signed`() {
        val s = CorePlanMath.evaluate(plan, listOf("VOO" to 9000.0, "VXUS" to 1000.0))
        assertFalse(s.onTarget)
        assertEquals("International", s.worst!!.slot.name)
        assertEquals(-10.0, s.slots[1].driftPts!!, 1e-9)
    }

    @Test
    fun `an empty core has no shares and no worst group, never zero percent`() {
        val s = CorePlanMath.evaluate(plan, listOf("MSFT" to 1000.0))
        assertNull(s.slots[0].pct)
        assertNull(s.worst)
        assertFalse(s.onTarget)
        assertEquals("VOO", s.slots[0].buySymbol)
    }

    @Test
    fun `a deposit too small to close the gap all goes to the short group`() {
        val s = CorePlanMath.evaluate(plan, listOf("VOO" to 9000.0, "VXUS" to 1000.0))
        val split = CorePlanMath.split(s, 500.0)
        assertEquals(1, split.size)
        assertEquals("VXUS", split[0].symbol)
        assertEquals(500.0, split[0].dollars, 1e-9)
    }

    @Test
    fun `a deposit splits by each group's shortfall after the deposit`() {
        // Core 9,800 + 1,000 = 10,800: US wants 8,640 (short 640), international 2,160 (short 360).
        val split = CorePlanMath.split(CorePlanMath.evaluate(plan, listOf("VOO" to 8000.0, "VXUS" to 1800.0)), 1000.0)
        assertEquals(640.0, split.first { it.symbol == "VOO" }.dollars, 1e-9)
        assertEquals(360.0, split.first { it.symbol == "VXUS" }.dollars, 1e-9)
    }

    @Test
    fun `nothing goes to a group still over target after the deposit`() {
        val split = CorePlanMath.split(CorePlanMath.evaluate(plan, listOf("VOO" to 9500.0, "VXUS" to 500.0)), 1000.0)
        assertEquals(listOf("VXUS"), split.map { it.symbol })
        assertEquals(1000.0, split[0].dollars, 1e-9)
    }

    @Test
    fun `an on-target core splits the deposit by target and the parts add up`() {
        val s = CorePlanMath.evaluate(plan, listOf("VOO" to 8000.0, "VXUS" to 2000.0))
        val split = CorePlanMath.split(s, 333.0)
        assertEquals(333.0, split.sumOf { it.dollars }, 1e-9)
        assertEquals(266.0, split.first { it.symbol == "VOO" }.dollars, 1.0)
    }

    @Test
    fun `targets must add to 100`() {
        assertTrue(CorePlanMath.targetsValid(plan))
        assertFalse(CorePlanMath.targetsValid(plan.copy(slots = listOf(CoreSlot("A", listOf("VOO"), 90.0)))))
        assertFalse(CorePlanMath.targetsValid(plan.copy(slots = listOf(CoreSlot("A", emptyList(), 100.0)))))
    }

    @Test
    fun `next deposit date rolls to next month once the day has passed`() {
        val p = plan.copy(depositDay = 15)
        assertEquals(LocalDate.of(2026, 11, 15), CorePlanMath.nextDepositDate(p, LocalDate.of(2026, 11, 4)))
        assertEquals(LocalDate.of(2026, 11, 15), CorePlanMath.nextDepositDate(p, LocalDate.of(2026, 11, 15)))
        assertEquals(LocalDate.of(2027, 1, 15), CorePlanMath.nextDepositDate(p, LocalDate.of(2026, 12, 16)))
    }

    @Test
    fun `projection and the monthly amount needed agree`() {
        val months = 120
        val need = CorePlanMath.monthlyNeeded(10000.0, 100000.0, months, 6.0)!!
        assertEquals(100000.0, CorePlanMath.project(10000.0, need, months, 6.0), 0.01)
        assertEquals(0.0, CorePlanMath.monthlyNeeded(200000.0, 100000.0, months, 6.0)!!, 0.0)
        // No growth: deposits simply add up.
        assertEquals(1000.0 + 12 * 100.0, CorePlanMath.project(1000.0, 100.0, 12, 0.0), 1e-9)
    }

    @Test
    fun `reminder is due once a month, on or after the buy day`() {
        val p = plan.copy(depositDay = 5)
        assertNull(CorePlanNotifier.dueMonth(p, LocalDate.of(2026, 11, 4), ""))
        assertEquals("2026-11", CorePlanNotifier.dueMonth(p, LocalDate.of(2026, 11, 5), ""))
        assertNull(CorePlanNotifier.dueMonth(p, LocalDate.of(2026, 11, 20), "2026-11"))
        assertNull(CorePlanNotifier.dueMonth(p.copy(remind = false), LocalDate.of(2026, 11, 5), ""))
        assertNull(CorePlanNotifier.dueMonth(null, LocalDate.of(2026, 11, 5), ""))
    }
}
