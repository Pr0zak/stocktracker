package com.stocktracker.app.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.funds.FundsLogic
import com.stocktracker.app.ui.theme.Signal
import java.util.Locale

/**
 * FUND-2/6 — "What it holds": what the fund covers, its biggest holdings, and how it overlaps what
 * the user already owns (the before-you-buy read, right where the buying decision happens).
 *
 * Collapsed: what it covers, its three biggest holdings, and the overlap lines. Opened: all ten
 * holdings Yahoo lists, fund size, and what a round trip costs in the bid-ask gap.
 */
@Composable
internal fun FundHoldsCard(symbol: String, view: FundHoldsView, onOpenCompare: (List<String>) -> Unit) {
    val me = symbol.uppercase()
    val p = view.resp.funds[me] ?: return
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var open by rememberSaveable(me) { mutableStateOf(false) }
    val overlap = FundsLogic.beforeYouBuy(me, view.resp, view.ownedFunds, view.ownedStocks)
    val closest = view.ownedFunds.filter { it != me }
        .maxByOrNull { view.resp.pair(me, it)?.corr ?: Double.NEGATIVE_INFINITY }
    val tops = p.topHoldings.orEmpty()

    GlowCard(tint = null, spacing = 6.dp, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { open = !open }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("What it holds", style = MaterialTheme.typography.labelLarge, color = neutral)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "Hide holdings" else "Show holdings", tint = neutral)
        }
        Text(FundsLogic.covers(p), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        when {
            !p.profileOk -> Unit
            tops.isEmpty() && p.region == "crypto" -> Text("Holds the coin itself, not companies.",
                style = MaterialTheme.typography.bodySmall, color = neutral)
            tops.isNotEmpty() -> Text(
                "Biggest: " + tops.take(if (open) 10 else 3).joinToString(" · ") {
                    "${it.symbol} ${String.format(Locale.US, "%.1f", it.pct)}%"
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Default,
            )
        }
        if (open && p.top10Pct != null && tops.size >= 5) {
            Text("Its ${tops.size} biggest holdings are ${String.format(Locale.US, "%.0f", p.top10Pct)}% of the fund.",
                style = MaterialTheme.typography.labelSmall, color = neutral)
        }

        if (overlap.isNotEmpty()) {
            Text("Against what you own", style = MaterialTheme.typography.labelMedium, color = neutral,
                modifier = Modifier)
            overlap.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        } else if (view.ownedFunds.isEmpty() && view.ownedStocks.isEmpty()) {
            Text("You don't hold any funds or stocks for it to overlap with.",
                style = MaterialTheme.typography.bodySmall, color = neutral)
        }
        FundsLogic.directCryptoNote(listOf(p.groupId), view.heldCoins)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Signal)
        }
        FundsLogic.smallFundWarning(p.netAssets)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Signal)
        }

        if (open) {
            FundsLogic.size(p.netAssets)?.let {
                Text("Fund size: $it", style = MaterialTheme.typography.bodySmall, color = neutral)
            }
            (FundsLogic.tradingCost(p.spreadPct, p.spreadAt, p.isMutualFund)
                ?: "Trading gap: measured during market hours.").let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = neutral)
            }
            if (p.sectors.orEmpty().size > 3) {
                Text("All sectors: " + p.sectors.orEmpty().joinToString(" · ") {
                    "${it.label} ${String.format(Locale.US, "%.0f", it.pct)}%"
                }, style = MaterialTheme.typography.labelSmall, color = neutral)
            }
            Text("Holdings are the 10 largest, all Yahoo lists.", style = MaterialTheme.typography.labelSmall, color = neutral)
        }
        TextButton(onClick = { onOpenCompare(listOfNotNull(me, closest)) }) {
            Text(if (closest != null) "Compare with your $closest" else "Compare with other funds")
        }
    }
}
