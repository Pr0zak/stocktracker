package com.stocktracker.app.ui.ideas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.EntryPlan
import com.stocktracker.app.di.ServiceLocator

/**
 * "Ideas" — deploy free cash across the watchlist. The analyst sees every candidate at once, picks
 * the top few for NEW money, and spreads the cash across them with entry zones and share counts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeasScreen(onOpenDetail: (Asset) -> Unit) {
    val vm: IdeasViewModel = viewModel()
    val state by vm.state.collectAsState()
    val watchlist by ServiceLocator.watchlistStore.watchlist.collectAsState(initial = emptyList())

    // Resolve a pick's symbol back to a watchlist asset (crypto picks arrive in Yahoo "BTC-USD" form).
    fun assetFor(symbol: String): Asset? = watchlist.firstOrNull {
        val s = it.symbol.uppercase()
        s == symbol.uppercase() || (it.type == AssetType.CRYPTO && "$s-USD" == symbol.uppercase())
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Ideas") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.enabled) {
                Text(
                    "Set your Signals service URL in Settings → AI analyst to get deployment ideas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                "How much free cash do you want to put to work? The AI analyst compares your whole " +
                    "watchlist and spreads it across its best entries.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.cashText,
                onValueChange = vm::setCash,
                label = { Text("Investable cash") },
                prefix = { Text("$") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = vm::getIdeas, enabled = !state.loading) { Text("Get ideas") }
                FilterChip(
                    selected = state.deep,
                    onClick = { vm.setDeep(!state.deep) },
                    label = { Text("Deep model") },
                )
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Comparing your watchlist…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            state.result?.let { r ->
                if (r.overview.isNotBlank()) {
                    Text(r.overview, style = MaterialTheme.typography.bodyMedium)
                }
                if (r.picks.isEmpty()) {
                    Text(
                        "No compelling entries right now — the analyst recommends keeping the cash uninvested.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                r.picks.forEach { pick ->
                    PickCard(pick, onClick = { assetFor(pick.symbol)?.let(onOpenDetail) })
                }
                if (r.passed.isNotEmpty()) {
                    Text(
                        "Passed on: ${r.passed.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val cachedTag = if (r.cached) " · cached" else ""
                Text(
                    "${r.model} · ${r.considered} candidates$cachedTag · decision support, not advice",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val Buy = Color(0xFF16A34A)
private val Pullback = Color(0xFFD97706)
private val Sell = Color(0xFFDC2626)

internal fun planActionColor(action: String, neutral: Color): Color = when (action) {
    "buy_now" -> Buy
    "buy_on_pullback" -> Pullback
    "avoid" -> Sell
    else -> neutral
}

internal fun planActionLabel(action: String): String = when (action) {
    "buy_now" -> "BUY NOW"
    "buy_on_pullback" -> "BUY THE DIP"
    "wait" -> "WAIT"
    "avoid" -> "AVOID"
    else -> action.replace('_', ' ').uppercase()
}

/** Compact money: whole dollars unless cents matter. */
internal fun usd(v: Double): String =
    if (v % 1.0 == 0.0) "$%,d".format(v.toLong()) else "$%,.2f".format(v)

internal fun sharesText(v: Double): String =
    if (v % 1.0 == 0.0) "%,d".format(v.toLong()) else "%,.6f".format(v).trimEnd('0').trimEnd('.')

@Composable
private fun PickCard(pick: EntryPlan, onClick: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val c = planActionColor(pick.action, neutral)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pick.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .background(c.copy(alpha = 0.16f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    planActionLabel(pick.action),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = c,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Conviction ${pick.conviction}/100", style = MaterialTheme.typography.labelMedium, color = neutral)
            Text(
                "${sharesText(pick.suggestedShares)} sh · ${usd(pick.allocationUsd)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(neutral.copy(alpha = 0.18f), RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((pick.conviction / 100f).coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(c, RoundedCornerShape(3.dp)),
            )
        }
        Text(
            "Entry ${usd(pick.entryLow)}–${usd(pick.entryHigh)} · stop ${usd(pick.stop)} · target ${usd(pick.target)}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (pick.timing.isNotBlank()) {
            Text("When: ${pick.timing}", style = MaterialTheme.typography.bodySmall, color = neutral)
        }
        if (pick.thesis.isNotBlank()) {
            Text(pick.thesis, style = MaterialTheme.typography.bodySmall)
        }
    }
}
