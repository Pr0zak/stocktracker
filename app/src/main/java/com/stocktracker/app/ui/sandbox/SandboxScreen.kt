package com.stocktracker.app.ui.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.remote.SandboxState
import com.stocktracker.app.data.remote.SandboxStrategyNote
import com.stocktracker.app.data.remote.SandboxTrade
import com.stocktracker.app.ui.components.AllocationDonut
import com.stocktracker.app.ui.components.DONUT_COLORS
import com.stocktracker.app.ui.components.ChartLineOverlay
import com.stocktracker.app.ui.components.ChartMarker
import com.stocktracker.app.ui.components.PriceChart
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.util.Formatting
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal val GREEN = GainGreen
internal val RED = LossRed
internal val AMBER = Color(0xFFB0872B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxScreen(onOpenSettings: () -> Unit = {}) {
    val vm: SandboxViewModel = sandboxViewModel()
    val ui by vm.state.collectAsState()
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val backendOffline by com.stocktracker.app.data.remote.SignalsHealth.state.collectAsState()
        .let { st -> androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { st.value.isOffline } } }
    // Re-pull whenever this screen is shown: the server-side timer may have traded while the app sat
    // open, and returning from settings should reflect anything changed there.
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sandbox")
                        Text("AI paper trader", style = MaterialTheme.typography.labelSmall, color = neutral)
                    }
                },
                actions = {
                    if (ui.ticking) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 12.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Sandbox settings")
                    }
                },
            )
        },
    ) { padding ->
        val st = ui.state
        // Hoisted: an empty lazy item still consumes its 14dp of spacedBy (see backendOffline docs).
        val backendOffline = com.stocktracker.app.ui.components.backendOffline()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (backendOffline) {
                item { com.stocktracker.app.ui.components.BackendStatusBanner() }
            }
            if (!ui.configured) {
                item { InfoCard("Set the Signals service URL in Settings to use the AI sandbox.") }
                return@LazyColumn
            }
            // Only surface a per-screen error when it ISN'T plain unreachability — the banner above
            // already says that, and repeating it just burns space.
            if (ui.error != null && st == null && !backendOffline) {
                item { InfoCard(ui.error!!) }
            }
            if (st == null || st.fundedTotal <= 0.0) {
                item { Spacer(Modifier.height(8.dp)) }
                item { EmptyState(onFund = { vm.fund(it) }) }
                if (st != null) item { SettingsSummary(st, onOpen = onOpenSettings) }
                return@LazyColumn
            }

            item { Spacer(Modifier.height(4.dp)) }
            item { HeaderMetrics(st, trendPctPerMonth = ui.trendPctPerMonth) }
            st.settings.goalAmount?.takeIf { it > 0 }?.let { goal ->
                item { GoalCard(equity = st.equity, goal = goal, goalDate = st.settings.goalDate) }
            }
            item {
                val up = ui.nav.size >= 2 && ui.nav.last().price >= ui.nav.first().price
                val overlays = buildList {
                    if (ui.benchmarkValues.any { it != null }) {
                        add(ChartLineOverlay("S&P 500", Color(0xFFEC4899), ui.benchmarkValues))
                    }
                    // The trajectory through the day-to-day noise — "how it's doing over time".
                    if (ui.trendValues.any { it != null }) {
                        add(ChartLineOverlay("Trend", AMBER, ui.trendValues))
                    }
                }
                // Out/under-performance vs the S&P, as its own pane with a zero line: above 0 = ahead.
                val panes = if (ui.vsBenchmarkSeries.any { it != null }) {
                    listOf(
                        com.stocktracker.app.ui.components.ChartSubPane(
                            label = "vs S&P (pts)",
                            lines = listOf(ChartLineOverlay("vs S&P", GREEN, ui.vsBenchmarkSeries)),
                            guides = listOf(0.0),
                        ),
                    )
                } else {
                    emptyList()
                }
                val markers = ui.trades
                    .filter { it.status == "filled" && (it.side == "buy" || it.side == "sell") }
                    .take(40)
                    .map { ChartMarker((it.ts * 1000).toLong(), if (it.side == "buy") GREEN else RED, it.symbol) }
                if (ui.nav.size >= 2) {
                    PriceChart(
                        points = ui.nav, up = up, showHighLow = true, showAxis = true,
                        overlays = overlays, markers = markers, subPanes = panes,
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        valueFormatter = { "$" + Formatting.compact(it) },
                        timeFormatter = {
                            com.stocktracker.app.util.formatChartTimestamp(it, com.stocktracker.app.data.model.ChartRange.ALL)
                        },
                    )
                } else {
                    InfoCard("The equity curve appears after the first daily tick.")
                }
            }
            item { AutoTradeRow(st, onToggle = { vm.setEnabled(it) }, onRunNow = { vm.runTick() }, ticking = ui.ticking) }
            // Directly under the equity curve and the auto-trade switch: right where someone deciding
            // whether to trust this thing is already looking.
            ui.memory?.let { mem -> item { ScorecardCard(mem) } }
            st.strategyNote?.let { item { StrategyCard(it) } }
            if (st.positions.isNotEmpty()) {
                item { SectionLabel("Holdings") }
                // Allocation donut + legend, same as the Portfolio tab — one colour per position,
                // echoed on the rows below so a slice maps to a name at a glance. Cash is included as
                // its own slice since an idle-cash sandbox is a meaningful state.
                item {
                    val sorted = st.positions.sortedByDescending { it.value }
                    val colorOf = sorted.mapIndexed { i, p -> p.symbol to DONUT_COLORS[i % DONUT_COLORS.size] }.toMap()
                    val cashColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    if (st.equity > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AllocationDonut(
                                slices = sorted.map {
                                    (colorOf[it.symbol] ?: DONUT_COLORS[0]) to (it.value / st.equity).toFloat()
                                } + (cashColor to (st.cash / st.equity).toFloat().coerceAtLeast(0f)),
                                modifier = Modifier.size(96.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                sorted.take(5).forEach { p ->
                                    LegendRow(
                                        colorOf[p.symbol] ?: DONUT_COLORS[0],
                                        p.symbol.removeSuffix("-USD"),
                                        p.value / st.equity * 100,
                                    )
                                }
                                if (sorted.size > 5) {
                                    Text("+${sorted.size - 5} more", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (st.cash > 0) LegendRow(cashColor, "Cash", st.cash / st.equity * 100)
                            }
                        }
                    }
                }
                items(st.positions) { p -> PositionRow(p, st.equity) }
            }
            item { SectionLabel("Trade log") }
            if (ui.trades.isEmpty()) {
                item { Text("No trades yet.", style = MaterialTheme.typography.bodySmall, color = neutral) }
            } else {
                items(ui.trades.take(60)) { t -> TradeRow(t) }
            }
            item { SettingsSummary(st, onOpen = onOpenSettings) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // transient message → simple inline banner via a LaunchedEffect that clears after a moment
    ui.message?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2600)
            vm.clearMessage()
        }
    }
}

