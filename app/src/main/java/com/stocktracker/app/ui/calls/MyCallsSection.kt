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
import com.stocktracker.app.data.model.ExitTaxonomy
import com.stocktracker.app.data.model.PositionSide
import com.stocktracker.app.data.model.RealizedPnl
import com.stocktracker.app.ui.detail.ageAgo
import com.stocktracker.app.data.model.RiskMultiple
import com.stocktracker.app.ui.ideas.usd
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.NumberSmall
import com.stocktracker.app.ui.theme.PriceSmall
import com.stocktracker.app.ui.theme.PriceLarge
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
                "No tracked positions yet. Trade an option on Fidelity, then tap \"+ Track a call\" — or use " +
                    "\"Track this\" on a stock's calls / puts / covered-call card.",
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
                onAssigned = { vm.markAssigned(row.position); detailId = null },
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
                "${p.contracts} contract${if (p.contracts != 1) "s" else ""} · " +
                    "${if (p.side == PositionSide.SHORT) "credit" else "cost"} ${usd(p.costBasis)}",
                style = MaterialTheme.typography.bodySmall,
                color = neutral,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val pl = row.unrealizedPl
            // A stale figure does not get to wear the live figure's colours. Green means "this
            // position is up right now"; on a row whose last re-price failed, nobody knows that.
            // The number is still worth showing — it is the last thing that was true — but in the
            // muted ink, with its age spelled out below the row.
            val stale = row.failed && !row.loading
            val plColor = { up: Boolean -> if (stale) neutral else if (up) GainGreen else LossRed }
            when {
                pl != null -> {
                    val up = pl >= 0
                    Text(
                        "${if (up) "+" else "−"}${usd(abs(pl))}",
                        style = PriceSmall,
                        color = plColor(up),
                    )
                    row.unrealizedPlPct?.let { pct ->
                        Text(
                            "${if (pct >= 0) "▲" else "▼"} ${"%.1f".format(abs(pct))}%",
                            style = NumberSmall,
                            color = plColor(up),
                        )
                    }
                }
                row.loading -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = neutral)
                else -> Text("—", color = neutral, fontWeight = FontWeight.Medium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${row.dte}d", style = NumberSmall, color = neutral)
                MoneynessChip(row.inTheMoney)
            }
        }
    }
    if (row.failed && !row.loading) {
        // Name the age, not just the failure. "Showing last known" left the reader to guess whether
        // that meant a minute ago or last Tuesday — on a decaying contract those are different
        // positions. Where the quote carries no usable stamp the line says so rather than inventing
        // a reassuring one.
        val age = ageAgo(row.quote?.asOf)
        Text(
            when {
                row.unrealizedPl == null -> "Couldn't re-price — no P/L for this contract yet."
                age != null -> "Couldn't re-price — the P/L above is from $age."
                else -> "Couldn't re-price — the P/L above is the last one we got, age unknown."
            },
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    }
}

