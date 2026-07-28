package com.stocktracker.app.data

import com.stocktracker.app.data.remote.EntryPlan
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A price level the analyst could not justify must decode as ABSENT, not as zero.
 *
 * These fields were non-nullable `Double = 0.0`, and `Http.json` sets `coerceInputValues = true`, so
 * an explicit `null` AND an omitted key both became 0.0 — which the entry-plan and Ideas cards then
 * printed as "Stop $0 · target $0". That reads as a real instruction on a screen people act on with
 * money. The analyst legitimately returns null when it has no defensible level (the backend prompt
 * says so explicitly: "Use null for any level you cannot justify… never invent a precise level").
 */
class EntryPlanNullsTest {

    @Test
    fun `an explicit null level stays null instead of becoming zero`() {
        val json = """
            {"symbol":"AAPL","action":"buy_on_pullback","conviction":62,
             "entry_low":null,"entry_high":null,"stop":null,"target":null,
             "suggested_shares":null,"allocation_usd":null}
        """.trimIndent()
        val plan = Http.json.decodeFromString<EntryPlan>(json)
        assertNull("a null stop became a real-looking \$0", plan.stop)
        assertNull(plan.target)
        assertNull(plan.entryLow)
        assertNull(plan.entryHigh)
        assertNull(plan.suggestedShares)
        assertNull(plan.allocationUsd)
    }

    @Test
    fun `an omitted level is also absent`() {
        val plan = Http.json.decodeFromString<EntryPlan>("""{"symbol":"AAPL","action":"wait"}""")
        assertNull(plan.stop)
        assertNull(plan.target)
    }

    @Test
    fun `real levels still decode`() {
        val json = """
            {"symbol":"AAPL","action":"buy_now","conviction":71,
             "entry_low":180.5,"entry_high":186.0,"stop":172.0,"target":210.0,
             "suggested_shares":12.0,"allocation_usd":2200.0}
        """.trimIndent()
        val plan = Http.json.decodeFromString<EntryPlan>(json)
        assertNotNull(plan.stop)
        assertTrue(plan.stop!! == 172.0)
        assertTrue(plan.entryLow!! == 180.5 && plan.entryHigh!! == 186.0)
        assertTrue(plan.suggestedShares!! == 12.0)
    }

    @Test
    fun `a zero level is treated as absent by the renderers' guard`() {
        // Belt and braces: even if a 0.0 arrives, the render sites gate on `> 0.0`, so this pins the
        // condition they rely on rather than the formatting itself.
        val plan = Http.json.decodeFromString<EntryPlan>(
            """{"symbol":"AAPL","action":"wait","stop":0.0,"target":0.0}""",
        )
        assertTrue((plan.stop ?: 0.0) <= 0.0)
        assertTrue((plan.target ?: 0.0) <= 0.0)
    }
}