@Composable
private fun HeaderMetrics(st: SandboxState, trendPctPerMonth: Double? = null) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$" + Formatting.compact(st.equity), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Pill("PAPER", neutral)
        }
        val ret = st.totalReturnPct
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (ret != null) Text(signedPct(ret) + " total", color = if (ret >= 0) GREEN else RED,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            st.vsBenchmarkPct?.let {
                Text("vs S&P " + signedPct(it), color = if (it >= 0) GREEN else RED, style = MaterialTheme.typography.titleSmall)
            }
        }
        trendPctPerMonth?.let { rate ->
            Text(
                "Trending " + signedPct(rate) + " / month",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (rate >= 0) GREEN else RED,
            )
        }
        Text(
            "Cash " + (st.cashPct?.let { "${it.toInt()}%" } ?: "—") +
                " · realized " + signedUsd(st.realizedPlTotal) +
                (st.lastTickDate?.let { " · last traded $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall, color = neutral,
        )
    }
}

@Composable
private fun AutoTradeRow(st: SandboxState, onToggle: (Boolean) -> Unit, onRunNow: () -> Unit, ticking: Boolean) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("Auto-trade", fontWeight = FontWeight.SemiBold)
                Text(if (st.enabled) "On — trades each weekday at the close" else "Paused",
                    style = MaterialTheme.typography.labelSmall, color = neutral)
            }
            Switch(checked = st.enabled, onCheckedChange = onToggle)
        }
        OutlinedButton(onClick = onRunNow, enabled = !ticking, modifier = Modifier.fillMaxWidth()) {
            Text(if (ticking) "Running…" else "Run a decision cycle now")
        }
    }
}

