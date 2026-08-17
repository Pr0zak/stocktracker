package com.stocktracker.app.ui.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.remote.SandboxPosition
import com.stocktracker.app.data.remote.SandboxState
import com.stocktracker.app.data.remote.SandboxStrategyNote
import com.stocktracker.app.data.remote.SandboxTrade
import com.stocktracker.app.ui.components.AllocationDonut
import com.stocktracker.app.ui.components.ChartLineOverlay
import com.stocktracker.app.ui.components.ChartMarker
import com.stocktracker.app.ui.components.DONUT_COLORS
import com.stocktracker.app.ui.components.PriceChart
import com.stocktracker.app.ui.theme.BenchmarkGrey
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

    // Every action set `message` ("Ran - 2 trade(s) executed", "Tick failed - couldn't reach the
    // service", "Sandbox reset") and NOTHING rendered it: the only reference was a LaunchedEffect
    // that cleared it 2.6s later. So funding, resetting and running a tick all completed in total
    // silence, including when they failed. A snackbar is the right surface - it doesn't shift layout.
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let { snackbarHost.showSnackbar(it) }
    }

    // Tapping a ticker anywhere on this screen — a holding, a donut legend entry, or a trade — opens
    // that name's history: what the AI paid, what it holds, and every fill behind it.
    var detailSymbol by rememberSaveable { mutableStateOf<String?>(null) }

    // The trade log collapses to one line per entry; this holds the keys of the rows the user opened.
    // Keyed by trade identity rather than list position so a refresh that prepends new fills doesn't
    // slide the open state onto a different row.
    val expandedTrades = rememberSaveable(
        saver = listSaver<MutableList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
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
            // A null state means we could not LOAD the account, which is not the same as the account
            // being empty. Showing first-run onboarding here told a funded user their sandbox did not
            // exist and offered a prefilled $10,000 Fund button — one tap from double-funding a live
            // account over a transient outage.
            if (st == null) {
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    InfoCard(
                        "Can't load the sandbox right now, so its balance and holdings aren't shown. " +
                            "Nothing has changed — this is a connection problem, not an empty account."
                    )
                }
                return@LazyColumn
            }
            if (st.fundedTotal <= 0.0) {
                item { Spacer(Modifier.height(8.dp)) }
                item { EmptyState(onFund = { vm.fund(it) }) }
                item { SettingsSummary(st, onOpen = onOpenSettings) }
                return@LazyColumn
            }

            item { Spacer(Modifier.height(4.dp)) }
            // Which book you are looking at, before any number on the screen. Only shown when there
            // is actually a choice — one arm needs no switcher.
            if (ui.arms.size > 1) {
                item { ArmSwitcher(arms = ui.arms, selected = ui.arm, onSelect = { vm.selectArm(it) }) }
            }
            item { HeaderMetrics(st, trendPctPerMonth = ui.trendPctPerMonth) }
            st.settings.goalAmount?.takeIf { it > 0 }?.let { goal ->
                item { GoalCard(equity = st.equity, goal = goal, goalDate = st.settings.goalDate) }
            }
            item {
                val up = ui.nav.size >= 2 && ui.nav.last().price >= ui.nav.first().price
                val overlays = buildList {
                    if (ui.benchmarkValues.any { it != null }) {
                        add(ChartLineOverlay("S&P 500", BenchmarkGrey, ui.benchmarkValues, dashed = true))
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
                        shadeDrawdown = true,
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
            // The comparison itself, directly under the curve: every arm's excess over its OWN S&P
            // shadow. Raw equity across arms is not comparable — they can be funded with different
            // amounts on different days — so the shadow-relative number is the one that lines up.
            if (ui.arms.size > 1) {
                item { ArmComparison(arms = ui.arms, selected = ui.arm, onSelect = { vm.selectArm(it) }) }
                ui.armsNav?.let { n -> item { ArmTrendCard(nav = n, arms = ui.arms, selected = ui.arm) } }
            }
            // The auto-trade switch and settings write to whichever arm the ENDPOINTS default to,
            // which is main. Offering them while another arm is on screen would let a tap labelled
            // "Mechanical" change the real account, so a side arm is read-only here and says so.
            if (ui.arm == "main") {
                item { AutoTradeRow(st, onToggle = { vm.setEnabled(it) }, onRunNow = { vm.runTick() }, ticking = ui.ticking) }
            } else {
                item { SideArmNotice(st) }
            }
            // Directly under the equity curve and the auto-trade switch: right where someone deciding
            // whether to trust this thing is already looking.
            ui.memory?.let { mem -> item { ScorecardCard(mem) } }
            // The world the trader is reasoning against, right above its strategy — a defensive
            // stance or a skipped energy name only makes sense next to the backdrop that caused it.
            item { com.stocktracker.app.ui.components.MacroCard(ui.macro) }
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
                                        onClick = { detailSymbol = p.symbol },
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
                item { HelperText("Tap a holding to see what it paid and every trade behind it.") }
                items(st.positions) { p ->
                    PositionRow(p, st.equity, onClick = { detailSymbol = p.symbol })
                }
            }
            item { SectionLabel("Trade log") }
            if (ui.trades.isEmpty()) {
                item { Text("No trades yet.", style = MaterialTheme.typography.bodySmall, color = neutral) }
            } else {
                item { HelperText("Tap an entry for the full reasoning and numbers.") }
                items(ui.trades.take(60)) { t ->
                    val key = tradeKey(t)
                    TradeRow(
                        t = t,
                        expanded = expandedTrades.contains(key),
                        onToggle = {
                            if (!expandedTrades.remove(key)) expandedTrades.add(key)
                        },
                        onOpenSymbol = { detailSymbol = t.symbol },
                    )
                }
            }
            // Settings edits main; on a side arm this would be a control that changes a different
            // book than the one on screen.
            if (ui.arm == "main") item { SettingsSummary(st, onOpen = onOpenSettings) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    detailSymbol?.let { sym ->
        TickerDetailSheet(
            symbol = sym,
            position = ui.state?.positions?.firstOrNull { it.symbol == sym },
            trades = ui.trades.filter { it.symbol == sym },
            onDismiss = { detailSymbol = null },
        )
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
        // Risk beside return. Without it "+2.48%" describes a book that could have got there in a
        // straight line or been 20% underwater on the way, and the two read identically. Shown only
        // once the curve is long enough to HAVE a drawdown -- a fresh account displaying "max DD
        // 0.00%" claims a measurement it has not earned.
        st.maxDrawdownPct?.let { maxDd ->
            val cur = st.currentDrawdownPct ?: 0.0
            Text(
                "Max drawdown ${pctPlain(maxDd)}" +
                    if (cur > 0.05) " · ${pctPlain(cur)} below peak" else " · at its peak",
                style = MaterialTheme.typography.labelMedium,
                color = if (cur > 0.05) AMBER else neutral,
            )
        }
        trendPctPerMonth?.let { rate ->
            Text(
                // Says what it excludes when there IS something to exclude. The equity curve above
                // rises on a deposit and this figure does not, which looks like a contradiction
                // unless the reason is on screen.
                "Trending " + signedPct(rate) + " / month" +
                    if (st.settings.monthlyDeposit > 0) " (excl. deposits)" else "",
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
private fun PositionRow(p: SandboxPosition, equity: Double, onClick: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val weight = if (equity > 0) p.value / equity * 100 else 0.0
    val sym = p.symbol.removeSuffix("-USD")
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 4.dp),
        Arrangement.SpaceBetween,
        Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(sym, fontWeight = FontWeight.SemiBold)
            // What it paid, right on the row — the first thing you want when asking "how did it get here?"
            Text(
                "${trimNum(p.shares)} sh · ${weight.toInt()}% · avg ${Formatting.price(p.avgCost)}",
                style = MaterialTheme.typography.labelSmall, color = neutral,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$" + Formatting.compact(p.value), fontWeight = FontWeight.Medium)
            p.unrealizedPct?.let {
                Text(signedPct(it), style = MaterialTheme.typography.labelSmall, color = if (it >= 0) GREEN else RED)
            }
        }
        Icon(
            Icons.Filled.ChevronRight, contentDescription = "Open $sym history",
            tint = neutral, modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * One trade, collapsed to a single line.
 *
 * The reasoning text is the whole value of this log and also what made it unreadable — a 60-entry list
 * where every entry was a paragraph. Collapsed, a row is exactly one line: side, date, name, size and
 * fill price. Everything else — the AI's reasoning, the rule that blocked an order, the entry zone it
 * wanted, conviction — appears on tap.
 */
@Composable
private fun TradeRow(
    t: SandboxTrade,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenSymbol: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val skipped = t.status == "skipped"
    val isCash = t.symbol == "CASH"
    val sym = t.symbol.removeSuffix("-USD")
    val color = when {
        skipped -> neutral
        t.side == "buy" -> GREEN
        t.side == "sell" -> RED
        else -> MaterialTheme.colorScheme.primary   // deposit/withdraw
    }
    Column(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A blocked order gets its own amber BLOCKED pill — a greyed "BUY" read like a real buy at a
            // glance, which hid the fact that a rule stopped it.
            Pill(if (skipped) "BLOCKED" else t.side.uppercase(), if (skipped) AMBER else color)
            Text(
                oneLineSummary(t),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!skipped && t.realizedPl != null && t.realizedPl != 0.0) {
                Text(signedUsd(t.realizedPl), style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, color = if (t.realizedPl >= 0) GREEN else RED)
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse trade" else "Expand trade",
                tint = neutral,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // Full date and un-abbreviated money — the collapsed line trades precision for width,
                // this is where the exact numbers live.
                DetailLine("Date", t.date)
                when {
                    isCash -> DetailLine("Amount", signedMoney(t.gross ?: 0.0))
                    !skipped -> {
                        DetailLine("Filled", "${trimNum(t.shares)} sh @ ${Formatting.price(t.price ?: 0.0)}")
                        t.gross?.let { DetailLine("Value", Formatting.price(kotlin.math.abs(it))) }
                        // A fill worth more than the order it came from has to account for the gap
                        // here, otherwise the row reads as if the AI asked for exactly this.
                        t.sizeNote?.takeIf { it.isNotBlank() }?.let { DetailLine("Sizing", it, AMBER) }
                        t.realizedPl?.takeIf { it != 0.0 }?.let {
                            DetailLine("Realized", signedMoney(it), if (it >= 0) GREEN else RED)
                        }
                    }
                }
                t.conviction?.let { DetailLine("Conviction", "$it") }
                if (t.source.isNotBlank()) DetailLine("Source", t.source)
                if (skipped) {
                    DetailLine("Rule", t.skipReason ?: "blocked", AMBER)
                    if (t.entryLow != null || t.entryHigh != null) {
                        DetailLine("Entry zone", entryZoneText(t))
                    }
                    // Keep the AI's original intent visible so you can see WHAT the rule stopped.
                    if (t.reason.isNotBlank()) DetailLine("Wanted", t.reason)
                } else if (t.reason.isNotBlank()) {
                    DetailLine("Why", t.reason)
                }
                if (!isCash && t.symbol.isNotBlank()) {
                    TextButton(
                        onClick = onOpenSymbol,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                    ) {
                        Text("View $sym history", style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** Label + value, for the opened detail of a trade. */
@Composable
private fun DetailLine(label: String, value: String, valueColor: Color? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(74.dp),
        )
        Text(
            value, style = MaterialTheme.typography.labelSmall,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Everything the sandbox has done in one name: what it holds, what it paid, and every fill behind it.
 *
 * The position figures come from the server and are authoritative. The activity list is drawn from the
 * trade window the app has loaded, which does not necessarily reach back to when the position was
 * opened. So the sheet reconciles the two — if the listed buys and sells don't add up to the shares
 * actually held, it says the history is partial instead of letting a truncated list read as the whole
 * story. Same reason the realized figure is labelled as covering only the trades shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TickerDetailSheet(
    symbol: String,
    position: SandboxPosition?,
    trades: List<SandboxTrade>,
    onDismiss: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val sym = symbol.removeSuffix("-USD")
    val filled = trades.filter { it.status == "filled" }
    val net = filled.sumOf {
        when (it.side) {
            "buy" -> it.shares
            "sell" -> -it.shares
            else -> 0.0
        }
    }
    val held = position?.shares ?: 0.0
    val partial = kotlin.math.abs(net - held) > 0.01
    val realized = filled.mapNotNull { it.realizedPl }.sum()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(sym, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Pill(if (held > 0) "HOLDING" else "CLOSED", if (held > 0) GREEN else neutral)
            }
            position?.let { p ->
                // The server uses the ticker itself as the group for single-name holdings — repeating it
                // under the title is noise, so it only shows when it says something new ("SP500").
                if (p.exposureGroup.isNotBlank() && !p.exposureGroup.equals(sym, ignoreCase = true)) {
                    Text(p.exposureGroup, style = MaterialTheme.typography.labelSmall, color = neutral)
                }
                val basis = p.shares * p.avgCost
                val unrealized = p.value - basis
                StatLine("Shares", trimNum(p.shares))
                StatLine("Average cost", Formatting.price(p.avgCost))
                StatLine("Last price", Formatting.price(p.price))
                StatLine("Cost basis", Formatting.price(basis))
                StatLine("Market value", Formatting.price(p.value))
                StatLine(
                    "Unrealized",
                    signedMoney(unrealized) + (p.unrealizedPct?.let { " (${signedPct(it)})" } ?: ""),
                    if (unrealized >= 0) GREEN else RED,
                )
            }
            if (realized != 0.0) {
                StatLine(
                    if (partial) "Realized (shown)" else "Realized",
                    signedMoney(realized),
                    if (realized >= 0) GREEN else RED,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("Activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (trades.isEmpty()) {
                Text(
                    "No $sym trades in the loaded history.",
                    style = MaterialTheme.typography.bodySmall, color = neutral,
                )
            } else {
                trades.forEach { ActivityRow(it) }
            }
            if (partial) {
                val sharesHeld = trimNum(held) + if (held == 1.0) " share" else " shares"
                Text(
                    "Only the most recent trades are loaded, and what's listed here doesn't add up to " +
                        "the $sharesHeld held — so $sym has earlier activity that isn't shown.",
                    style = MaterialTheme.typography.labelSmall, color = AMBER,
                )
            }
        }
    }
}

/** One fill in the per-ticker history — date, size, the price it got, and why. */
@Composable
private fun ActivityRow(t: SandboxTrade) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val skipped = t.status == "skipped"
    val color = when {
        skipped -> AMBER
        t.side == "buy" -> GREEN
        t.side == "sell" -> RED
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Pill(if (skipped) "BLOCKED" else t.side.uppercase(), color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.date, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            Text(
                if (skipped) {
                    "Not placed — " + (t.skipReason ?: "blocked")
                } else {
                    "${trimNum(t.shares)} sh @ ${Formatting.price(t.price ?: 0.0)}" +
                        (t.gross?.let { " · ${Formatting.price(kotlin.math.abs(it))}" } ?: "")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (skipped) AMBER else neutral,
            )
            if (t.reason.isNotBlank()) {
                Text(t.reason, style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
        if (!skipped && t.realizedPl != null && t.realizedPl != 0.0) {
            Text(
                signedMoney(t.realizedPl), style = MaterialTheme.typography.labelMedium,
                color = if (t.realizedPl >= 0) GREEN else RED,
            )
        }
    }
}

/** Label on the left, figure on the right — the position summary lines in the ticker sheet. */
@Composable
private fun StatLine(label: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(
            label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
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
internal fun DateField(
    label: String,
    iso: String?,
    modifier: Modifier = Modifier,
    /** Selectable years. Defaults to Material3's 1900..2100. A birth date narrows it to the past,
     *  which both rules out an impossible answer and drops the year list somewhere useful — the
     *  default opens on this year, roughly fifty scrolls from where a birth date lives. */
    yearRange: IntRange = DatePickerDefaults.YearRange,
    onPicked: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        Text(iso ?: label, maxLines = 1)
    }
    if (open) {
        val stateDp = rememberDatePickerState(
            initialSelectedDateMillis = iso?.let {
                runCatching { java.time.LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
            },
            yearRange = yearRange,
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
private fun LegendRow(color: Color, label: String, pct: Double, onClick: (() -> Unit)? = null) {
    Row(
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
        mem.sandboxBuys?.let { "Bought" to it },
        mem.sandboxSells?.let { "Sold" to it },
        mem.buyCalls?.let { "Buy calls" to it },
        mem.sellCalls?.let { "Sell calls" to it },
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
                val beat = sc.correctRate20d
                val excess = sc.edgePct
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
                            // "right" reads correctly for both sides; the sell inversion is
                            // already applied server-side, so no per-card wording change is needed.
                            excess?.let { "right · " + signedPct(it) + " vs index" } ?: "right",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "How often each call was right, measured 20 trading days later against the S&P. " +
                    "Above 50% means it added value; below means buying the index would have done " +
                    "better. A sell counts as right when the name then lagged the index.",
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

/** Which book the screen is showing. A side arm is a paper experiment against the main account;
 *  saying so on the chip itself is cheaper than a legend nobody reads. */
@Composable
private fun ArmSwitcher(
    arms: List<com.stocktracker.app.data.remote.SandboxArm>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        arms.forEach { a ->
            FilterChip(
                selected = a.arm == selected,
                onClick = { onSelect(a.arm) },
                label = { Text(a.label.ifBlank { a.arm }) },
                leadingIcon = if (a.engine == "rules") {
                    { Icon(Icons.Filled.Calculate, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
            )
        }
    }
}

/** Every arm side by side on the only figure that is comparable between them.
 *
 *  Deliberately NOT raw equity or total return: arms can be funded with different amounts on
 *  different days, so those differ for reasons that have nothing to do with the strategy. Each arm
 *  carries its own "same money in the S&P" shadow, and the excess over that shadow is what lines up. */
@Composable
private fun ArmComparison(
    arms: List<com.stocktracker.app.data.remote.SandboxArm>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Arms", style = MaterialTheme.typography.titleSmall)
            Text(
                "Same market, same tick, same day — so the difference between them is the strategy. " +
                    "Each is measured against its own S&P shadow, since raw equity isn't comparable.",
                style = MaterialTheme.typography.bodySmall, color = neutral,
            )
            // The spread is the actual result; showing it saves the reader doing the subtraction,
            // and it is only meaningful once at least two arms have a shadow to measure against.
            val measured = arms.mapNotNull { it.vsBenchmarkPct }
            arms.forEach { a ->
                val vs = a.vsBenchmarkPct
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(a.arm) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            a.label.ifBlank { a.arm },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (a.arm == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            buildString {
                                append(if (a.engine == "rules") "mechanical" else "analyst")
                                a.cashPct?.let { append(" · ${"%.0f".format(it)}% cash") }
                                append(" · ${a.positions} holding${if (a.positions == 1) "" else "s"}")
                                if (!a.enabled) append(" · paused")
                            },
                            style = MaterialTheme.typography.labelSmall, color = neutral,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "$" + Formatting.compact(a.equity),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            // Absent is not zero — an arm with no benchmark shadow yet has no
                            // comparable number, and a confident "0.00%" would be a fabrication.
                            if (vs == null) "—"
                            else (if (vs >= 0) "+" else "") + "%.2f".format(vs) + "% vs S&P",
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                vs == null -> neutral
                                vs >= 0 -> GREEN
                                else -> RED
                            },
                        )
                    }
                }
            }
            if (measured.size >= 2) {
                val spread = measured.max() - measured.min()
                Text(
                    "Spread: %.2f points between best and worst.".format(spread) +
                        " Too few days to mean anything yet — this needs weeks, not ticks.",
                    style = MaterialTheme.typography.labelSmall, color = neutral,
                )
            }
        }
    }
}

/** One colour per arm, stable across recompositions and independent of list order — so an arm keeps
 *  its colour when another is added or deleted. */
private val ARM_COLORS = listOf(
    Color(0xFF2563EB), Color(0xFFB0872B), Color(0xFF16A34A),
    Color(0xFF9333EA), Color(0xFFDC2626), Color(0xFF0891B2),
)

private fun armColor(arm: String): Color =
    ARM_COLORS[(arm.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % ARM_COLORS.size]

/** "2026-08-13" → epoch millis at UTC midnight. The NAV axis is ET trading DATES, not instants, so
 *  the wall-clock time within the day is meaningless — anchoring to a fixed offset keeps the chart's
 *  x-spacing exactly one day per point regardless of the device's timezone or DST. Falls back to 0
 *  on an unparseable date rather than throwing inside a composable. */
private fun dateToEpochMillis(d: String): Long =
    runCatching { java.time.LocalDate.parse(d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        .getOrDefault(0L)

/** Every arm's trajectory on one axis, indexed to 100 at the first day they all existed.
 *
 *  Indexed, not raw equity, because raw equity across arms is not a comparison: arms can be funded
 *  with different amounts on different days, so the tallest line would just be the richest one. And
 *  indexed from the COMMON start — before that date at least one arm did not exist, and basing there
 *  would credit or blame it for a period it never traded.
 *
 *  When there is no overlapping history yet, this says so and draws nothing. An empty chart is not a
 *  finding; a chart drawn from one arm's history pretending to be five is. */
@Composable
private fun ArmTrendCard(
    nav: com.stocktracker.app.data.remote.SandboxArmsNav,
    arms: List<com.stocktracker.app.data.remote.SandboxArm>,
    selected: String,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val base = nav.commonStartIndex
    Box(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How they're tracking", style = MaterialTheme.typography.titleSmall)
            if (base == null || nav.dates.size - base < 2) {
                Text(
                    "The arms don't have overlapping history yet. This chart appears once every arm " +
                        "has at least two days in common — arms created today start contributing at " +
                        "their first tick.",
                    style = MaterialTheme.typography.bodySmall, color = neutral,
                )
                return@Column
            }
            val dates = nav.dates.drop(base)
            // Index each arm to 100 at the common start. A null inside an arm's window is a day that
            // arm didn't tick; left null so the line breaks rather than inventing a flat segment.
            val indexed = nav.arms.mapNotNull { s ->
                val window = s.equity.drop(base)
                val b = window.firstOrNull() ?: return@mapNotNull null
                if (b <= 0.0) return@mapNotNull null
                Triple(s, window.map { v -> v?.let { it / b * 100.0 } }, b)
            }
            if (indexed.size < 2) {
                Text("Not enough arms with data to compare yet.",
                     style = MaterialTheme.typography.bodySmall, color = neutral)
                return@Column
            }
            // The selected arm is the solid line; the rest are overlays. PriceChart needs a concrete
            // main series, and making it the one you're already looking at keeps the two consistent.
            val primary = indexed.firstOrNull { it.first.arm == selected } ?: indexed.first()
            val points = dates.indices.mapNotNull { i ->
                primary.second[i]?.let { v ->
                    PricePoint(dateToEpochMillis(dates[i]), v)
                }
            }
            val overlays = indexed.filter { it !== primary }.map { (s, vals, _) ->
                ChartLineOverlay(s.label.ifBlank { s.arm }, armColor(s.arm), vals)
            }
            if (points.size >= 2) {
                PriceChart(
                    points = points,
                    up = points.last().price >= points.first().price,
                    showAxis = true,
                    overlays = overlays,
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    valueFormatter = { "%.1f".format(it) },
                    timeFormatter = {
                        com.stocktracker.app.util.formatChartTimestamp(
                            it, com.stocktracker.app.data.model.ChartRange.ALL)
                    },
                )
            }
            // Legend: the solid line is named too, since the chart itself only labels overlays.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                indexed.forEach { (s, vals, _) ->
                    val last = vals.lastOrNull { it != null }
                    val isPrimary = s === primary.first
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(9.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (isPrimary) GREEN else armColor(s.arm)),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            s.label.ifBlank { s.arm } + if (isPrimary) " (shown)" else "",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            last?.let { (if (it >= 100) "+" else "") + "%.2f".format(it - 100) + "%" } ?: "—",
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                last == null -> neutral
                                last >= 100 -> GREEN
                                else -> RED
                            },
                        )
                    }
                }
            }
            Text(
                "Indexed to 100 on ${nav.commonStart} — the first day all arms existed. " +
                    "${dates.size} day${if (dates.size == 1) "" else "s"} of overlap: far too short " +
                    "to separate skill from luck, and with ${indexed.size} arms the best-looking one " +
                    "is most likely the luckiest.",
                style = MaterialTheme.typography.labelSmall, color = neutral,
            )
        }
    }
}

/** Shown in place of the auto-trade row on a side arm. The controls it replaces write to main. */
@Composable
private fun SideArmNotice(st: com.stocktracker.app.data.remote.SandboxState) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (st.engine == "rules") Icons.Filled.Calculate else Icons.Filled.SmartToy,
                    contentDescription = null, tint = neutral, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (st.engine == "rules") "Mechanical arm — no AI" else "Comparison arm",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                if (st.engine == "rules") {
                    "Fills toward the standing plan's targets, largest gap first. It takes no view " +
                        "and never sells — it exists to show what the analyst is worth on top of " +
                        "simply executing the plan."
                } else {
                    "Runs the same analyst on the same market as the main account, with one setting " +
                        "changed, so the two can be compared."
                },
                style = MaterialTheme.typography.bodySmall, color = neutral,
            )
            Text(
                "Read-only here. Funding, settings and the auto-trade switch belong to the main " +
                    "account — switch to it to change them.",
                style = MaterialTheme.typography.labelSmall, color = neutral,
            )
        }
    }
}

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

/** A magnitude, deliberately unsigned. A drawdown carries no sign — printing "-2.7%" beside "+2.5%
 *  return invites the reader to net them, which is not what either number means. */
private fun pctPlain(v: Double) = String.format(java.util.Locale.US, "%.2f%%", kotlin.math.abs(v))

/** Abbreviated money, for places where width is scarce (a collapsed log line, the header metrics). */
private fun signedUsd(v: Double) = (if (v >= 0) "+$" else "-$") + Formatting.compact(kotlin.math.abs(v))

/** Money to the cent. `compact` rounds to whole dollars under $1,000, which is fine for a headline but
 *  wrong for an execution price or a cost basis — "what did it pay?" is the whole point of those views. */
private fun signedMoney(v: Double) = (if (v >= 0) "+" else "-") + Formatting.price(kotlin.math.abs(v))

private fun trimNum(v: Double) = Formatting.shares(v)

/** Stable identity for a trade, so an expanded row stays expanded across refreshes. */
private fun tradeKey(t: SandboxTrade) = "${t.ts}|${t.symbol}|${t.side}|${t.status}|${t.shares}"

/** "2026-07-28" → "07-28". The year is the same for every row in a 60-entry log; the width isn't. */
private fun shortDate(iso: String) = if (iso.length >= 10) iso.substring(5) else iso

/** The collapsed trade line — one line, always. */
private fun oneLineSummary(t: SandboxTrade): String {
    val sym = t.symbol.removeSuffix("-USD")
    return when {
        t.symbol == "CASH" -> "${shortDate(t.date)} · ${signedUsd(t.gross ?: 0.0)}"
        t.status == "skipped" -> "${shortDate(t.date)} · $sym not placed"
        else -> "${shortDate(t.date)} · $sym · ${trimNum(t.shares)} sh @ ${Formatting.price(t.price ?: 0.0)}"
    }
}

private fun entryZoneText(t: SandboxTrade): String = when {
    t.entryLow != null && t.entryHigh != null ->
        "${Formatting.price(t.entryLow)} – ${Formatting.price(t.entryHigh)}"
    t.entryHigh != null -> "≤ ${Formatting.price(t.entryHigh)}"
    t.entryLow != null -> "≥ ${Formatting.price(t.entryLow)}"
    else -> "—"
}
