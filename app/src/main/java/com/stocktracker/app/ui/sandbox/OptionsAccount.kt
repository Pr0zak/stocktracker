package com.stocktracker.app.ui.sandbox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.SandboxContract
import com.stocktracker.app.data.remote.SandboxSettings
import com.stocktracker.app.data.remote.SandboxState
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.util.Formatting
import java.util.Locale
import kotlin.math.abs

/** The options arm's fixed exits: sell at or under [dte] days left, at [stopPct] under the premium
 *  paid, or at [targetPct] over it. */
internal data class OptionExits(val dte: Int, val stopPct: Double, val targetPct: Double)

/**
 * SBX-5 (2026-11-15) — what a contract needs to show that a share does not: how close it is to each
 * of the arm's three exits, and what the stock has to do for it to pay. Pure, so the arithmetic is
 * tested without a screen.
 *
 * A stale mark (no live quote) is the last quote or the price paid, so nothing here measures off
 * it: no gauge position, no exit distance. Absent stays absent.
 */
internal object ContractMath {
    fun exits(s: SandboxSettings): OptionExits =
        OptionExits(s.optExitDte ?: 21, s.optStopPct ?: -50.0, s.optTargetPct ?: 100.0)

    private fun livePl(c: SandboxContract): Double? = c.plPct?.takeUnless { c.markStale }

    /** Where P/L sits between the stop (0) and the target (1), clamped. */
    fun gaugeFraction(c: SandboxContract, e: OptionExits): Double? =
        livePl(c)?.let { ((it - e.stopPct) / (e.targetPct - e.stopPct)).coerceIn(0.0, 1.0) }

    /** Where cost (0% P/L) sits on the same rail. */
    fun costFraction(e: OptionExits): Double = (0.0 - e.stopPct) / (e.targetPct - e.stopPct)

    /** Points of P/L left before the stop sells it; null on a stale mark. */
    fun pointsToStop(c: SandboxContract, e: OptionExits): Double? = livePl(c)?.let { it - e.stopPct }

    fun pointsToTarget(c: SandboxContract, e: OptionExits): Double? = livePl(c)?.let { e.targetPct - it }

    /** Whole days until the days-left exit sells it (0 = due on the next run). */
    fun daysToExit(c: SandboxContract, e: OptionExits): Int? =
        c.dte?.let { maxOf(0, kotlin.math.floor(it - e.dte).toInt()) }

    /** The underlying price at expiry where the contract returns its premium: strike + premium for a
     *  call, strike − premium for a put. [SandboxContract.premiumPaid] is per share (the ask paid). */
    fun breakEven(c: SandboxContract): Double? {
        val k = c.strike ?: return null
        val p = c.premiumPaid ?: return null
        return if (c.right == "put") k - p else k + p
    }

    /** "Dec 18" from "2026-12-18"; the raw string when it does not parse. */
    fun shortDate(iso: String?): String? = iso?.let {
        runCatching {
            java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.US))
        }.getOrDefault(it)
    }

    /** "This week: $1,410 of $1,487 spent · more from Nov 23", or "This week: $1,500 left to spend". */
    fun weekLine(w: com.stocktracker.app.data.remote.SandboxWeeklyBudget?): String? {
        w ?: return null
        val money = { v: Double -> String.format(Locale.US, "$%,.0f", v) }
        val head = if (w.spentUsd <= 0.0) "This week: ${money(w.roomUsd)} left to spend"
        else if (w.roomUsd <= 0.0) "This week: budget used (${money(w.spentUsd)} of ${money(w.budgetUsd)})"
        else "This week: ${money(w.spentUsd)} of ${money(w.budgetUsd)} spent"
        return head + (shortDate(w.freesOn)?.takeIf { w.spentUsd > 0.0 }?.let { " · more from $it" } ?: "")
    }

    /** "needs AAPL above $289.20 by Dec 18". */
    fun needsLine(c: SandboxContract): String? {
        val be = breakEven(c) ?: return null
        val side = if (c.right == "put") "below" else "above"
        return "needs ${c.underlying} $side ${Formatting.price(be)}" + (shortDate(c.expiry)?.let { " by $it" } ?: "")
    }

    /** How far the stock is from break-even, signed in the direction it has to move; ≤ 0 means it is
     *  already past. Null without a spot price. */
    fun movePctToBreakEven(c: SandboxContract): Double? {
        val be = breakEven(c) ?: return null
        val spot = c.spot?.takeIf { it > 0 } ?: return null
        val pct = (be / spot - 1) * 100
        return if (c.right == "put") -pct else pct
    }

    fun nowLine(c: SandboxContract): String? {
        val spot = c.spot ?: return null
        val move = movePctToBreakEven(c) ?: return null
        val now = "now ${Formatting.price(spot)}"
        if (move <= 0) return "$now · past break-even"
        val dir = if (c.right == "put") "−" else "+"
        return "$now · $dir" + String.format(Locale.US, "%.1f%%", move) + " to go"
    }
}