@Composable
private fun StrategyCard(n: SandboxStrategyNote) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val color = when (n.stance.lowercase()) {
        "constructive" -> GREEN; "defensive" -> RED; else -> AMBER
    }
    // COLLAPSED by default — the Opus note is a full paragraph plus theme lists, which reads as a wall
    // of text inline. The header carries the actionable summary (stance + cash target); tap for the rest.
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Strategy", style = MaterialTheme.typography.labelLarge, color = neutral)
                Pill(n.stance.ifBlank { "—" }, color)
                Text("${n.cashTargetPct.toInt()}% cash", style = MaterialTheme.typography.labelMedium,
                    color = neutral, maxLines = 1)
            }
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "Collapse strategy" else "Expand strategy",
                tint = neutral,
            )
        }
        if (!open) {
            // One scannable line: what it's leaning into (the most useful at-a-glance bit).
            val gist = n.themes.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: n.notes.takeIf { it.isNotBlank() }?.substringBefore(". ")?.plus(".")
            gist?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = neutral,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            // Target mix — the plan's actual numbers, as proportion bars (this is the most concrete
            // part of the note and was previously not surfaced at all).
            if (n.targets.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                StrategyHeading("Target mix")
                val maxPct = (n.targets.maxOfOrNull { it.targetPct } ?: 1.0).coerceAtLeast(1.0)
                // Label ABOVE the bar — the analyst's group names are descriptive ("US Large-Cap Equity
                // (SPY/VOO)"), which a fixed side column would truncate to uselessness.
                n.targets.sortedByDescending { it.targetPct }.take(6).forEach { t ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                t.exposureGroup, style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Text("${t.targetPct.toInt()}%", style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold, color = neutral)
                        }
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = { (t.targetPct / maxPct).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            // Lean in / Avoid as real bullet lists, one idea per line.
            if (n.themes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                StrategyHeading("Lean in")
                n.themes.forEach { BulletLine("+", it, GREEN) }
            }
            if (n.avoid.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                StrategyHeading("Avoid")
                n.avoid.forEach { BulletLine("−", it, RED) }
            }
            // The full reasoning paragraph last, as supporting detail.
            if (n.notes.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                StrategyHeading("Why")
                Text(n.notes, style = MaterialTheme.typography.bodySmall, color = neutral)
            }
        }
    }
}

@Composable
private fun StrategyHeading(text: String) = Text(
    text.uppercase(),
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.primary,
)

/** One idea per line with a colored marker — far more scannable than a "·"-joined run-on. */
@Composable
private fun BulletLine(marker: String, text: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(marker, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PositionRow(p: com.stocktracker.app.data.remote.SandboxPosition, equity: Double) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val weight = if (equity > 0) p.value / equity * 100 else 0.0
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.symbol.removeSuffix("-USD"), fontWeight = FontWeight.SemiBold)
            Text("${trimNum(p.shares)} sh · ${weight.toInt()}%", style = MaterialTheme.typography.labelSmall, color = neutral)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$" + Formatting.compact(p.value), fontWeight = FontWeight.Medium)
            p.unrealizedPct?.let {
                Text(signedPct(it), style = MaterialTheme.typography.labelSmall, color = if (it >= 0) GREEN else RED)
            }
        }
    }
}

