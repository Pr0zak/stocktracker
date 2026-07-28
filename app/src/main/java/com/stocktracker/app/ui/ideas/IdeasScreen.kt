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
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
fun IdeasScreen(onOpenDetail: (Asset) -> Unit, onBack: () -> Unit = {}) {
    val vm: IdeasViewModel = viewModel()
    val state by vm.state.collectAsState()
    val watchlist by ServiceLocator.watchlistStore.watchlist.collectAsState(initial = emptyList())

    // Resolve a pick's symbol back to a watchlist asset (crypto picks arrive in Yahoo "BTC-USD" form).
    fun assetFor(symbol: String): Asset? = watchlist.firstOrNull {
        val s = it.symbol.uppercase()
        s == symbol.uppercase() || (it.type == AssetType.CRYPTO && "$s-USD" == symbol.uppercase())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ideas") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Only when the backend is the relevant problem. If the AI switch is off, the notice
            // below is the accurate diagnosis and a "tap to retry" would fix nothing on this screen.
            val backendOffline = com.stocktracker.app.ui.components.backendOffline()
            if (state.enabled) com.stocktracker.app.ui.components.BackendStatusBanner()

            // ABOVE the AI-switch gate on purpose: this screen costs nothing to run (no LLM), so
            // hiding it behind the analyst switch would withhold a free feature from anyone who
            // turned the paid one off.
            ValueScreenCard(
                ui = state,
                onRefresh = { vm.loadScreen(refresh = true) },
                onOpen = { sym ->
                    val asset = sym.takeUnless { it.uppercase().endsWith("-USD") }
                        ?.let { Asset(it, AssetType.STOCK, it, null) }
                    asset?.let(onOpenDetail)
                },
            )

            if (!state.enabled) {
                Text(
                    "AI analyst is off. Enable it and set your Signals service URL in " +
                        "Settings → AI analyst to get deployment ideas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                if (state.market) {
                    "How much free cash do you want to put to work? The AI analyst compares your " +
                        "watchlist plus live market screens (actives, gainers, growth, value) and " +
                        "spreads it across the best entries anywhere."
                } else {
                    "How much free cash do you want to put to work? The AI analyst compares your whole " +
                        "watchlist and spreads it across its best entries."
                },
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
                    selected = state.market,
                    onClick = { vm.setMarket(!state.market) },
                    label = { Text("Whole market") },
                )
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
            // Suppressed while the banner is up: "Couldn't reach the analyst service" and
            // "Backend offline" are the same news twice, with two retries that do different things.
            // (Sandbox already guards this way; Ideas did not.) It also outlived the outage - only a
            // new request cleared it - so recovery left a red error under a healthy screen.
            if (!backendOffline) {
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
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
                    val known = assetFor(pick.symbol)
                    PickCard(
                        pick,
                        isNew = known == null,
                        onClick = {
                            // Discovered stocks open a detail screen too (Yahoo-backed by symbol);
                            // unknown "-USD" symbols can't (crypto detail needs a CoinGecko id).
                            val asset = known ?: pick.symbol
                                .takeUnless { it.uppercase().endsWith("-USD") }
                                ?.let { Asset(it, AssetType.STOCK, it, null) }
                            asset?.let(onOpenDetail)
                        },
                    )
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
private fun PickCard(pick: EntryPlan, isNew: Boolean = false, onClick: () -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pick.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (isNew) { // discovered by the market screen, not on the watchlist
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            "NEW",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
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
                // "0 sh · $0" is not a suggestion; when the analyst gave no size, say so.
                (pick.suggestedShares?.takeIf { it > 0.0 }?.let { "${sharesText(it)} sh" }
                    ?: "size not given") +
                    (pick.allocationUsd?.takeIf { it > 0.0 }?.let { " · ${usd(it)}" } ?: ""),
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
            // Each level is printed only when the analyst actually supplied it — a missing one used
            // to decode to 0.0 and render as an authoritative "$0".
            listOfNotNull(
                if (pick.entryLow != null && pick.entryHigh != null && pick.entryLow > 0.0)
                    "Entry ${usd(pick.entryLow)}–${usd(pick.entryHigh)}" else null,
                pick.stop?.takeIf { it > 0.0 }?.let { "stop ${usd(it)}" },
                pick.target?.takeIf { it > 0.0 }?.let { "target ${usd(it)}" },
            ).joinToString(" · ").ifBlank { "No levels given" },
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


/**
 * The 200-week value screen (MB-15/MB-18) — names trading unusually far below their own long-term
 * trend, ranked by our thesis, computed with no LLM.
 *
 * The server's own caveat is rendered verbatim and not paraphrased away: the historical touch study
 * on this codebase found below-the-line dips UNDERPERFORMED the S&P over the following 12-24 months.
 * This is a "what is unusually dislocated" list, not a buy list, and the card has to say so or it
 * reads as the opposite.
 */
@Composable
private fun ValueScreenCard(ui: IdeasUiState, onRefresh: () -> Unit, onOpen: (String) -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val amber = Color(0xFFB0872B)
    val screen = ui.screen
    if (screen == null && !ui.screenLoading && ui.screenError == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Below their 200-week line", style = MaterialTheme.typography.titleSmall)
            if (ui.screenLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }

        ui.screenError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = amber) }

        screen?.let { s ->
            if (s.results.isEmpty()) {
                Text("Nothing in the screened universe is below its 200-week line right now.",
                     style = MaterialTheme.typography.bodySmall, color = neutral)
            }
            s.results.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(row.symbol) }
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.symbol, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildString {
                            row.priceVs200wPct?.let { append(String.format("%.0f", it)).append("% vs line") }
                            row.direction?.let { append(" · ").append(it.replace('_', ' ')) }
                            row.rsi14w?.let { append(" · RSI ").append(String.format("%.0f", it)) }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        // A MISSING direction is not "still falling". Amber-by-default painted an
                        // unknown as a specific negative, which is the same defect one colour up.
                        color = when (row.direction) {
                            "recovering" -> neutral
                            null, "" -> neutral
                            else -> amber
                        },
                    )
                }
            }
            // Say what was NOT scored. A name missing because it has under ~4 years of history is a
            // different fact from a name that scored badly, and the list alone cannot show that.
            if (s.skipped.isNotEmpty()) {
                Text("Not enough history to score: ${s.skipped.joinToString(", ")}",
                     style = MaterialTheme.typography.labelSmall, color = neutral)
            }
            // A fetch failure is a fact about the network, not about the company — kept separate
            // from "not enough history" for exactly that reason.
            if (s.fetchFailed.isNotEmpty()) {
                Text("Couldn't fetch: ${s.fetchFailed.joinToString(", ")}",
                     style = MaterialTheme.typography.labelSmall, color = amber)
            }
            // Which pool this ran over, and whether it is current. A fallback or stale run looked
            // identical to a fresh curated one.
            if (s.universeSource == "yahoo_screens") {
                Text("Screened a live sample, not the full curated list — results will shift between runs",
                     style = MaterialTheme.typography.labelSmall, color = amber)
            } else if (s.universeStale) {
                Text("The screened list is out of date and due a refresh",
                     style = MaterialTheme.typography.labelSmall, color = amber)
            }
            if (s.note.isNotBlank()) {
                Text(s.note, style = MaterialTheme.typography.labelSmall, color = amber)
            }
            val age = s.cachedAgeSeconds
            if (s.cached && age != null) {
                Text(
                    "Screened " + when {
                        age < 120 -> "just now"
                        age < 3600 -> "${age / 60} min ago"
                        else -> "${age / 3600}h ago"
                    },
                    style = MaterialTheme.typography.labelSmall, color = neutral,
                )
            }
        }
    }
}