/** The options account's lead card: balance, premium in play, cash, and the S&P comparison only
 *  once the account has run. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun OptionsHeader(st: SandboxState) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val ran = st.lastTickDate != null
    val gap = st.vsBenchmarkPct.takeIf { ran }
    GlowCard(tint = com.stocktracker.app.ui.components.directionTint(gap), spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(String.format(Locale.US, "$%,.2f", st.equity), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Pill("PAPER", neutral)
        }
        val atRisk = st.premiumAtRiskUsd ?: 0.0
        val share = if (st.equity > 0) (atRisk / st.equity).coerceIn(0.0, 1.0) else 0.0
        Text("Premium in play", style = MaterialTheme.typography.labelSmall, color = neutral)
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(neutral.copy(alpha = 0.2f))) {
            if (share > 0) Box(Modifier.fillMaxWidth(share.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val n = st.contracts.size
            Pill(
                if (n == 0) "No contracts held"
                else "${Formatting.price(atRisk)} in $n contract${if (n == 1) "" else "s"} · " + String.format(Locale.US, "%.0f%%", share * 100),
                MaterialTheme.colorScheme.primary,
            )
            Pill("Cash ${Formatting.price(st.cash)}", neutral)
            if (!ran) {
                Pill("Not run yet", neutral)
            } else gap?.let {
                Pill(String.format(Locale.US, "%s %.1f pts %s the S&P", if (it >= 0) "▲" else "▼", abs(it), if (it >= 0) "ahead of" else "behind"),
                    if (it >= 0) GREEN else RED)
            }
        }
        ContractMath.weekLine(st.weeklyBudget)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = neutral) }
        st.lastTickDate?.let { Text("Last ran $it", style = MaterialTheme.typography.labelSmall, color = neutral) }
    }
}

/** One held contract: value, P/L, where it sits between the stop and the target, the days until
 *  the days-left exit, and the move the stock needs to break even. */