/** ITM (green) / OTM (neutral) status chip; renders "?" when moneyness is unknown (no live quote). */
@Composable
private fun MoneynessChip(itm: Boolean?) {
    val (label, color) = when (itm) {
        true -> "In the money" to GainGreen
        false -> "Out of the money" to MaterialTheme.colorScheme.onSurfaceVariant
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
private enum class CloseAction { EXERCISE, ASSIGN, EXPIRE, DELETE }

/** Position detail: the money numbers, the plan (TP/stop/notes) and the close-out actions + Delete. */
@Composable
private fun CallPositionDetailDialog(
    row: CallRow,
    onCloseSold: (Double) -> Unit,
    onExercised: () -> Unit,
    onAssigned: () -> Unit,
    onExpired: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val p = row.position
    val isShort = p.side == PositionSide.SHORT
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
                    val stale = row.failed && !row.loading
                    Text(
                        "${if (up) "+" else "−"}${usd(abs(pl))}" +
                            (row.unrealizedPlPct?.let { " (${if (it >= 0) "+" else "−"}${"%.1f".format(abs(it))}%)" } ?: ""),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (stale) neutral else if (up) GainGreen else LossRed,
                    )
                    // The age rides with the number even on a SUCCESSFUL re-price. This dialog is
                    // the screen someone opens to decide whether to close a position, and an option
                    // premium ten minutes old is a different premium. Saying so costs one line.
                    val age = ageAgo(row.quote?.asOf)
                    Text(
                        "Unrealized P/L" + when {
                            stale && age != null -> " · not re-priced, this is from $age"
                            stale -> " · not re-priced, age unknown"
                            age != null -> " · priced $age"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                } else {
                    Text("P/L unavailable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (row.loading) "Re-pricing…" else "Couldn't re-price this contract right now.",
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                }

                // A SHORT's [CallPosition.costBasis] is a credit collected, not a cost, and its max
                // loss is either uncapped (short call) or (strike × 100 × contracts − credit, short
                // put) — neither of which is this number, so the label says what it actually is.
                StatRow(if (isShort) "Premium collected" else "Cost basis (max loss)", usd(p.costBasis))
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
                    Text(if (isShort) "Bought to close — record P/L" else "Sold to close — record P/L")
                }
                if (isShort) {
                    OutlinedButton(onClick = { pending = CloseAction.ASSIGN }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (p.type.equals("put", ignoreCase = true)) "Assigned — I bought the shares"
                            else "Assigned — my shares were called away",
                        )
                    }
                } else {
                    OutlinedButton(onClick = { pending = CloseAction.EXERCISE }, modifier = Modifier.fillMaxWidth()) {
                        Text("Exercised — I bought the shares")
                    }
                }
                OutlinedButton(onClick = { pending = CloseAction.EXPIRE }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isShort) "Expired worthless (kept the premium)" else "Expired worthless")
                }
                TextButton(onClick = { pending = CloseAction.DELETE }) {
                    Text("Delete (discard, no record)", color = LossRed)
                }

                Text(
                    if (isShort) {
                        "You sold this on Fidelity — you keep the premium unless assigned. Buying to close is " +
                            "the normal exit. This is a tracker, not advice."
                    } else {
                        "You bought this on Fidelity — the max loss is the whole premium. Selling to close is " +
                            "the normal exit. This is a tracker, not advice."
                    },
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
                    CloseAction.ASSIGN -> onAssigned()
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
    val isShort = p.side == PositionSide.SHORT
    var text by remember { mutableStateOf(row.currentPrice?.let { plainNum(it) } ?: "") }
    val exit = text.trim().toDoubleOrNull()
    val preview = exit?.let { RealizedPnl.forSale(p.fillPrice, it, p.contracts, p.side) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isShort) "Bought to close" else "Sold to close") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (isShort) {
                        "Enter the premium PER SHARE you paid to buy the option back. Buying to close is the " +
                            "beginner-normal exit — you lock in the difference and never risk being assigned."
                    } else {
                        "Enter the premium PER SHARE you sold the option for. Selling to close is the " +
                            "beginner-normal exit — you take the cash and never risk exercising into shares."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = neutral,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(if (isShort) "Buy-back price (premium / share)" else "Sell price (premium / share)") },
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
            // Latch on first press. The dialog unmounts asynchronously, so a fast double-tap fired
            // onConfirm twice — writing two closed-call records and double-counting the realized P&L
            // that drives the total and win-rate card.
            var submitting by remember { mutableStateOf(false) }
            TextButton(
                enabled = exit != null && exit >= 0.0 && !submitting,
                onClick = { submitting = true; onConfirm(exit!!) },
            ) {
                Text(if (isShort) "Record buy-back" else "Record sale")
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
                "this as exercised and don't show a separate option P/L, since the value now lives in the shares. " +
                // ONE confirmation line (MONEY-2): this tap also appends those $shares shares, dated
                // today, as a lot on your ${p.symbol.uppercase()} watchlist holding — not a second,
                // silent write later.
                "Confirming also adds those $shares shares to your ${p.symbol.uppercase()} portfolio holding, " +
                "dated today, at that cost basis."
            confirmLabel = "Confirm exercised"
            danger = false
        }
        CloseAction.ASSIGN -> {
            val isPut = p.type.equals("put", ignoreCase = true)
            if (isPut) {
                val perShareBasis = p.strike - p.fillPrice
                title = "Mark assigned"
                body = "Assignment turns this put into $shares shares of ${p.symbol.uppercase()} you must buy " +
                    "at the ${usd(p.strike)} strike — that costs ${usd(p.strike * 100.0 * p.contracts)}, offset " +
                    "by the ${usd(p.costBasis)} premium you already collected. Your cost basis becomes " +
                    "${usd(perShareBasis)}/share (strike − the premium you collected). We record this as " +
                    "assigned and don't show a separate option P/L, since the value now lives in the shares. " +
                    "Confirming also adds those $shares shares to your ${p.symbol.uppercase()} portfolio " +
                    "holding, dated today, at that cost basis."
            } else {
                title = "Mark assigned"
                body = "Assignment sells $shares shares of ${p.symbol.uppercase()} away at the " +
                    "${usd(p.strike)} strike (${usd(p.strike * 100.0 * p.contracts)} proceeds), on top of the " +
                    "${usd(p.costBasis)} premium you already collected when you sold the call. We record this " +
                    "as assigned and don't show a separate option P/L. Confirming also removes those $shares " +
                    "shares from your ${p.symbol.uppercase()} portfolio holding, dated today."
            }
            confirmLabel = "Confirm assigned"
            danger = false
        }
        CloseAction.EXPIRE -> {
            title = "Mark expired worthless"
            body = if (p.side == PositionSide.SHORT) {
                "The option expired with no value — you keep the whole premium you collected: " +
                    "+${usd(p.costBasis)} (+100%). Record it in your history?"
            } else {
                "The option expired with no value — you lose the whole premium: −${usd(p.costBasis)} (−100%). " +
                    "Record it in your history?"
            }
            confirmLabel = if (p.side == PositionSide.SHORT) "Confirm" else "Confirm loss"
            danger = p.side != PositionSide.SHORT
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
    val rStats = remember(closed) { RiskMultiple.aggregate(closed) }
    val exits = remember(closed) { ExitTaxonomy.summarize(closed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Closed calls") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ClosedSummaryCard(summary, rStats, exits)
                if (closed.isEmpty()) {
                    Text("No closed calls yet.", style = MaterialTheme.typography.bodySmall, color = neutral)
                } else {
                    closed.forEach { ClosedRow(it) }
                }
                Text(
                    "Win rate and total cover sold + expired only. Exercised/assigned positions roll their " +
                        "value into the shares, so they aren't counted here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The realized-P&L summary: total, win rate, count — the app's card style, plus the R track record. */
@Composable
private fun ClosedSummaryCard(s: RealizedPnl.Summary, r: RiskMultiple.Aggregate, x: ExitTaxonomy.Record) {
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
            style = PriceLarge,
            color = if (up) GainGreen else LossRed,
        )
        Text("Total realized P&L", style = MaterialTheme.typography.labelSmall, color = neutral)
        // Deliberately a COUNT, not the percentage this line used to lead with (SWT-7). Its population
        // is "every close with an option P/L" — which includes trades that had no plan to be measured
        // against — so as a percentage it sat above the qualified pair below looking like the headline
        // win rate while answering a different question. The rates on this card now all arrive with
        // their denominator and their opposite number.
        Text(
            "${s.closedCount} closed · ${s.wins} of ${s.counted} finished green",
            style = MaterialTheme.typography.bodySmall,
            color = neutral,
            modifier = Modifier.padding(top = 4.dp),
        )
        RTrackRecord(r)
        ExitBreakdown(x)
    }
}

/**
 * How the closed trades ENDED, and the two win rates that describe it (SWT-7).
 *
 * THE RULE THIS COMPOSABLE ENFORCES: the hard win rate (reached the planned target) and the profitable
 * exit rate (finished green by any route) are rendered on ONE line, over ONE visible denominator, and
 * neither is ever drawn without the other. They can differ enormously — the reference this came from
 * published 12.5% and 65.3% for the same trades — and the flattering one is the one a reader quotes.
 *
 * Under [ExitTaxonomy.MIN_CLASSIFIED_FOR_RATES] classified closes the percentages are NOT drawn at all.
 * "1 of 2 reached target" is the honest sentence there; "50%" is a confident-sounding claim about two
 * trades. Nulls render as nothing — no dashes standing in for a rate, no 0.0R for a bucket that could
 * not be scored.
 */
@Composable
private fun ExitBreakdown(x: ExitTaxonomy.Record) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    if (x.closedCount == 0) return

    Text(
        "How they ended",
        style = MaterialTheme.typography.labelSmall,
        color = neutral,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 10.dp),
    )

    val hard = x.hardWinRatePct
    val profitable = x.profitableExitRatePct
    if (hard == null || profitable == null) {
        // Nothing measurable against a plan. Saying so beats printing 0%, which would read as "every
        // trade was checked and none won" over a history that never made that claim.
        Text(
            "No win rate yet — none of these ${x.closedCount} closes could be measured against the plan it " +
                "was opened with.",
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    } else {
        if (x.smallSample) {
            Text(
                "${x.targetHits} of ${x.classified} reached target · ${x.greenExits} of ${x.classified} finished green",
                style = MaterialTheme.typography.bodySmall,
                color = neutral,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Too few closes to call either a rate — under ${ExitTaxonomy.MIN_CLASSIFIED_FOR_RATES} " +
                    "these are counts, not a win rate.",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        } else {
            Text(
                "Hit target ${"%.1f".format(hard)}% · finished green ${"%.1f".format(profitable)}% " +
                    "(of ${x.classified} classified)",
                style = MaterialTheme.typography.bodySmall,
                color = neutral,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Two rates, one denominator. The second counts every green exit, including the ones that " +
                    "never reached the target — it is always the kinder number.",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
    }

    x.occupiedBuckets.forEach { b ->
        // Average R prints only where it exists. A bucket of unscoreable closes shows its count alone.
        val rSuffix = b.avgR?.let { " · ${RiskMultiple.format(it)} avg" } ?: ""
        Text(
            "${ExitTaxonomy.label(b.kind)} ${b.count}$rSuffix",
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    }

    if (x.bucket(ExitTaxonomy.ExitKind.EXPIRY).count > 0) {
        Text(
            "Expired is kept apart from stopped on purpose: a stop is the plan working, an expiry at \$0 is " +
                "the plan abandoned — and it usually costs about twice the risk the stop defined.",
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    }
}

/**
 * Expectancy in R, with the coverage that qualifies it. The scored/unscoreable split is printed on the
 * same line as the average on purpose: "+0.4R avg" over 4 of 30 closes is not this account's
 * expectancy, and the caveat has to travel with the number rather than sit somewhere it can be missed.
 * Nothing is printed as an R when nothing could be scored — no "0.0R" stand-in.
 */
@Composable
private fun RTrackRecord(r: RiskMultiple.Aggregate) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    if (r.scored == 0) {
        if (r.closedCount > 0) {
            Text(
                "No average result — none of these ${r.closedCount} closes could be scored. Results are measured " +
                    "against the stop the position was opened with, and that can't be recovered after the close.",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        return
    }
    val avg = r.avgR ?: return
    Text(
        "${RiskMultiple.format(avg)} avg · scored ${r.scored} of ${r.closedCount}",
        style = MaterialTheme.typography.bodySmall,
        color = if (avg >= 0) GainGreen else LossRed,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (r.smallSample) {
        Text(
            "Small sample — under ${RiskMultiple.MIN_SCORED_FOR_EXPECTANCY} scored closes this is noise, not an edge.",
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    }
    if (r.unscoreable > 0) {
        // Name BOTH reasons a close goes unscored. Labelling the bucket "no stop recorded" would be a
        // small lie about the exercised ones, which did record a stop but have no option-leg exit.
        Text(
            "${r.unscoreable} not scored — closed without the stop it was opened with, or exercised.",
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
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
            val calledAway = c.outcome == CallOutcome.ASSIGNED && c.type.equals("call", ignoreCase = true)
            if (c.outcome == CallOutcome.EXERCISED || c.outcome == CallOutcome.ASSIGNED) {
                Text("${c.exercisedShares} sh", style = PriceSmall, color = neutral)
                Text(if (calledAway) "called away" else "now held", style = MaterialTheme.typography.labelSmall, color = neutral)
            } else {
                val pnl = c.realizedPnl ?: 0.0
                val up = pnl >= 0
                Text(
                    "${if (up) "+" else "−"}${usd(abs(pnl))}",
                    style = PriceSmall,
                    color = if (up) GainGreen else LossRed,
                )
                c.realizedPnlPct?.let { pct ->
                    // R prints only when the close carried the stop it was opened with. A position with
                    // no stop recorded shows the percentage alone — never "0.0R", which would claim it
                    // finished exactly at its risk.
                    val r = RiskMultiple.rFor(c)
                    val rSuffix = r?.let { " · ${RiskMultiple.format(it)}" } ?: ""
                    Text(
                        "${if (pct >= 0) "▲" else "▼"} ${"%.1f".format(abs(pct))}%$rSuffix",
                        style = NumberSmall,
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
        CallOutcome.ASSIGNED -> "ASSIGNED" to MaterialTheme.colorScheme.tertiary
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
    val optChar = if (c.type.equals("put", ignoreCase = true)) "P" else "C"
    return "${c.symbol.uppercase()} \$${k}$optChar ${shortExpiry(c.expiryIso)}"
}

private fun plainNum(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
