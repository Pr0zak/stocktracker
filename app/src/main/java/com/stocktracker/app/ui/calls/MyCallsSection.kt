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
import com.stocktracker.app.data.model.RealizedPnl
import com.stocktracker.app.data.model.RiskMultiple
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
            // Latch on first press. The dialog unmounts asynchronously, so a fast double-tap fired
            // onConfirm twice — writing two closed-call records and double-counting the realized P&L
            // that drives the total and win-rate card.
            var submitting by remember { mutableStateOf(false) }
            TextButton(
                enabled = exit != null && exit >= 0.0 && !submitting,
                onClick = { submitting = true; onConfirm(exit!!) },
            ) {
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
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
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
                "No expectancy in R — none of these ${r.closedCount} closes could be scored. R needs the stop " +
                    "the position was opened with, and that can't be recovered after the close.",
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
                    // R prints only when the close carried the stop it was opened with. A position with
                    // no stop recorded shows the percentage alone — never "0.0R", which would claim it
                    // finished exactly at its risk.
                    val r = RiskMultiple.rFor(c)
                    val rSuffix = r?.let { " · ${RiskMultiple.format(it)}" } ?: ""
                    Text(
                        "${if (pct >= 0) "▲" else "▼"} ${"%.1f".format(abs(pct))}%$rSuffix",
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
