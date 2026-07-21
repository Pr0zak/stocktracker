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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.CallOutcome
import com.stocktracker.app.data.model.ClosedCallPosition
import com.stocktracker.app.data.model.RealizedPnl
import com.stocktracker.app.ui.ideas.usd
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import kotlin.math.abs

/**
 * "My Calls" (OC-3/OC-5) — the manually-tracked long-call positions, shown as a section on the
 * Portfolio screen with a "+ Track a call" button and a "History" affordance for closed positions.
 * Each row re-prices live via /option_quote; a failed quote (market closed / contract gone) keeps the
 * last-known value rather than dropping the row. Tapping a row opens its detail (cost basis, current
 * value, break-even, DTE, TP/stop/notes) with close-out actions (sold / exercised / expired) + Delete.
 */
@Composable
fun MyCallsSection() {
    val vm: CallsViewModel = viewModel()
    val state by vm.state.collectAsState()
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

    var showEntry by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.closed.isNotEmpty()) {
                    TextButton(onClick = { showHistory = true }) { Text("History") }
                }
                TextButton(onClick = { showEntry = true }) { Text("+ Track a call") }
            }
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
                onCloseSold = { exit -> vm.closeSold(row.position, exit); detailId = null },
                onExercised = { vm.markExercised(row.position); detailId = null },
                onExpired = { vm.markExpiredWorthless(row.position); detailId = null },
                onDelete = { vm.delete(id); detailId = null },
                onDismiss = { detailId = null },
            )
        }
    }

    if (showHistory) {
        ClosedCallsDialog(closed = state.closed, onDismiss = { showHistory = false })
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
                        "${if (up) "+" else "−"}${usd(abs(pl))}",
                        fontWeight = FontWeight.Medium,
                        color = if (up) GainGreen else LossRed,
                    )
                    row.unrealizedPlPct?.let { pct ->
                        Text(
                            "${if (pct >= 0) "▲" else "▼"} ${"%.1f".format(abs(pct))}%",
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
    PillChip(label, color)
}

/** A small rounded status pill in [color]. */
@Composable
private fun PillChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

/** Which confirm-and-record action the user tapped on the detail dialog. */
private enum class CloseAction { EXERCISE, EXPIRE, DELETE }

/** Position detail: the money numbers, the plan (TP/stop/notes) and the close-out actions + Delete. */
@Composable
private fun CallPositionDetailDialog(
    row: CallRow,
    onCloseSold: (Double) -> Unit,
    onExercised: () -> Unit,
    onExpired: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val p = row.position
    var showSellPrompt by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<CloseAction?>(null) }

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
                        "${if (up) "+" else "−"}${usd(abs(pl))}" +
                            (row.unrealizedPlPct?.let { " (${if (it >= 0) "+" else "−"}${"%.1f".format(abs(it))}%)" } ?: ""),
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

                // --- Close-out actions (OC-5) ---
                Text(
                    "Close this position",
                    style = MaterialTheme.typography.labelLarge,
                    color = neutral,
                    modifier = Modifier.padding(top = 10.dp),
                )
                OutlinedButton(onClick = { showSellPrompt = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sold to close — record P/L")
                }
                OutlinedButton(onClick = { pending = CloseAction.EXERCISE }, modifier = Modifier.fillMaxWidth()) {
                    Text("Exercised — I bought the shares")
                }
                OutlinedButton(onClick = { pending = CloseAction.EXPIRE }, modifier = Modifier.fillMaxWidth()) {
                    Text("Expired worthless")
                }
                TextButton(onClick = { pending = CloseAction.DELETE }) {
                    Text("Delete (discard, no record)", color = LossRed)
                }

                Text(
                    "You bought this on Fidelity — the max loss is the whole premium. Selling to close is the " +
                        "normal exit. This is a tracker, not advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    if (showSellPrompt) {
        SellToCloseDialog(
            row = row,
            onConfirm = { exit -> onCloseSold(exit) },
            onDismiss = { showSellPrompt = false },
        )
    }
    pending?.let { action ->
        ConfirmCloseDialog(
            action = action,
            row = row,
            onConfirm = {
                when (action) {
                    CloseAction.EXERCISE -> onExercised()
                    CloseAction.EXPIRE -> onExpired()
                    CloseAction.DELETE -> onDelete()
                }
            },
            onDismiss = { pending = null },
        )
    }
}

/** Prompt for the sell premium/share (defaults to the live re-price) and previews the realized P/L. */
@Composable
private fun SellToCloseDialog(
    row: CallRow,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val p = row.position
    var text by remember { mutableStateOf(row.currentPrice?.let { plainNum(it) } ?: "") }
    val exit = text.trim().toDoubleOrNull()
    val preview = exit?.let { RealizedPnl.forSale(p.fillPrice, it, p.contracts) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sold to close") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter the premium PER SHARE you sold the option for. Selling to close is the beginner-normal " +
                        "exit — you take the cash and never risk exercising into shares.",
                    style = MaterialTheme.typography.bodySmall,
                    color = neutral,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Sell price (premium / share)") },
                    prefix = { Text("$") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                row.currentPrice?.let {
                    Text("Live premium now ${usd(it)}", style = MaterialTheme.typography.labelSmall, color = neutral)
                }
                if (preview != null) {
                    val up = preview.pnl >= 0
                    Text(
                        "Realized ${if (up) "+" else "−"}${usd(abs(preview.pnl))} " +
                            "(${if (preview.pct >= 0) "+" else "−"}${"%.1f".format(abs(preview.pct))}%)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (up) GainGreen else LossRed,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = exit != null && exit >= 0.0, onClick = { onConfirm(exit!!) }) {
                Text("Record sale")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirm-step dialog for the exercise / expire / delete close-outs, each with beginner framing. */
@Composable
private fun ConfirmCloseDialog(
    action: CloseAction,
    row: CallRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val p = row.position
    val shares = 100 * p.contracts

    val title: String
    val body: String
    val confirmLabel: String
    val danger: Boolean
    when (action) {
        CloseAction.EXERCISE -> {
            title = "Mark exercised"
            body = "Exercising turns this option into $shares shares of ${p.symbol.uppercase()} at the " +
                "${usd(p.strike)} strike — that costs ${usd(p.strike * 100.0 * p.contracts)} to buy the shares. " +
                "Your cost basis becomes ${usd(p.breakeven)}/share (strike + the premium you paid). We record " +
                "this as exercised and don't show a separate option P/L, since the value now lives in the shares."
            confirmLabel = "Confirm exercised"
            danger = false
        }
        CloseAction.EXPIRE -> {
            title = "Mark expired worthless"
            body = "The option expired with no value — you lose the whole premium: −${usd(p.costBasis)} (−100%). " +
                "Record it in your history?"
            confirmLabel = "Confirm loss"
            danger = true
        }
        CloseAction.DELETE -> {
            title = "Delete this position?"
            body = "Removes it from your tracker without recording any result. Use this only if you entered it " +
                "by mistake."
            confirmLabel = "Delete"
            danger = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodySmall, color = neutral) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (danger) LossRed else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Closed-calls history (OC-5): a summary card + one row per closed position with an outcome chip. */
@Composable
private fun ClosedCallsDialog(closed: List<ClosedCallPosition>, onDismiss: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val summary = remember(closed) { RealizedPnl.summarize(closed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Closed calls") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ClosedSummaryCard(summary)
                if (closed.isEmpty()) {
                    Text("No closed calls yet.", style = MaterialTheme.typography.bodySmall, color = neutral)
                } else {
                    closed.forEach { ClosedRow(it) }
                }
                Text(
                    "Win rate and total cover sold + expired only. Exercised calls roll their value into the " +
                        "shares you now own, so they aren't counted here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The realized-P&L summary: total, win rate, count — the app's card style. */
@Composable
private fun ClosedSummaryCard(s: RealizedPnl.Summary) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val up = s.totalRealized >= 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "${if (up) "+" else "−"}${usd(abs(s.totalRealized))}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (up) GainGreen else LossRed,
        )
        Text("Total realized P&L", style = MaterialTheme.typography.labelSmall, color = neutral)
        Text(
            "${s.closedCount} closed · win rate ${"%.0f".format(s.winRatePct)}% (${s.wins}/${s.counted})",
            style = MaterialTheme.typography.bodySmall,
            color = neutral,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** One closed position: contract line + outcome chip on the left; realized P/L $/% (or shares) right. */
@Composable
private fun ClosedRow(c: ClosedCallPosition) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(closedContractLine(c), fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutcomeChip(c.outcome)
                Text("closed ${shortExpiry(c.closeDateIso)}", style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (c.outcome == CallOutcome.EXERCISED) {
                Text("${c.exercisedShares} sh", fontWeight = FontWeight.Medium, color = neutral)
                Text("now held", style = MaterialTheme.typography.labelSmall, color = neutral)
            } else {
                val pnl = c.realizedPnl ?: 0.0
                val up = pnl >= 0
                Text(
                    "${if (up) "+" else "−"}${usd(abs(pnl))}",
                    fontWeight = FontWeight.Medium,
                    color = if (up) GainGreen else LossRed,
                )
                c.realizedPnlPct?.let { pct ->
                    Text(
                        "${if (pct >= 0) "▲" else "▼"} ${"%.1f".format(abs(pct))}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (up) GainGreen else LossRed,
                    )
                }
            }
        }
    }
}

/** Outcome chip — colored by outcome type (the P/L number carries the gain/loss color separately). */
@Composable
private fun OutcomeChip(outcome: CallOutcome) {
    val (label, color) = when (outcome) {
        CallOutcome.SOLD -> "SOLD" to MaterialTheme.colorScheme.primary
        CallOutcome.EXERCISED -> "EXERCISED" to MaterialTheme.colorScheme.tertiary
        CallOutcome.EXPIRED -> "EXPIRED" to LossRed
    }
    PillChip(label, color)
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

/** "UNH $420C Sep 17 '26" for a closed position (mirrors contractLine for CallPosition). */
private fun closedContractLine(c: ClosedCallPosition): String {
    val k = if (c.strike % 1.0 == 0.0) c.strike.toLong().toString() else "%.2f".format(c.strike)
    return "${c.symbol.uppercase()} \$${k}C ${shortExpiry(c.expiryIso)}"
}

private fun plainNum(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
