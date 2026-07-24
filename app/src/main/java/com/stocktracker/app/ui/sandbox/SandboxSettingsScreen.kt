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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.stocktracker.app.data.remote.SandboxSettings
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.util.Formatting

/**
 * The Sandbox's dedicated settings page (opened from the gear in the Sandbox top bar). Groups every
 * knob into six scannable sections — Funds, Goals & horizon, Risk & style, Universe, Automation, and a
 * Danger zone — with live visual feedback (goal-progress bar, allocation-style meters) so the effect of
 * a change is immediately obvious.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxSettingsScreen(onBack: () -> Unit) {
    val vm: SandboxViewModel = viewModel()
    val ui by vm.state.collectAsState()
    val st = ui.state
    val s = st?.settings ?: SandboxSettings()
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sandbox settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ---------------- FUNDS ----------------
            item {
                Section("Funds", "Fictional money the AI manages") {
                    val equity = st?.equity ?: 0.0
                    val funded = st?.fundedTotal ?: 0.0
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        StatBlock("Balance", "$" + Formatting.compact(equity))
                        StatBlock("Deposited", "$" + Formatting.compact(funded))
                        StatBlock("Cash", (st?.cashPct?.toInt()?.toString() ?: "—") + "%")
                    }
                    Spacer(Modifier.height(10.dp))
                    var amt by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = amt, onValueChange = { amt = it }, prefix = { Text("$") },
                            label = { Text("Amount") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = {
                            parseMoney(amt)?.let { vm.fund(it) }; amt = ""
                        }) { Text("Add") }
                        OutlinedButton(onClick = {
                            parseMoney(amt)?.let { vm.fund(-it) }; amt = ""
                        }) { Text("Withdraw") }
                    }
                    Helper("A deposit also buys the S&P benchmark on the same day, so the comparison stays fair.")

                    Spacer(Modifier.height(10.dp))
                    Label("Recurring monthly deposit (DCA)")
                    ChipRow(listOf(0.0, 100.0, 250.0, 500.0), s.monthlyDeposit, { vm.setMonthlyDeposit(it) }) {
                        if (it == 0.0) "Off" else "$" + it.toInt()
                    }
                    Helper(
                        if (s.monthlyDeposit > 0)
                            "Adds $${s.monthlyDeposit.toInt()} on the first tick of each month."
                        else "Off — funds are added manually.",
                    )
                }
            }

            // ---------------- GOALS & HORIZON ----------------
            item {
                Section("Goals & horizon", "What it's investing toward") {
                    var goalText by remember(s.goalAmount) { mutableStateOf(s.goalAmount?.toInt()?.toString() ?: "") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = goalText, onValueChange = { goalText = it }, prefix = { Text("$") },
                            label = { Text("Target amount") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        DateField("By", s.goalDate, Modifier.weight(0.8f)) { vm.setGoal(parseMoney(goalText), it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.setGoal(parseMoney(goalText), s.goalDate) }) { Text("Save goal") }
                        if (s.goalAmount != null) {
                            TextButton(onClick = { vm.setGoal(null, null); goalText = "" }) { Text("Clear") }
                        }
                    }
                    // Live goal progress — the instant visual feedback.
                    s.goalAmount?.takeIf { it > 0 }?.let { goal ->
                        val equity = st?.equity ?: 0.0
                        val frac = (equity / goal).coerceIn(0.0, 1.0)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("$" + Formatting.compact(equity), fontWeight = FontWeight.Bold)
                            Text("${(frac * 100).toInt()}% of $" + Formatting.compact(goal),
                                style = MaterialTheme.typography.labelMedium, color = neutral)
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { frac.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)),
                            color = if (frac >= 1.0) GREEN else MaterialTheme.colorScheme.primary,
                        )
                        s.goalDate?.let { Helper("Target date $it — the AI weighs the pace needed to get there.") }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateField("Retirement", s.retirementDate, Modifier.weight(1f)) { vm.setRetirementDate(it) }
                        DateField("Exit date", s.exitDate, Modifier.weight(1f)) { vm.setExitDate(it) }
                    }
                    Helper("Nearing either date shifts the book toward cash. At the exit date it sells everything.")
                }
            }

            // ---------------- RISK & STYLE ----------------
            item {
                Section("Risk & style", "How aggressively it invests") {
                    Label("Aggressiveness")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("conservative" to "Conservative", "balanced" to "Balanced", "aggressive" to "Aggressive")
                            .forEach { (k, lbl) ->
                                FilterChip(selected = s.riskTolerance == k, onClick = { vm.setRisk(k) },
                                    label = { Text(lbl) })
                            }
                    }
                    Spacer(Modifier.height(8.dp))
                    Label("Max per position")
                    ChipRow(listOf(10.0, 20.0, 25.0, 33.0), s.maxPositionPct, { vm.setMaxPositionPct(it) }) { "${it.toInt()}%" }
                    MeterBar(s.maxPositionPct / 100.0, MaterialTheme.colorScheme.primary)

                    Spacer(Modifier.height(8.dp))
                    Label("Cash floor (never fully invested)")
                    ChipRow(listOf(0.0, 5.0, 10.0, 20.0), s.cashFloorPct, { vm.setCashFloorPct(it) }) { "${it.toInt()}%" }
                    MeterBar(s.cashFloorPct / 100.0, AMBER)

                    Spacer(Modifier.height(8.dp))
                    Label("Turnover cap (churn per decision)")
                    ChipRow(listOf(0.0, 10.0, 25.0, 50.0), s.maxTurnoverPct, { vm.setTurnoverPct(it) }) {
                        if (it == 0.0) "None" else "${it.toInt()}%"
                    }
                    Helper(if (s.maxTurnoverPct == 0.0) "Unlimited — it can reposition freely."
                           else "At most ${s.maxTurnoverPct.toInt()}% of the book changes hands per decision.")

                    Spacer(Modifier.height(8.dp))
                    Label("Minimum conviction to buy")
                    ChipRow(listOf(40.0, 55.0, 70.0, 85.0), s.minConvictionToTrade.toDouble(),
                        { vm.setMinConviction(it.toInt()) }) { it.toInt().toString() }
                    Helper("Higher = pickier; it holds cash unless a setup is strong.")
                }
            }

            // ---------------- UNIVERSE ----------------
            item {
                Section("Universe", "What it's allowed to buy") {
                    SwitchLine("Allow crypto (BTC / ETH ETFs)", s.allowCrypto) { vm.setAllowCrypto(it) }
                    SwitchLine("Allow ETFs", s.allowEtf) { vm.setAllowEtf(it) }
                    Spacer(Modifier.height(8.dp))
                    Label("Never buy these")
                    ExclusionSearchField(
                        already = s.exclusions,
                        onPick = { vm.addExclusion(it) },
                    )
                    if (s.exclusions.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            s.exclusions.take(8).forEach { t ->
                                Row(
                                    modifier = Modifier
                                        .background(RED.copy(alpha = 0.14f), RoundedCornerShape(50))
                                        .clickable { vm.removeExclusion(t) }
                                        .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(t, style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold, color = RED)
                                    Icon(Icons.Filled.Close, contentDescription = "Remove $t",
                                        tint = RED, modifier = Modifier.size(14.dp).padding(start = 4.dp))
                                }
                            }
                        }
                    }
                    Helper("Tap a ticker to remove it. The AI never sees excluded names as candidates.")
                }
            }

            // ---------------- AUTOMATION ----------------
            item {
                Section("Automation", "When it runs") {
                    SwitchLine("Auto-trade", s.masterEnabled) { vm.setEnabled(it) }
                    Helper(if (s.masterEnabled) "Runs each weekday at the close (15:35 ET)." else "Paused — no trades.")
                    Spacer(Modifier.height(8.dp))
                    Label("Decision cadence")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("daily" to "Daily", "weekly" to "Weekly").forEach { (k, lbl) ->
                            FilterChip(selected = s.cadence == k, onClick = { vm.setCadence(k) }, label = { Text(lbl) })
                        }
                    }
                    Helper("Weekly still tracks value daily — it just decides less often.")
                    Spacer(Modifier.height(8.dp))
                    SwitchLine("Trade after hours", s.allowAfterHours) { vm.setAllowAfterHours(it) }
                    Helper(
                        if (s.allowAfterHours) "Also trades 4-8pm ET — thinner books, wider spreads."
                        else "Regular session only (9:30am-4pm ET). Crypto trades any time.",
                    )
                    Spacer(Modifier.height(8.dp))
                    SwitchLine("Notify me on each trade", s.notifyOnTrade) { vm.setNotifyOnTrade(it) }
                    st?.lastTickDate?.let { Helper("Last decision: $it") }
                }
            }

            // ---------------- DANGER ZONE ----------------
            item {
                var confirm by remember { mutableStateOf(false) }
                Section("Danger zone", "Irreversible", tint = RED) {
                    if (!confirm) {
                        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Reset sandbox", color = RED)
                        }
                        Helper("Wipes cash, positions and history, and starts fresh.")
                    } else {
                        Text("Reset everything? This can't be undone.", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.reset(); confirm = false }) { Text("Yes, reset") }
                            TextButton(onClick = { confirm = false }) { Text("Cancel") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

/**
 * Type-ahead ticker picker for the exclusion list. Reuses the app's existing live search
 * (`ServiceLocator.repository.search`, the same one behind "Add ticker"), debounced 300ms, so the user
 * picks a real symbol from matches instead of hand-typing one that may not exist. Symbols already
 * excluded are greyed out; a raw entry can still be forced with the Exclude button for anything the
 * search doesn't cover.
 */
