package com.stocktracker.app.ui.pick

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.DailyPickHistoryItem
import com.stocktracker.app.data.remote.DailyPickResponse
import com.stocktracker.app.ui.components.FiftyTwoWeekRangeBar
import com.stocktracker.app.ui.components.ThresholdMeter
import com.stocktracker.app.ui.components.TwoHundredWeekLineBar
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.Signal
import java.util.Locale

/**
 * DP-6 — the "Why" sheet: every reason in full, the range bars, the RSI dial, what would make it wrong,
 * the runners-up, and the record of past picks against a simple rule. Also the "no pick" day's detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyPickSheet(
    resp: DailyPickResponse,
    state: DailyPickUiState,
    explain: ExplainState,
    onDismiss: () -> Unit,
    onOpenSymbol: (String, String?) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val p = resp.pick
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (resp.isPick && p != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ConvictionRing(p.conviction, size = 52)
                    Column {
                        Text(p.symbol ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        p.name?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral) }
                    }
                }
                p.thesis?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                ContextChips(resp)
                DailyPickRead.scanLagNote(resp)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Signal) }

                Section("Reasons for") { explain.show("percentile", "About the bars") }
                val factors = p.factors.associateBy { it.key }
                p.reasons.filter { it.supports }.forEach { r ->
                    FactorRow(true, factors[r.factor], r.factor, r.text, onExplain = { explain.show(r.factor, factors[r.factor]?.label ?: r.factor) })
                }
                Section("Reasons against")
                p.reasons.filterNot { it.supports }.forEach { r ->
                    FactorRow(false, factors[r.factor], r.factor, r.text, onExplain = { explain.show(r.factor, factors[r.factor]?.label ?: r.factor) })
                }
                if ((p.reasonsDropped ?: 0) > 0) {
                    Text("${p.reasonsDropped} reason(s) the AI gave were removed because they relied on data that could not be checked.",
                        style = MaterialTheme.typography.labelSmall, color = neutral)
                }

                Section("What would make it wrong")
                p.invalidation?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                PlanLadder(p.levels, resp.live?.price)
                p.levelNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = neutral) }

                Section("Where it sits")
                p.rsi14?.let { rsi ->
                    ThresholdMeter(
                        label = "How fast it has risen (RSI — over 70 is overheated)",
                        valueText = String.format(Locale.US, "%.0f", rsi),
                        fraction = (rsi / 100.0).toFloat().coerceIn(0f, 1f),
                        color = if (rsi >= 70) LossRed else if (rsi <= 30) GainGreen else Signal,
                        thresholdFraction = 0.7f,
                    )
                }
                val px = resp.live?.price ?: p.priceAtPick
                if (px != null && p.fiftyTwoWeekLow != null && p.fiftyTwoWeekHigh != null) {
                    Text("52-week range", style = MaterialTheme.typography.labelMedium, color = neutral)
                    FiftyTwoWeekRangeBar(
                        low = p.fiftyTwoWeekLow, high = p.fiftyTwoWeekHigh, current = px,
                        up = (resp.live?.changePct ?: 0.0) >= 0, valueFormatter = { DailyPickRead.money(it) },
                    )
                }
                if (px != null && p.sma200w != null && p.sma200w > 0) {
                    val pct = (px / p.sma200w - 1) * 100
                    Text("Vs its 4-year average (200-week)", style = MaterialTheme.typography.labelMedium, color = neutral)
                    TwoHundredWeekLineBar(pctFromLine = pct, belowLine = pct < 0)
                }

                if (p.runnersUp.isNotEmpty()) Section("What came close")
                p.runnersUp.forEach { r ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenSymbol(r.symbol, r.name) }) {
                        Text(r.symbol + (r.price?.let { "  ${DailyPickRead.money(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        r.whyNot?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral) }
                    }
                }
                resp.rulePick?.symbol?.let { rs ->
                    Text(
                        if (rs == p.symbol) "A simple rule (no AI) would have picked the same stock."
                        else "A simple rule (no AI) would have picked $rs. Both are graded against the S&P.",
                        style = MaterialTheme.typography.bodySmall, color = neutral,
                    )
                }
            } else {
                Text(DailyPickRead.header(DailyPickRead.Shape.NoPick(resp, resp.stale == true)),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(resp.noneReason ?: "Nothing was convincing enough today.", style = MaterialTheme.typography.bodyMedium)
                ContextChips(resp)
                p?.runnersUp?.takeIf { it.isNotEmpty() }?.let { ru ->
                    Section("What came close")
                    ru.forEach { r ->
                        Column(Modifier.fillMaxWidth().clickable { onOpenSymbol(r.symbol, r.name) }) {
                            Text(r.symbol, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            r.whyNot?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral) }
                        }
                    }
                }
                resp.screen?.let { s ->
                    val parts = listOfNotNull(
                        s.scanned?.let { "$it stocks checked" }, s.eligible?.let { "$it passed the basic filters" },
                        s.earningsExcluded.size.takeIf { it > 0 }?.let { "$it skipped because earnings are due" },
                    )
                    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = neutral)
                }
                resp.rulePick?.symbol?.let {
                    Text("A simple rule (no AI) would have picked $it; it is graded either way, so declining can be measured.",
                        style = MaterialTheme.typography.bodySmall, color = neutral)
                }
            }

            HorizontalDivider()
            PastPicks(state)
            Text("A reading, not advice. Nothing here places a trade.", style = MaterialTheme.typography.labelSmall, color = neutral)
        }
    }
}

@Composable
private fun Section(title: String, onInfo: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(Locale.US), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
        if (onInfo != null) InfoButton(title, onInfo)
    }
}

/** DP-4 / DP-11: the last picks, each with its 5- and 20-day result vs the S&P, and the AI-vs-rule tally. */
@Composable
private fun PastPicks(state: DailyPickUiState) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Section("Past picks")
    when {
        state.historyLoading && state.history == null -> CircularProgressIndicator()
        state.historyError != null -> Text(state.historyError, style = MaterialTheme.typography.bodySmall, color = LossRed)
        state.history == null -> Text("—", color = neutral)
        else -> {
            val h = state.history
            listOf("5d" to "After 1 week", "20d" to "After 1 month").forEach { (k, label) ->
                DailyPickRead.comparisonLine(h.comparison[k], h.minDaysForComparison)?.let {
                    Text("$label: $it", style = MaterialTheme.typography.bodySmall, color = neutral)
                }
            }
            val items = h.items
            if (items.isEmpty()) Text("No past picks yet.", style = MaterialTheme.typography.bodySmall, color = neutral)
            Row(Modifier.fillMaxWidth()) {
                Text("Date", Modifier.width(88.dp), style = MaterialTheme.typography.labelSmall, color = neutral)
                Text("Pick", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = neutral)
                Text("1 wk vs S&P", Modifier.width(76.dp), style = MaterialTheme.typography.labelSmall, color = neutral)
                Text("1 mo vs S&P", Modifier.width(76.dp), style = MaterialTheme.typography.labelSmall, color = neutral)
            }
            items.forEach { PastRow(it) }
        }
    }
}

