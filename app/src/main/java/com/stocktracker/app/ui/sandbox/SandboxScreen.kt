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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.remote.SandboxState
import com.stocktracker.app.data.remote.SandboxStrategyNote
import com.stocktracker.app.data.remote.SandboxTrade
import com.stocktracker.app.ui.components.ChartLineOverlay
import com.stocktracker.app.ui.components.ChartMarker
import com.stocktracker.app.ui.components.PriceChart
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.util.Formatting
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val GREEN = GainGreen
private val RED = LossRed
private val AMBER = Color(0xFFB0872B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxScreen(onOpenSettings: () -> Unit = {}) {
    val vm: SandboxViewModel = viewModel()
    val ui by vm.state.collectAsState()
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

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
                },
            )
        },
    ) { padding ->
        val st = ui.state
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!ui.configured) {
                item { InfoCard("Set the Signals service URL in Settings to use the AI sandbox.") }
                return@LazyColumn
            }
            if (ui.error != null && st == null) {
                item { InfoCard(ui.error!!) }
            }
            if (st == null || st.fundedTotal <= 0.0) {
                item { Spacer(Modifier.height(8.dp)) }
                item { EmptyState(onFund = { vm.fund(it) }) }
                if (st != null) item { SettingsSection(vm, st) }
                return@LazyColumn
            }

            item { Spacer(Modifier.height(4.dp)) }
            item { HeaderMetrics(st) }
            item {
                val up = ui.nav.size >= 2 && ui.nav.last().price >= ui.nav.first().price
                val overlays = if (ui.benchmarkValues.any { it != null })
                    listOf(ChartLineOverlay("S&P 500", Color(0xFFEC4899), ui.benchmarkValues)) else emptyList()
                val markers = ui.trades
                    .filter { it.status == "filled" && (it.side == "buy" || it.side == "sell") }
                    .take(40)
                    .map { ChartMarker((it.ts * 1000).toLong(), if (it.side == "buy") GREEN else RED, it.symbol) }
                if (ui.nav.size >= 2) {
                    PriceChart(
                        points = ui.nav, up = up, showHighLow = true, showAxis = true,
                        overlays = overlays, markers = markers,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
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
            st.strategyNote?.let { item { StrategyCard(it) } }
            if (st.positions.isNotEmpty()) {
                item { SectionLabel("Holdings") }
                items(st.positions) { p -> PositionRow(p, st.equity) }
            }
            item { SectionLabel("Trade log") }
            if (ui.trades.isEmpty()) {
                item { Text("No trades yet.", style = MaterialTheme.typography.bodySmall, color = neutral) }
            } else {
                items(ui.trades.take(60)) { t -> TradeRow(t) }
            }
            item { SettingsSection(vm, st) }
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
private fun HeaderMetrics(st: SandboxState) {
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
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Weekly strategy", style = MaterialTheme.typography.labelLarge, color = neutral)
            Pill(n.stance.ifBlank { "—" }, color)
            Text("cash target ${n.cashTargetPct.toInt()}%", style = MaterialTheme.typography.labelMedium, color = neutral)
        }
        if (n.notes.isNotBlank()) Text(n.notes, style = MaterialTheme.typography.bodySmall)
        if (n.themes.isNotEmpty()) Text("Lean in: " + n.themes.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = neutral)
        if (n.avoid.isNotEmpty()) Text("Avoid: " + n.avoid.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = neutral)
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
        Pill(t.side.uppercase(), color)
        Column(Modifier.weight(1f)) {
            val head = when {
                t.symbol == "CASH" -> "${t.date} · " + signedUsd(t.gross ?: 0.0)
                skipped -> "${t.date} · ${t.symbol}"
                else -> "${t.date} · ${t.symbol.removeSuffix("-USD")} · ${trimNum(t.shares)} sh @ $${Formatting.compact(t.price ?: 0.0)}"
            }
            Text(head, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val sub = if (skipped) (t.skipReason ?: "") else t.reason
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = neutral)
        }
        if (!skipped && t.realizedPl != null && t.realizedPl != 0.0) {
            Text(signedUsd(t.realizedPl), style = MaterialTheme.typography.labelMedium,
                color = if (t.realizedPl >= 0) GREEN else RED)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSection(vm: SandboxViewModel, st: SandboxState) {
    val s = st.settings
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var cashText by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings & goals", style = MaterialTheme.typography.labelLarge, color = neutral)

        Text("Risk tolerance", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("conservative", "balanced", "aggressive").forEach { r ->
                FilterChip(selected = s.riskTolerance == r, onClick = { vm.setRisk(r) },
                    label = { Text(r.replaceFirstChar { it.uppercase() }) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField("Retirement", s.retirementDate, Modifier.weight(1f)) { vm.setRetirementDate(it) }
            DateField("Exit date", s.exitDate, Modifier.weight(1f)) { vm.setExitDate(it) }
        }

        Text("Max per position", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20, 25, 33).forEach { pct ->
                FilterChip(selected = s.maxPositionPct.toInt() == pct, onClick = { vm.setMaxPositionPct(pct.toDouble()) },
                    label = { Text("$pct%") })
            }
        }
        Text("Cash floor", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 5, 10, 20).forEach { pct ->
                FilterChip(selected = s.cashFloorPct.toInt() == pct, onClick = { vm.setCashFloorPct(pct.toDouble()) },
                    label = { Text("$pct%") })
            }
        }
        SwitchRow("Allow crypto (BTC/ETH ETFs)", s.allowCrypto) { vm.setAllowCrypto(it) }
        SwitchRow("Allow ETFs", s.allowEtf) { vm.setAllowEtf(it) }

        Text("Funds", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = cashText, onValueChange = { cashText = it }, prefix = { Text("$") },
                label = { Text("Amount") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                cashText.replace(",", "").removePrefix("$").trim().toDoubleOrNull()?.let { vm.fund(it) }
                cashText = ""
            }) { Text("Add") }
        }
        HelperText("Adds fictional cash; a deposit also buys the S&P benchmark on the same schedule.")

        Spacer(Modifier.height(2.dp))
        if (!confirmReset) {
            TextButton(onClick = { confirmReset = true }) { Text("Reset sandbox", color = RED) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Wipe everything?", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.reset(); confirmReset = false }) { Text("Yes, reset", color = RED) }
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            }
        }
    }
}

// ---- small reusable bits (kept local to avoid promoting private helpers elsewhere) ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, iso: String?, modifier: Modifier = Modifier, onPicked: (String?) -> Unit) {
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
