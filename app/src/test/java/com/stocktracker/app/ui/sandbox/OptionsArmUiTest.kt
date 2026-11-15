package com.stocktracker.app.ui.sandbox

import com.stocktracker.app.data.remote.SandboxArm
import com.stocktracker.app.data.remote.SandboxState
import com.stocktracker.app.data.remote.SandboxTodayOrder
import com.stocktracker.app.data.remote.SandboxTrade
import com.stocktracker.app.data.remote.SandboxTradesResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The long-options arms (signals 0.49.0): their own group, contracts never read as shares. */
class OptionsArmUiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val arms = listOf(
        SandboxArm(arm = "main"),
        SandboxArm(arm = "etf", universe = "etf"),
        SandboxArm(arm = "options-rules", engine = "rules", universe = "options"),
        SandboxArm(arm = "options", engine = "llm", universe = "options"),
    )

    @Test fun options_arms_are_their_own_group_not_stocks() {
        assertEquals("options", ArmGroups.groupOf("options-rules", arms))
        assertEquals(listOf("main"), ArmGroups.armsIn("all", arms).map { it.arm })
        assertEquals(listOf("options-rules", "options"), ArmGroups.armsIn("options", arms).map { it.arm })
        assertEquals("options", ArmGroups.defaultArm("options", arms))
    }

    @Test fun an_unknown_universe_still_files_under_the_originals() {
        assertEquals("all", ArmGroups.groupOfUniverse("futures"))
    }

    @Test fun state_decodes_contracts_and_keeps_a_missing_mark_absent() {
        val st = json.decodeFromString<SandboxState>(
            """{"arm":"options","universe":"options","cash":8735.0,"equity":9990.0,"positions":[],
               "contracts":[{"contract_symbol":"AAPL261218C00360000","display":"AAPL ${'$'}360 call exp 2026-12-18",
                 "underlying":"AAPL","right":"call","strike":360,"expiry":"2026-12-18","count":1,
                 "premium_paid":12.65,"cost_total":1265.0,"mark":null,"mark_stale":true,"mark_date":"2026-11-16",
                 "value":null,"pl_pct":null,"pl_usd":null,"dte":32.4}],
               "premium_at_risk_usd":1265.0,
               "options_scorecard":{"closed":0,"premium_deployed_usd":0,"pl_usd":0,"index_alt_usd":null,
                 "index_alt_n":0,"direction_right":0,"direction_right_but_lost":0,"open":1,"premium_at_risk_usd":1265.0}}""")
        assertTrue(st.positions.isEmpty())
        val c = st.contracts.single()
        assertEquals("AAPL", c.underlying)
        assertNull(c.value)
        assertTrue(c.markStale)
        assertEquals(1265.0, st.premiumAtRiskUsd!!, 0.0)
        assertNull(st.optionsScorecard!!.indexAltUsd)
    }

    @Test fun an_older_server_without_options_fields_still_decodes() {
        val st = json.decodeFromString<SandboxState>("""{"arm":"main","cash":1.0}""")
        assertEquals("all", st.universe)
        assertTrue(st.contracts.isEmpty())
        assertNull(st.optionsScorecard)
    }

    @Test fun option_sides_fold_onto_buy_and_sell() {
        val rows = json.decodeFromString<SandboxTradesResponse>(
            """{"trades":[{"side":"buy_to_open","instrument":"option"},{"side":"sell_to_close","instrument":"option"},
               {"side":"expired","instrument":"option"},{"side":"settled","instrument":"option"},
               {"side":"deposit"},{"side":"buy"}]}""").trades
        assertEquals(listOf("buy", "sell", "sell", "sell", null, "buy"), rows.map { tradeDirection(it) })
        assertTrue(rows.first().isOption)
        assertTrue(!rows.last().isOption)
    }

    @Test fun tapping_an_option_order_opens_its_stock() {
        assertEquals("SOFI", TodaySummary.tapSymbol(
            SandboxTodayOrder(symbol = "SOFI \$16 put exp 2027-01-15", instrument = "option")))
        assertEquals("BRK.B", TodaySummary.tapSymbol(SandboxTodayOrder(symbol = "BRK.B")))
        assertEquals("SPY", TodaySummary.tapSymbol(SandboxTodayOrder(symbol = "SPY put", instrument = "option")))
    }
}