@Composable
private fun ExclusionSearchField(already: List<String>, onPick: (String) -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.stocktracker.app.data.model.SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList(); searching = false
            return@LaunchedEffect
        }
        searching = true
        kotlinx.coroutines.delay(300)
        results = runCatching { ServiceLocator.repository.search(query) }.getOrDefault(emptyList()).take(6)
        searching = false
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search a ticker") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (searching) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { onPick(query); query = "" },
            enabled = query.isNotBlank(),
        ) { Text("Exclude") }
    }

    // Live matches — tap one to exclude it.
    results.forEach { r ->
        val dup = already.any { it.equals(r.symbol, true) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !dup) { onPick(r.symbol); query = "" }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                r.symbol.uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (dup) neutral else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                r.name, style = MaterialTheme.typography.labelSmall, color = neutral,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (dup) Text("excluded", style = MaterialTheme.typography.labelSmall, color = neutral)
        }
    }
    if (query.isNotBlank() && !searching && results.isEmpty()) {
        Helper("No matches — tap Exclude to add \"${query.uppercase()}\" anyway.")
    }
}

// ---- local building blocks ----

@Composable
private fun Section(title: String, subtitle: String? = null, tint: Color? = null, content: @Composable () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = tint ?: MaterialTheme.colorScheme.primary)
        subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = neutral) }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)

@Composable
private fun Helper(text: String) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A row of value chips with a formatter — the workhorse for the numeric settings. */
@Composable
private fun ChipRow(values: List<Double>, selected: Double, onPick: (Double) -> Unit, labelOf: (Double) -> String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v ->
            FilterChip(selected = kotlin.math.abs(selected - v) < 0.01, onClick = { onPick(v) },
                label = { Text(labelOf(v)) })
        }
    }
}

/** A thin proportion bar so a % setting reads visually, not just as a number. */
@Composable
private fun MeterBar(frac: Double, color: Color) {
    Spacer(Modifier.height(4.dp))
    LinearProgressIndicator(
        progress = { frac.toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
        color = color,
    )
}

private fun parseMoney(s: String): Double? =
    s.replace(",", "").removePrefix("$").trim().toDoubleOrNull()?.takeIf { it > 0 }
