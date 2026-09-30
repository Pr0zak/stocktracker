package com.stocktracker.app.ui.sandbox

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.SandboxToday
import com.stocktracker.app.data.remote.SandboxTodayArm
import com.stocktracker.app.data.remote.SandboxTodayOrder
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.theme.EtfAccent
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.Indigo
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.Signal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * TODAY-1 — every arm's latest run at the top of the Sandbox tab.
 *
 * The tab shows one arm at a time, so "what did the sandbox do today" took nine arm switches. This
 * card answers it for all of them: which accounts bought or sold what, what was skipped and why, and
 * which held. It folds to one line ("5 bought · 1 skipped · 7 held"), remembered across launches.
 *
 * Absent is not zero: an arm whose last run was on an earlier day is listed as "didn't run", never
 * folded into "held", and a failed load says so instead of showing no trades.
 */
@Composable
internal fun TodayCard(
    today: SandboxToday?,
    failed: Boolean,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onOpenArm: (String) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (today == null) {
        if (failed) {
            GlowCard(tint = null) {
                Text("Couldn't load today's trades.", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        }
        return
    }
    val date = today.date
    if (date == null) {
        GlowCard(tint = null) {
            Text("No sandbox runs yet. Trades appear here after the first one.",
                 style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        return
    }
    val summary = TodaySummary.of(today)
    val tint = when {
        summary.traded.isEmpty() -> null
        today.totals.sells > 0 && today.totals.buys == 0 -> LossRed
        else -> GainGreen
    }
    GlowCard(tint = tint, modifier = Modifier.animateContentSize(), spacing = 12.dp) {
        // The header row is the whole card when folded, and the fold control either way.
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (collapsed) "Show trades" else "Fold to one line") { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                // Folded, the clock is dropped so the counts fit on one line.
                TodaySummary.heading(date, if (collapsed) null else today.ranAt),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.5f,
                color = muted,
            )
            if (collapsed) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (today.totals.buys > 0) Stat(GainGreen, today.totals.buys, "bought")
                    if (today.totals.sells > 0) Stat(LossRed, today.totals.sells, "sold")
                    if (today.totals.skipped > 0) Stat(Signal, today.totals.skipped, "skipped")
                    if (summary.held.isNotEmpty()) Stat(muted, summary.held.size, "held")
                    if (summary.didNotRun.isNotEmpty()) Stat(Signal, summary.didNotRun.size, "didn't run")
                }
            } else {
                Spacer(Modifier.weight(1f))
                if (summary.traded.isNotEmpty()) {
                    Pill(TodaySummary.totalsLine(today), if (tint == LossRed) LossRed else GainGreen)
                }
            }
            Icon(
                if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = null,
                tint = muted,
            )
        }
        if (collapsed) return@GlowCard

        if (failed) {
            Text("Couldn't refresh — these are the last trades loaded.",
                 style = MaterialTheme.typography.labelSmall, color = Signal)
        }
        if (summary.traded.isEmpty()) {
            Text("No trades in any account.", style = MaterialTheme.typography.bodyMedium)
        }
        summary.traded.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ArmBlock(a, onOpenArm)
        }
        if (summary.held.isNotEmpty()) {
            if (summary.traded.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                "Held: " + summary.held.joinToString(" · ") { TodaySummary.shortName(it.label) },
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
        }
        if (summary.didNotRun.isNotEmpty()) {
            Text(
                "Didn't run: " + summary.didNotRun.joinToString(" · ") {
                    TodaySummary.shortName(it.label) + if (!it.enabled) " (off)" else ""
                },
                style = MaterialTheme.typography.bodySmall, color = Signal,
            )
        }
    }
}

@Composable
private fun Stat(color: Color, n: Int, word: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(
            "$n $word",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ArmBlock(a: SandboxTodayArm, onOpenArm: (String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.clickable(onClickLabel = "Open this account") { onOpenArm(a.arm) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(if (a.universe == "etf") EtfAccent else Indigo, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(a.label.ifBlank { a.arm }, style = MaterialTheme.typography.titleSmall)
        }
        a.filled.forEach { o -> OrderRow(o) }
        a.skipped.forEach { o ->
            Row {
                Text(o.symbol, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Signal)
                Text(
                    " skipped · " + (o.reason ?: "skipped"),
                    style = MaterialTheme.typography.bodyMedium, color = muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OrderRow(o: SandboxTodayOrder) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val sell = o.side == "sell"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (sell) Text("▼ ", style = MaterialTheme.typography.bodyMedium, color = LossRed)
        Text(o.symbol, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
             color = if (sell) LossRed else MaterialTheme.colorScheme.onSurface)
        o.shares?.let {
            Text(" × " + TodaySummary.shares(it), style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        if (sell) Text(" sold", style = MaterialTheme.typography.bodyMedium, color = muted)
        Spacer(Modifier.weight(1f))
        Text(
            o.gross?.let { TodaySummary.money(it) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Pure pieces of the card, tested without a screen. */
internal object TodaySummary {
    data class Split(
        val traded: List<SandboxTodayArm>,
        val held: List<SandboxTodayArm>,
        val didNotRun: List<SandboxTodayArm>,
    )

    fun of(t: SandboxToday): Split = Split(
        // An arm that only skipped still "did something" worth reading, so it is listed with the traders.
        traded = t.arms.filter { it.ran && (it.filled.isNotEmpty() || it.skipped.isNotEmpty()) },
        held = t.arms.filter { it.ran && it.filled.isEmpty() && it.skipped.isEmpty() },
        didNotRun = t.arms.filter { !it.ran },
    )

    /** "Regime gate on (blocks buys when shut)" → "Regime gate on". The full name is one tap away. */
    fun shortName(label: String): String = label.substringBefore(" (").trim().ifBlank { label }

    private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /**
     * "TODAY · 2:37 PM", or "SEP 29 · 2:37 PM" once the run is not today's. The clock is in the
     * phone's zone (the user is in Central, never hard-code ET); the date is the ET trading date the
     * server stamped, compared to today in ET.
     */
    fun heading(
        date: String,
        ranAt: Double?,
        zone: ZoneId = ZoneId.systemDefault(),
        todayEt: LocalDate = LocalDate.now(ZoneId.of("America/New_York")),
    ): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull()
        val day = if (d == todayEt) "TODAY" else d?.format(MONTH_DAY)?.uppercase(Locale.US) ?: date
        val clock = ranAt?.let { Instant.ofEpochMilli((it * 1000).toLong()).atZone(zone).format(CLOCK) }
        return if (clock != null) "$day · $clock" else day
    }

    /** "5 buys · $4,756", "1 sell · $640", "2 buys · 1 sell · $5,396 traded". */
    fun totalsLine(t: SandboxToday): String {
        val b = t.totals.buys
        val s = t.totals.sells
        val parts = buildList {
            if (b > 0) add("$b buy" + if (b == 1) "" else "s")
            if (s > 0) add("$s sell" + if (s == 1) "" else "s")
        }
        if (parts.isEmpty()) return "${t.totals.skipped} skipped"
        val dollars = when {
            s == 0 -> money(t.totals.bought, cents = false)
            b == 0 -> money(t.totals.sold, cents = false)
            else -> money(t.totals.bought + t.totals.sold, cents = false) + " traded"
        }
        return parts.joinToString(" · ") + " · " + dollars
    }

    fun money(v: Double, cents: Boolean = true): String =
        "$" + String.format(Locale.US, if (cents) "%,.2f" else "%,.0f", v)

    fun shares(v: Double): String =
        if (v == Math.floor(v)) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.3f", v).trimEnd('0')
}