@Composable
private fun PastRow(it: DailyPickHistoryItem) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(it.date.drop(5), Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall)
        Text(
            when (it.status) {
                "pick" -> it.symbol ?: "—"
                "none" -> "no pick"
                "failed" -> "failed"
                else -> "—"
            },
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = if (it.status == "pick") MaterialTheme.colorScheme.onSurface else neutral,
        )
        for (k in listOf("5d", "20d")) {
            val ex = it.marks[k]?.excessPp
            Text(
                // A mark not written yet is "pending", never 0.0.
                ex?.let { e -> String.format(Locale.US, "%+.1f pts", e) } ?: if (it.status == "pick") "not yet" else "—",
                Modifier.width(76.dp), style = MaterialTheme.typography.bodySmall,
                color = when {
                    ex == null -> neutral
                    ex >= 0 -> GainGreen
                    else -> LossRed
                },
            )
        }
    }
}

/**
 * DP-13 — "I bought it". Pre-filled with the live price; the user confirms what they actually paid and
 * how many shares. Either may be left blank, and a blank is stored as unknown, never as zero.
 */
@Composable
fun BoughtDialog(resp: DailyPickResponse, onDismiss: () -> Unit, onConfirm: (shares: Double?, price: Double?) -> Unit) {
    val sym = resp.pick?.symbol ?: return
    val start = resp.live?.price?.let { String.format(Locale.US, "%.2f", it) } ?: ""
    var price by rememberSaveable { mutableStateOf(start) }
    var shares by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log your $sym purchase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved to your journal with the pick's exit and target prices, so you can see later how it went.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(price, { price = it }, label = { Text("Price you paid") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(shares, { shares = it }, label = { Text("Shares") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(shares.toDoubleOrNull(), price.toDoubleOrNull()) }) { Text("Log it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
