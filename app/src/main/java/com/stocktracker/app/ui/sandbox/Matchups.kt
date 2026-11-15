package com.stocktracker.app.ui.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.SandboxArm
import java.util.Locale
import kotlin.math.abs

/** An AI account and the no-AI account that trades the same universe by fixed rules. */
internal data class Twin(val title: String, val ai: SandboxArm, val rules: SandboxArm)

/**
 * SBX-4 (2026-11-15) — the Sandbox's accounts are experiments, and this answers what each one tests:
 *
 *  - **AI vs rules**: every rules-engine account against its AI twin (`main`↔`rules`,
 *    `x`↔`x-rules`).
 *  - **The reviewer**: `reviewed` (trades a second opinion kept) against the `rejects` book (the
 *    trades it cut, bought anyway on paper).
 *  - **Tweaks**: the other AI accounts on the full universe, each one setting changed from the
 *    Baseline, as points over or under the Baseline.
 *
 * Pure, so the pairing rules are tested without a screen. Every figure is points against the
 * account's own S&P shadow (see [ArmGroups.vsShown]); an account that has not run gives no figure,
 * and a pair with one missing gives no gap.
 */
internal object Matchups {
    const val BASELINE = "main"

    /** The AI account a rules account is the twin of: `rules` → `main`, `x-rules` → `x`. */
    fun aiTwinName(rulesArm: String): String? = when {
        rulesArm == "rules" -> BASELINE
        rulesArm.endsWith("-rules") -> rulesArm.removeSuffix("-rules")
        else -> null
    }

    fun title(a: SandboxArm): String = when {
        a.universe == ArmGroups.OPTIONS -> "Calls + puts"
        a.universe == ArmGroups.ETF -> if (a.singleShare) "One ETF a day" else "ETFs"
        a.singleShare -> "One share a day"
        else -> "Stocks"
    }

    /** Twins in chip order (stocks, ETFs, options), each pair once. */
    fun twins(arms: List<SandboxArm>): List<Twin> {
        val byName = arms.associateBy { it.arm }
        val groupRank = ArmGroups.ORDER.map { it.first }
        return arms.filter { it.engine == "rules" }
            .mapNotNull { r -> aiTwinName(r.arm)?.let(byName::get)?.let { ai -> Twin(title(ai), ai, r) } }
            .sortedWith(compareBy<Twin> { groupRank.indexOf(ArmGroups.groupOfUniverse(it.ai.universe)) }
                .thenBy { it.ai.singleShare })
    }

    /** (kept trades, cut trades), or null when either account is missing. */
    fun reviewer(arms: List<SandboxArm>): Pair<SandboxArm, SandboxArm>? {
        val cut = arms.firstOrNull { it.engine == "rejects" } ?: return null
        val kept = arms.firstOrNull { it.arm == "reviewed" } ?: return null
        return kept to cut
    }

    /** AI accounts on the full universe that change one thing from the Baseline. */
    fun tweaks(arms: List<SandboxArm>): List<SandboxArm> =
        arms.filter {
            it.engine == "llm" && ArmGroups.groupOfUniverse(it.universe) == ArmGroups.ALL &&
                !it.singleShare && it.arm != BASELINE
        }

    /** a − b in points, or null when either has no figure. */
    fun gap(a: SandboxArm, b: SandboxArm): Double? {
        val x = ArmGroups.vsShown(a) ?: return null
        val y = ArmGroups.vsShown(b) ?: return null
        return x - y
    }

    /** A tweak's figure relative to the Baseline's, in points. */
    fun overBaseline(tweak: SandboxArm, arms: List<SandboxArm>): Double? =
        arms.firstOrNull { it.arm == BASELINE }?.let { gap(tweak, it) }
}

@Composable
internal fun MatchupsCard(
    arms: List<SandboxArm>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val twins = Matchups.twins(arms)
    val reviewer = Matchups.reviewer(arms)
    val tweaks = Matchups.tweaks(arms)
    // One scale for the whole card, so a point is the same length in every section.
    val scale = maxOf(
        LeaderboardMath.scale(arms),
        tweaks.mapNotNull { Matchups.overBaseline(it, arms)?.let(::abs) }.maxOrNull() ?: 1.0,
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (twins.isNotEmpty()) {
            SectionHead("Does the AI beat plain rules?", "pts vs S&P")
            twins.forEachIndexed { i, t ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp), color = muted.copy(alpha = 0.2f))
                PairHead(t.title, Matchups.gap(t.ai, t.rules))
                VsRow("AI", ArmGroups.vsShown(t.ai), scale, t.ai.arm == selected, "Open ${t.ai.label}") { onSelect(t.ai.arm) }
                VsRow("Rules", ArmGroups.vsShown(t.rules), scale, t.rules.arm == selected, "Open ${t.rules.label}") { onSelect(t.rules.arm) }
            }
        }
        reviewer?.let { (kept, cut) ->
            SectionHead("Does the reviewer help?", "pts vs S&P", top = twins.isNotEmpty())
            PairHead("Kept vs cut", Matchups.gap(kept, cut))
            VsRow("Trades it kept", ArmGroups.vsShown(kept), scale, kept.arm == selected, "Open ${kept.label}") { onSelect(kept.arm) }
            VsRow("Trades it cut", ArmGroups.vsShown(cut), scale, cut.arm == selected, "Open ${cut.label}") { onSelect(cut.arm) }
        }
        if (tweaks.isNotEmpty()) {
            SectionHead("Which tweak helps?", "pts vs Baseline", top = true)
            tweaks.sortedByDescending { Matchups.overBaseline(it, arms) ?: Double.NEGATIVE_INFINITY }.forEach { t ->
                VsRow(LeaderboardMath.shortLabel(t), Matchups.overBaseline(t, arms), scale, t.arm == selected, "Open ${t.label}") { onSelect(t.arm) }
            }
        }
        Text("Tap a row to open that account.", style = MaterialTheme.typography.labelSmall, color = muted)
    }
}

@Composable
private fun SectionHead(title: String, unit: String, top: Boolean = false) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(top = if (top) 10.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
            letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.5f, color = muted,
            modifier = Modifier.weight(1f),
        )
        Text(unit, style = MaterialTheme.typography.labelSmall, color = muted)
    }
}

/** The pair's name and its gap. No winner is named: most gaps here are fractions of a point. */
@Composable
private fun PairHead(title: String, gap: Double?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        com.stocktracker.app.ui.components.Pill(
            gap?.let { "gap " + String.format(Locale.US, "%.2f", abs(it)) + " pts" } ?: "not run yet",
            muted,
        )
    }
}