@Composable
private fun TradeRow(t: SandboxTrade) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val skipped = t.status == "skipped"
    val color = when {
        skipped -> neutral
        t.side == "buy" -> GREEN
        t.side == "sell" -> RED
        else -> MaterialTheme.colorScheme.primary   // deposit/withdraw
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        // A blocked order gets its own amber BLOCKED pill — a greyed "BUY" read like a real buy at a
        // glance, which hid the fact that a rule stopped it.
        Pill(if (skipped) "BLOCKED" else t.side.uppercase(), if (skipped) AMBER else color)
        Column(Modifier.weight(1f)) {
            val head = when {
                t.symbol == "CASH" -> "${t.date} · " + signedUsd(t.gross ?: 0.0)
                skipped -> "${t.date} · ${t.side.uppercase()} ${t.symbol.removeSuffix("-USD")} not placed"
                else -> "${t.date} · ${t.symbol.removeSuffix("-USD")} · ${trimNum(t.shares)} sh @ $${Formatting.compact(t.price ?: 0.0)}"
            }
            Text(head, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val zone = t.entryHigh?.let { " · wanted ≤$" + Formatting.compact(it) } ?: ""
            val sub = if (skipped) ("Rule: " + (t.skipReason ?: "blocked") + zone) else t.reason
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.labelSmall,
                    color = if (skipped) AMBER else neutral)
            }
            // Keep the AI's original intent visible so you can see WHAT the rule stopped.
            if (skipped && t.reason.isNotBlank()) {
                Text("Wanted: ${t.reason}", style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
        if (!skipped && t.realizedPl != null && t.realizedPl != 0.0) {
            Text(signedUsd(t.realizedPl), style = MaterialTheme.typography.labelMedium,
                color = if (t.realizedPl >= 0) GREEN else RED)
        }
    }
}

/** Goal progress — the instant "how am I tracking?" visual: a big % + a progress bar + the gap left. */
@Composable
private fun GoalCard(equity: Double, goal: Double, goalDate: String?) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val frac = (equity / goal).coerceIn(0.0, 1.0)
    val done = frac >= 1.0
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Goal", style = MaterialTheme.typography.labelLarge, color = neutral)
            Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = if (done) GREEN else MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(
            progress = { frac.toFloat() },
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)),
            color = if (done) GREEN else MaterialTheme.colorScheme.primary,
        )
        val gap = goal - equity
        Text(
            if (done) "Goal reached — $" + Formatting.compact(equity) + " of $" + Formatting.compact(goal)
            else "$" + Formatting.compact(equity) + " of $" + Formatting.compact(goal) +
                " · $" + Formatting.compact(gap) + " to go" + (goalDate?.let { " by $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall, color = neutral,
        )
    }
}

/** A compact, tappable summary of the key settings — the entry point to the full settings page. */
@Composable
private fun SettingsSummary(st: SandboxState, onOpen: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val s = st.settings
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable { onOpen() }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Settings & goals", style = MaterialTheme.typography.labelLarge, color = neutral)
            Icon(Icons.Filled.Settings, contentDescription = null, tint = neutral, modifier = Modifier.size(18.dp))
        }
        Text(
            listOfNotNull(
                s.riskTolerance.replaceFirstChar { it.uppercase() },
                "max ${s.maxPositionPct.toInt()}%/name",
                "${s.cashFloorPct.toInt()}% cash floor",
                if (s.maxTurnoverPct > 0) "${s.maxTurnoverPct.toInt()}% turnover" else null,
                s.cadence,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            listOfNotNull(
                if (s.monthlyDeposit > 0) "+$${s.monthlyDeposit.toInt()}/mo" else null,
                if (s.exclusions.isNotEmpty()) "excludes ${s.exclusions.joinToString(",")}" else null,
                if (!s.allowCrypto) "no crypto" else null,
                if (!s.allowEtf) "no ETFs" else null,
                s.exitDate?.let { "exit $it" },
            ).joinToString(" · ").ifBlank { "Tap to configure funds, goals, risk and automation" },
            style = MaterialTheme.typography.labelSmall, color = neutral,
        )
    }
}


