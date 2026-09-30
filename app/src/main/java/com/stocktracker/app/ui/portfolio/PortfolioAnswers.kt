package com.stocktracker.app.ui.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.NumberSmall
import com.stocktracker.app.ui.theme.PriceMedium
import com.stocktracker.app.util.Formatting
import java.util.Locale
import kotlin.math.abs

/**
 * ABOUT-3 — the Portfolio tab's "What stands out" answers, kept pure so they are tested without a
 * screen: the one position that dominates, the day's biggest move, and the best since bought.
 */
internal object PortfolioAnswers {
    enum class Kind { WEIGHT, TODAY, SINCE }

    /** [tone] +1 good, -1 bad, 0 neutral (a concentration is worth noticing, not good or bad). */
    data class Standout(val kind: Kind, val title: String, val symbol: String, val value: String, val tone: Int)

    fun standouts(holdings: List<Holding>, total: Double, todayKnown: Boolean): List<Standout> = buildList {
        if (holdings.size >= 2 && total > 0) {
            val top = holdings.maxBy { it.value }
            add(Standout(Kind.WEIGHT, "Most in one place", top.asset.symbol,
                String.format(Locale.US, "%.0f%%", top.value / total * 100.0), 0))
        }
        // A "today" answer only when every quote is today's; an old cached move is not today's move.
        if (todayKnown) {
            val moves = holdings.mapNotNull { h -> dayPct(h)?.let { h to it } }
            val worst = moves.minByOrNull { it.second }
            val best = moves.maxByOrNull { it.second }
            when {
                worst != null && worst.second < 0 -> add(Standout(Kind.TODAY, "Biggest drop today", worst.first.asset.symbol, signedPct(worst.second, 2), -1))
                best != null && best.second > 0 -> add(Standout(Kind.TODAY, "Best today", best.first.asset.symbol, signedPct(best.second, 2), 1))
            }
        }
        holdings.filter { it.gainPercent != null }.maxByOrNull { it.gainPercent!! }?.let { h ->
            if (holdings.count { it.gainPercent != null } >= 2) {
                val g = h.gainPercent!!
                add(Standout(Kind.SINCE, "Best since bought", h.asset.symbol, signedPct(g, 1), if (g >= 0) 1 else -1))
            }
        }
    }

    /** Today's move as a percent of yesterday's value, or null when there is no base to measure from. */
    fun dayPct(h: Holding): Double? {
        val prev = h.value - h.dayChange
        return if (prev > 0) h.dayChange / prev * 100.0 else null
    }

    fun signedPct(v: Double, decimals: Int): String =
        (if (v >= 0) "+" else "−") + String.format(Locale.US, "%.${decimals}f%%", abs(v))
}

/**
 * One holding as an answer row: a coloured symbol square (the donut's colour), the symbol and what it
 * is, a weight bar scaled to the largest position, and on the right its value and today's move.
 */
@Composable
internal fun HoldingAnswerRow(
    h: Holding,
    weightPct: Double,
    maxValue: Double,
    kind: String?,
    color: Color,
    hideZeroCents: Boolean,
    onClick: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(h.asset.symbol.take(4), color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Text(h.asset.symbol, style = MaterialTheme.typography.titleSmall)
            if (kind != null) {
                Text(kind, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${Formatting.shares(h.shares)} sh · ${String.format(Locale.US, "%.1f", weightPct)}%",
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
            val frac = if (maxValue > 0) (h.value / maxValue).toFloat().coerceIn(0f, 1f) else 0f
            Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.9f).height(4.dp).clip(RoundedCornerShape(50)).background(muted.copy(alpha = 0.14f))) {
                Box(Modifier.fillMaxWidth(frac).height(4.dp).background(color))
            }
            if (h.coveredCallEligible) {
                Text("Income eligible · covered call", style = MaterialTheme.typography.labelSmall,
                     fontWeight = FontWeight.Bold, color = GainGreen, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(Formatting.price(h.value, hideZeroCents = hideZeroCents), style = PriceMedium)
            PortfolioAnswers.dayPct(h)?.let { d ->
                Pill((if (d >= 0) "▲ " else "▼ ") + String.format(Locale.US, "%.2f%%", abs(d)), if (d >= 0) GainGreen else LossRed)
            }
            h.gainPercent?.let { gp ->
                Text("${if (gp >= 0) "▲" else "▼"} ${String.format(Locale.US, "%.1f", abs(gp))}% total",
                     style = NumberSmall, color = if (gp >= 0) GainGreen else LossRed)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = muted)
    }
}
