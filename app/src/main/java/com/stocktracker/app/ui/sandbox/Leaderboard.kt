package com.stocktracker.app.ui.sandbox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.SandboxArm
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import java.util.Locale
import kotlin.math.abs

/**
 * SBX-3 (2026-09-30) — "Who's beating the S&P", the Sandbox tab's lead.
 *
 * The sandbox exists to compare strategies, and the comparison sat below the fold as a list of
 * numbers. Every account is now one bar, drawn left or right of a centre line that IS the S&P: its
 * lead or lag over the same money in the index, the one figure comparable across accounts that were
 * funded on different days. All bars share one scale. Tapping a bar opens that account; the chart of
 * how they got here folds under "Over time".
 */
@Composable
internal fun Leaderboard(
    arms: List<SandboxArm>,
    selected: String,
    onSelect: (String) -> Unit,
    overTime: (@Composable () -> Unit)?,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ranked = LeaderboardMath.ranked(arms)
    val scale = LeaderboardMath.scale(ranked)
    var showOverTime by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "WHO'S BEATING THE S&P", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.5f, color = muted,
                modifier = Modifier.weight(1f),
            )
            Text("tap to open", style = MaterialTheme.typography.labelSmall, color = muted)
        }
        ranked.forEach { a ->
            val v = a.vsBenchmarkPct
            val isSel = a.arm == selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(onClickLabel = "Open ${a.label}") { onSelect(a.arm) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    LeaderboardMath.shortLabel(a) + if (isSel) " ●" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val line = muted
                Canvas(Modifier.width(120.dp).height(12.dp)) {
                    val mid = size.width / 2f
                    drawLine(line, Offset(mid, -3f), Offset(mid, size.height + 3f), strokeWidth = 1.dp.toPx())
                    if (v != null) {
                        val w = (abs(v) / scale).toFloat().coerceIn(0f, 1f) * mid
                        val left = if (v >= 0) mid else mid - w
                        drawRoundRect(
                            if (v >= 0) GainGreen else LossRed,
                            topLeft = Offset(left, size.height * 0.2f),
                            size = Size(w.coerceAtLeast(2f), size.height * 0.6f),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                    }
                }
                Text(
                    v?.let { (if (it >= 0) "+" else "−") + String.format(Locale.US, "%.2f", abs(it)) } ?: "—",
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                    color = when { v == null -> muted; v >= 0 -> GainGreen; else -> LossRed },
                    textAlign = TextAlign.End, modifier = Modifier.width(56.dp),
                )
            }
        }
        Text(
            "Points ahead of or behind the same money in the S&P. The centre line is the S&P.",
            style = MaterialTheme.typography.labelSmall, color = muted,
        )
        if (overTime != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { showOverTime = !showOverTime },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Over time", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                     modifier = Modifier.weight(1f))
                Icon(if (showOverTime) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = muted)
            }
            if (showOverTime) overTime()
        }
    }
}

/** Pure ordering, scale and naming for the leaderboard. */
internal object LeaderboardMath {
    /** Best first; an account with no figure yet sinks to the bottom rather than ranking as zero. */
    fun ranked(arms: List<SandboxArm>): List<SandboxArm> =
        arms.sortedWith(compareByDescending<SandboxArm> { it.vsBenchmarkPct != null }.thenByDescending { it.vsBenchmarkPct ?: 0.0 })

    /** One scale for every bar: the largest lead or lag, but never under 1 point so a quiet week is not blown up. */
    fun scale(arms: List<SandboxArm>): Double =
        maxOf(1.0, arms.mapNotNull { it.vsBenchmarkPct?.let(::abs) }.maxOrNull() ?: 1.0)

    /**
     * "Regime gate on (blocks buys when shut)" → "Regime gate on"; the full name is in the title bar.
     * Only a LONG parenthetical is an explanation. A short one is the name: "ETFs only (AI)" and
     * "ETFs only (no AI)" are different accounts, and cutting both to "ETFs only" made two identical rows.
     */
    fun shortLabel(a: SandboxArm): String {
        val full = a.label.ifBlank { a.arm }
        val paren = Regex("""\s*\(([^)]*)\)\s*$""").find(full) ?: return full
        return if (paren.groupValues[1].length > 8) full.substring(0, paren.range.first).trim().ifBlank { full } else full
    }
}
