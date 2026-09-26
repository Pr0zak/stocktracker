package com.stocktracker.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.FundCost
import com.stocktracker.app.data.remote.FundCostLookup
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.theme.GainGreen

/**
 * FC-1 — "What it costs": a fund's yearly fee in dollars, and the funds that hold the same thing.
 *
 * Collapsed, it is three facts, one line each: the fee per $10,000, what the user's own holding pays,
 * and the cheapest look-alike ETF and Fidelity fund. Expanded, it is the whole comparison, cheapest
 * first, each row tappable into that fund's own screen. Look-alikes are measured on the server, not
 * guessed from names (see fund_cost.py in the signals backend).
 *
 * [shares] and [price] size the "your holding" lines; either missing and those lines are left out
 * rather than computed from a guess.
 */
@Composable
internal fun FundCostCard(
    lookup: FundCostLookup,
    shares: Double?,
    price: Double?,
    onRetry: () -> Unit,
    onOpenFund: (FundCost) -> Unit,
) {
    val f = lookup.fund
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var open by rememberSaveable(f.symbol) { mutableStateOf(false) }
    val fee = f.expenseRatioPct
    val group = f.group
    val rows = group?.funds.orEmpty()
    val value = if (shares != null && shares > 0.0 && price != null && price > 0.0) shares * price else null
    val lowest = FundCostText.isLowest(f, rows)
    val cheapest = FundCostText.cheaper(f, rows).firstOrNull()

    GlowCard(
        tint = null,
        spacing = 6.dp,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { open = !open },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("What it costs", style = MaterialTheme.typography.labelLarge, color = neutral)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (lowest) Pill("Lowest fee", GainGreen)
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Hide the fund comparison" else "Show the fund comparison",
                    tint = neutral,
                )
            }
        }
        Text(
            FundCostText.headline(fee),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (fee == null) {
            Text(FundCostText.unknownReason(f, lookup.live), style = MaterialTheme.typography.bodySmall, color = neutral)
            if (!lookup.live) TextButton(onClick = onRetry) { Text("Retry") }
        }
        if (fee != null && value != null) {
            Text(FundCostText.holdingLine(value, fee), style = MaterialTheme.typography.bodyMedium)
        }
        FundCostText.compareLine(f, rows)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (lowest) GainGreen else neutral)
        }

        if (open) {
            if (fee != null && fee > 0.0) {
                Text(FundCostText.explainer(fee), style = MaterialTheme.typography.bodySmall, color = neutral)
            }
            if (group == null) {
                Text("No look-alike on the comparison list.", style = MaterialTheme.typography.bodySmall, color = neutral)
            } else {
                Text(
                    "Funds holding ${group.label} · a year per \$10,000",
                    style = MaterialTheme.typography.labelMedium,
                    color = neutral,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rows.forEach { r ->
                        val self = r.symbol == f.symbol
                        FundRow(r, self, onClick = if (self) null else ({ onOpenFund(r) }))
                    }
                }
                if (value != null && fee != null && cheapest != null) {
                    FundCostText.savingLine(value, fee, cheapest)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = GainGreen)
                    }
                }
                if (cheapest != null) {
                    // Real money: a switch is a sale, and a sale in a taxable account is a tax bill on
                    // the gain. The fee gap is a yearly trickle; the tax is due all at once.
                    Text(
                        "Selling to switch can mean tax on gains, so the gap matters most for new money.",
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                }
                group.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = neutral) }
            }
            val source = FundCostText.sourceLine(rows.ifEmpty { listOf(f) }, lookup.live)
            if (source.isNotBlank()) {
                Text(source, style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
    }
}

/** One fund in the comparison: ticker, short name, the small facts that change a decision, the fee. */
@Composable
private fun FundRow(r: FundCost, self: Boolean, onClick: (() -> Unit)?) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (self) primary.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    r.symbol,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    FundCostText.shortName(r.name),
                    style = MaterialTheme.typography.labelMedium,
                    color = neutral,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val tags = FundCostText.tags(r, self)
            if (tags.isNotEmpty()) {
                Text(tags, style = MaterialTheme.typography.labelSmall, color = if (r.fidelity) primary else neutral)
            }
        }
        Text(
            FundCostText.rowFee(r),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}
