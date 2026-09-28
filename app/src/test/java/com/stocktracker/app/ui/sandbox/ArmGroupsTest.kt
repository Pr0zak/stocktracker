package com.stocktracker.app.ui.sandbox

import com.stocktracker.app.data.remote.SandboxArm
import com.stocktracker.app.data.remote.SandboxArmCohort
import com.stocktracker.app.data.remote.SandboxArmSeries
import com.stocktracker.app.data.remote.SandboxArmsNav
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmGroupsTest {
    private val arms = listOf(
        SandboxArm(arm = "main"), SandboxArm(arm = "rules", engine = "rules"),
        SandboxArm(arm = "etf-rules", engine = "rules", universe = "etf"),
        SandboxArm(arm = "etf", engine = "llm", universe = "etf"),
    )

    @Test fun each_arm_belongs_to_its_group() {
        assertEquals("etf", ArmGroups.groupOf("etf-rules", arms))
        assertEquals("all", ArmGroups.groupOf("rules", arms))
        assertEquals("all", ArmGroups.groupOf("gone", arms))
        assertEquals(listOf("etf-rules", "etf"), ArmGroups.armsIn("etf", arms).map { it.arm })
        assertEquals(listOf("main", "rules"), ArmGroups.armsIn("all", arms).map { it.arm })
    }

    @Test fun switching_to_etfs_opens_the_ai_arm_not_the_mechanical_one() {
        assertEquals("etf", ArmGroups.defaultArm("etf", arms))
        assertEquals("main", ArmGroups.defaultArm("all", arms))
    }

    @Test fun no_toggle_before_any_etf_arm_exists() {
        assertFalse(ArmGroups.hasEtf(arms.take(2)))
        assertTrue(ArmGroups.hasEtf(arms))
    }

    @Test fun each_group_charts_from_its_own_start() {
        val nav = SandboxArmsNav(
            dates = listOf("2026-09-01", "2026-09-28", "2026-09-29"), commonStartIndex = 0,
            cohorts = mapOf(
                "all" to SandboxArmCohort(listOf("main", "rules"), "2026-09-01", 0),
                "etf" to SandboxArmCohort(listOf("etf", "etf-rules", "main"), "2026-09-28", 1)),
        )
        assertEquals(setOf("main", "rules") to 0, ArmGroups.trend("all", nav))
        assertEquals(setOf("etf", "etf-rules", "main") to 1, ArmGroups.trend("etf", nav))
    }

    @Test fun an_older_server_without_groups_keeps_the_old_chart() {
        val nav = SandboxArmsNav(commonStartIndex = 3,
            arms = listOf(SandboxArmSeries(arm = "main"), SandboxArmSeries(arm = "etf", universe = "etf")))
        assertEquals(setOf("main") to 3, ArmGroups.trend("all", nav))
        assertNull(ArmGroups.trend("etf", nav).second)
    }

    @Test fun the_server_payload_parses() {
        val json = Json { ignoreUnknownKeys = true }
        val nav = json.decodeFromString(SandboxArmsNav.serializer(), """
            {"dates":["2026-09-28"],"common_start":"2026-08-26","common_start_index":0,
             "cohorts":{"etf":{"arms":["etf","main"],"common_start":null,"common_start_index":null}},
             "arms":[{"arm":"etf","label":"ETFs only (AI)","engine":"llm","universe":"etf",
                      "equity":[null],"benchmark_value":[null]}]}""")
        assertEquals("etf", nav.arms.single().universe)
        assertNull(nav.cohorts["etf"]!!.commonStartIndex)
    }
}