// ---- small reusable bits (kept local to avoid promoting private helpers elsewhere) ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(label: String, iso: String?, modifier: Modifier = Modifier, onPicked: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        Text(iso ?: label, maxLines = 1)
    }
    if (open) {
        val stateDp = rememberDatePickerState(
            initialSelectedDateMillis = iso?.let {
                runCatching { java.time.LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    val d = stateDp.selectedDateMillis?.let {
                        DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(it))
                    }
                    onPicked(d); open = false
                }) { Text("Set") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onPicked(null); open = false }) { Text("Clear") }
                    TextButton(onClick = { open = false }) { Text("Cancel") }
                }
            },
        ) { DatePicker(state = stateDp) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One donut-legend line: colour swatch, name, and share of the book. */
@Composable
private fun LegendRow(color: Color, label: String, pct: Double) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        Text("${pct.toInt()}%", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp))

@Composable
private fun HelperText(text: String) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Pill(text: String, color: Color) = Box(
    Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 2.dp),
) { Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color) }

/**
 * The AI's own scorecard: did its decisions actually beat just owning the index?
 *
 * Shown on purpose even when it is unflattering — the whole value of grading past calls is lost if the
 * UI only surfaces the number when it flatters. Beat rate is the headline because raw win rate is
 * misleading (equities drift up, so ~55-60% of any 20-day window is positive regardless of skill).
 * Absent entirely until enough decisions have been graded, which takes ~20 trading days after a call.
 */
@Composable
private fun ScorecardCard(mem: com.stocktracker.app.data.remote.MemoryStats) {
    val cards = listOfNotNull(
        mem.sandboxBuys?.let { "What it bought" to it },
        mem.buyCalls?.let { "What it called" to it },
    )
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Is it any good?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (cards.isEmpty()) {
            Text(
                "Not enough graded decisions yet. Each one is scored 20 trading days after it is made, " +
                    "then compared with simply owning the S&P.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            cards.forEach { (label, sc) ->
                val beat = sc.beatRate20d
                val excess = sc.avgExcess20dPct
                // Grey, not green/red, until the sample can support a claim either way.
                val thin = sc.n < 20
                val color = when {
                    thin || beat == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    beat >= 0.55 -> GREEN
                    beat < 0.45 -> RED
                    else -> AMBER
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "${sc.n} graded" + if (thin) " · too few to judge" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            beat?.let { String.format(java.util.Locale.US, "%.0f%%", it * 100) } ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = color,
                        )
                        Text(
                            excess?.let { "beat index · " + signedPct(it) + " avg" } ?: "beat index",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "Measured over 20 trading days against the S&P. Above 50% means it added value; below " +
                    "means it would have been better to just buy the index.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoCard(text: String) = Box(
    Modifier.fillMaxWidth().padding(top = 24.dp)
        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(16.dp),
) { Text(text, style = MaterialTheme.typography.bodyMedium) }

@Composable
private fun EmptyState(onFund: (Double) -> Unit) {
    var cash by remember { mutableStateOf("10000") }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Fund the sandbox to begin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("The AI will invest this fictional cash on its own each trading day. Paper only — it never touches your real portfolio.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = cash, onValueChange = { cash = it }, prefix = { Text("$") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            Button(onClick = { cash.replace(",", "").trim().toDoubleOrNull()?.let(onFund) }) { Text("Fund") }
        }
    }
}

private fun signedPct(v: Double) = (if (v >= 0) "+" else "") + String.format(java.util.Locale.US, "%.1f%%", v)
private fun signedUsd(v: Double) = (if (v >= 0) "+$" else "-$") + Formatting.compact(kotlin.math.abs(v))
private fun trimNum(v: Double) = if (v == kotlin.math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.US, "%.4f", v)
