package com.stocktracker.app.ui.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.ui.ideas.usd
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed

/**
 * "My Calls" (OC-3) — the manually-tracked long-call positions, shown as a section on the Portfolio
 * screen with a "+ Track a call" button. Each row re-prices live via /option_quote; a failed quote
 * (market closed / contract gone) keeps the last-known value rather than dropping the row. Tapping a
 * row opens its detail (cost basis, current value, break-even, DTE, TP/stop/notes + Delete).
 */
@Composable
fun MyCallsSection() {
    val vm: CallsViewModel = viewModel()
    val state by vm.state.collectAsState()
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

    var showEntry by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("My Calls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = { showEntry = true }) { Text("+ Track a call") }
        }

        if (!state.configured) {
            Text(
                "Set your Signals service URL in Settings to see live option prices. You can still track a " +
                    "call now — its P/L just won't update until then.",
                style = MaterialTheme.typography.bodySmall,
                color = neutral,
            )
        }

        if (state.rows.isEmpty()) {
            Text(
                "No tracked calls yet. Buy a call on Fidelity, then tap \"+ Track a call\" — or use " +
                    "\"Track this\" on a stock's Play-with-calls card.",
                style = MaterialTheme.typography.bodySmall,
                color = neutral,
            )
        } else {
            state.rows.forEach { row ->
                CallRowItem(row) { detailId = row.position.id }
            }
        }
    }

    if (showEntry) {
        CallEntryDialog(
            prefill = null,
            onDismiss = { showEntry = false },
            onSave = { vm.add(it); showEntry = false },
        )
    }

    detailId?.let { id ->
        val row = state.rows.firstOrNull { it.position.id == id }
        if (row == null) {
            detailId = null
        } else {
            CallPositionDetailDialog(
                row = row,
                onDelete = { vm.delete(id); detailId = null },
                onDismiss = { detailId = null },
            )
        }
    }
}

/** One compact row: contract line + cost basis on the left; live P/L, DTE and an ITM/OTM chip right. */
@Composable
private fun CallRowItem(row: CallRow, onClick: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val p = row.position
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(contractLine(p), fontWeight = FontWeight.Bold)
            Text(
                "${p.contracts} contract${if (p.contracts != 1) "s" else ""} · cost ${usd(p.costBasis)}",
                style = MaterialTheme.typography.bodySmall,
                color = neutral,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val pl = row.unrealizedPl
            when {
                pl != null -> {
                    val up = pl >= 0
                    Text(
                        "${if (up) "+" else "−"}${usd(kotlin.math.abs(pl))}",
                        fontWeight = FontWeight.Medium,
                        color = if (up) GainGreen else LossRed,
                    )
                    row.unrealizedPlPct?.let { pct ->
                        Text(
                            "${if (pct >= 0) "▲" else "▼"} ${"%.1f".format(kotlin.math.abs(pct))}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (up) GainGreen else LossRed,
                        )
                    }
                }
                row.loading -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = neutral)
                else -> Text("—", color = neutral, fontWeight = FontWeight.Medium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${row.dte}d", style = MaterialTheme.typography.labelSmall, color = neutral)
                MoneynessChip(row.inTheMoney)
            }
        }
    }
    if (row.failed && !row.loading) {
        Text(
            "Couldn't re-price — showing last known / not available.",
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    }
}

/** ITM (green) / OTM (neutral) status chip; renders "?" when moneyness is unknown (no live quote). */
@Composable
private fun MoneynessChip(itm: Boolean?) {
    val (label, color) = when (itm) {
        true -> "ITM" to GainGreen
        false -> "OTM" to MaterialTheme.colorScheme.onSurfaceVariant
        null -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

/** Position detail: the money numbers, the plan (TP/stop/notes) and a Delete action. */
@Composable
private fun CallPositionDetailDialog(
    row: CallRow,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val p = row.position
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(contractLine(p)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Live P/L headline.
                val pl = row.unrealizedPl
                if (pl != null) {
                    val up = pl >= 0
                    Text(
                        "${if (up) "+" else "−"}${usd(kotlin.math.abs(pl))}" +
                            (row.unrealizedPlPct?.let { " (${if (it >= 0) "+" else "−"}${"%.1f".format(kotlin.math.abs(it))}%)" } ?: ""),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (up) GainGreen else LossRed,
                    )
                    Text("Unrealized P/L", style = MaterialTheme.typography.labelSmall, color = neutral)
                } else {
                    Text("P/L unavailable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (row.loading) "Re-pricing…" else "Couldn't re-price this contract right now.",
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                }

                StatRow("Cost basis (max loss)", usd(p.costBasis))
                StatRow("Current value", row.currentValue?.let { usd(it) } ?: "—")
                StatRow("Break-even", usd(p.breakeven))
                StatRow("Strike", usd(p.strike))
                StatRow("Contracts", p.contracts.toString())
                StatRow("Fill (premium / share)", usd(p.fillPrice))
                row.currentPrice?.let { StatRow("Current premium / share", usd(it)) }
                StatRow("Days to expiry", "${row.dte}d · ${shortExpiry(p.expiryIso)}")
                StatRow("Status", when (row.inTheMoney) { true -> "In the money"; false -> "Out of the money"; null -> "—" })
                StatRow("Bought on", shortExpiry(p.openDateIso))
                if (p.contractSymbol.isNotBlank()) StatRow("Contract", p.contractSymbol)

                if (p.takeProfitPct != null || p.stopPct != null) {
                    Text("Your plan", style = MaterialTheme.typography.labelLarge, color = neutral, modifier = Modifier.padding(top = 4.dp))
                    p.takeProfitPct?.let { StatRow("Take-profit", "+${"%.0f".format(it)}%") }
                    p.stopPct?.let { StatRow("Stop", "−${"%.0f".format(it)}%") }
                }
                p.notes?.takeIf { it.isNotBlank() }?.let {
                    Text("Notes", style = MaterialTheme.typography.labelLarge, color = neutral, modifier = Modifier.padding(top = 4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                Text(
                    "You bought this on Fidelity — the max loss is the whole premium. This is a tracker, not advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                Text(if (confirmDelete) "Confirm delete" else "Delete", color = LossRed)
            }
        },
        dismissButton = {
            TextButton(onClick = { if (confirmDelete) confirmDelete = false else onDismiss() }) {
                Text(if (confirmDelete) "Keep" else "Close")
            }
        },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