@Composable
internal fun ContractCard(c: SandboxContract, exits: OptionExits, onClick: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = "Open ${c.underlying}") { onClick() }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(c.right.uppercase(), if (c.right == "put") RED else GREEN)
            Spacer(Modifier.width(8.dp))
            Text("${c.count} × ${c.display}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.value?.let { (if (c.markStale) "≈" else "") + Formatting.price(it) } ?: "—",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                color = if (c.markStale) AMBER else Color.Unspecified)
        }
        val pl = c.plPct?.takeUnless { c.markStale }
        val parts = listOfNotNull(
            pl?.let { (if (it >= 0) "+" else "−") + String.format(Locale.US, "%.1f%%", abs(it)) },
            c.costTotal?.let { "paid ${Formatting.price(it)}" },
            if (c.markStale) "no live quote" + (c.markDate?.let { " since $it" } ?: "") else null,
        )
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
            color = when { c.markStale -> AMBER; (pl ?: 0.0) > 0 -> GREEN; (pl ?: 0.0) < 0 -> RED; else -> neutral })

        // Near an exit: say so in words, so it does not rest on reading the dot.
        val toStop = ContractMath.pointsToStop(c, exits)
        val toTarget = ContractMath.pointsToTarget(c, exits)
        when {
            toStop != null && toStop < 15 ->
                Pill(String.format(Locale.US, "%.0f pts from the %.0f%% sell", toStop, exits.stopPct), AMBER)
            toTarget != null && toTarget < 25 ->
                Pill(String.format(Locale.US, "%.0f pts from the +%.0f%% sell", toTarget, exits.targetPct), GREEN)
        }

        ExitGauge(ContractMath.gaugeFraction(c, exits), ContractMath.costFraction(exits), (pl ?: 0.0) >= 0)
        Row(Modifier.fillMaxWidth()) {
            Text(String.format(Locale.US, "%.0f%% sell", exits.stopPct), style = MaterialTheme.typography.labelSmall, color = neutral, modifier = Modifier.weight(1f))
            Text("cost", style = MaterialTheme.typography.labelSmall, color = neutral, modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, "+%.0f%% sell", exits.targetPct), style = MaterialTheme.typography.labelSmall, color = neutral)
        }

        c.dte?.let { dte ->
            val toExit = ContractMath.daysToExit(c, exits)
            DaysBar(heldDays = c.heldDays, dte = dte, exitDte = exits.dte)
            Text(
                "${dte.toInt()} days left" + (toExit?.let { if (it == 0) " · days-left sell is due" else " · sold in $it days at ${exits.dte} left" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (toExit != null && toExit <= 7) AMBER else neutral,
            )
        }
        ContractMath.needsLine(c)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        ContractMath.nowLine(c)?.let { line ->
            val past = (ContractMath.movePctToBreakEven(c) ?: 1.0) <= 0
            Text(line, style = MaterialTheme.typography.labelSmall, color = if (past) GREEN else neutral)
        }
    }
}

/** A rail from the stop (left, red) through cost to the target (right, green), and a dot for where
 *  the contract is now. No dot when the mark is stale. */
@Composable
private fun ExitGauge(fraction: Double?, cost: Double, up: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ground = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val y = size.height / 2
        val h = 3.dp.toPx()
        val cx = (size.width * cost).toFloat()
        drawRoundRect(RED.copy(alpha = 0.55f), Offset(0f, y - h / 2), Size(cx, h), CornerRadius(h))
        drawRoundRect(GREEN.copy(alpha = 0.55f), Offset(cx, y - h / 2), Size(size.width - cx, h), CornerRadius(h))
        drawLine(muted, Offset(cx, 1f), Offset(cx, size.height - 1f), strokeWidth = 1.dp.toPx())
        fraction?.let {
            val x = (size.width * it).toFloat().coerceIn(6.dp.toPx(), size.width - 6.dp.toPx())
            drawCircle(ground, radius = 7.dp.toPx(), center = Offset(x, y))
            drawCircle(if (up) GREEN else RED, radius = 5.dp.toPx(), center = Offset(x, y))
        }
    }
}

/** Time held so far against the contract's life, with a tick where the days-left exit sells it. */
@Composable
private fun DaysBar(heldDays: Int?, dte: Double, exitDte: Int) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val held = heldDays?.toDouble() ?: return
    val total = held + dte
    if (total <= 0) return
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val h = 4.dp.toPx()
        val y = size.height / 2 - h / 2
        drawRoundRect(muted.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, h), CornerRadius(h))
        drawRoundRect(primary, Offset(0f, y), Size((size.width * held / total).toFloat().coerceAtLeast(2f), h), CornerRadius(h))
        val ex = (size.width * ((total - exitDte) / total)).toFloat().coerceIn(0f, size.width)
        drawLine(AMBER, Offset(ex, 0f), Offset(ex, size.height), strokeWidth = 1.5.dp.toPx())
    }
}
