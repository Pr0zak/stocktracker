package com.stocktracker.app.ui.ask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.AskIdea
import com.stocktracker.app.data.remote.AskResponse
import com.stocktracker.app.ui.components.BackendStatusBanner
import com.stocktracker.app.ui.components.ChangePill
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.components.backendOffline
import com.stocktracker.app.util.Formatting
import java.util.Locale

/**
 * Ask AI — one free-form question ("which ETF should I buy today?"), answered by the deep model from
 * the signals service's own data for today: live moves, each fund's distance from its high and its
 * 200-day, the dip scan, the daily picks, the market gate and the news backdrop. Ideas are limited
 * to names that data priced; the server re-stamps their numbers, so the pills here are data.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AskScreen(
    onBack: () -> Unit,
    onOpenDetail: (Asset) -> Unit,
    onOpenSignalsSettings: () -> Unit = {},
) {
    val vm: AskViewModel = viewModel()
    val state by vm.state.collectAsState()
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ask AI") },
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
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.enabled) {
                Text(
                    "Ask AI needs the AI analyst. Turn it on and set your Signals service URL in " +
                        "Settings → AI analyst.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenSignalsSettings) { Text("Set up signals") }
                return@Column
            }
            BackendStatusBanner()
            val offline = backendOffline()

            OutlinedTextField(
                value = state.question,
                onValueChange = vm::setQuestion,
                label = { Text("Ask about today's market") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.ask() }),
                trailingIcon = {
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { vm.ask() }, enabled = state.question.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
                        }
                    }
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.examples.forEach { ex ->
                    SuggestionChip(
                        onClick = { vm.ask(ex) },
                        enabled = !state.loading,
                        label = { Text(ex, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.includeHoldings,
                    onClick = { vm.setIncludeHoldings(!state.includeHoldings) },
                    label = { Text("Consider my holdings") },
                )
                if (state.loading) {
                    Text(
                        "Reading today's market…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!offline) {
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            state.answers.forEach { a -> AnswerBlock(a, onOpenDetail) }

            Text(
                "Decision support, not advice. Answers use only today's data from your signals service.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerBlock(a: AskResponse, onOpenDetail: (Asset) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("“${a.question}”", style = MaterialTheme.typography.labelLarge, color = muted)
        Text(a.answer, style = MaterialTheme.typography.bodyMedium)
        a.ideas.forEach { IdeaCard(it, onOpenDetail) }
        if (a.ideas.isEmpty()) {
            a.noneReason?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
        asOfLine(a)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdeaCard(i: AskIdea, onOpenDetail: (Asset) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val crypto = i.symbol.endsWith("-USD")
    GlowCard(
        tint = null,
        modifier = Modifier.clickable(enabled = !crypto, onClickLabel = "Open ${i.symbol}") {
            onOpenDetail(Asset(i.symbol, AssetType.STOCK, i.symbol, null))
        },
        spacing = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(i.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Pill(if (i.kind.equals("etf", ignoreCase = true)) "ETF" else "Stock", MaterialTheme.colorScheme.primary)
            i.price?.let { Text(Formatting.price(it), style = MaterialTheme.typography.bodyMedium) }
        }
        if (i.headline.isNotBlank()) {
            Text(i.headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChangePill(i.todayPct, suffix = " today")
            i.pctOff52wHigh?.let { Pill(String.format(Locale.US, "%.1f%% off high", -it), muted) }
            i.feePct?.let { Pill(String.format(Locale.US, "%.2f%% fee", it), muted) }
            entryZone(i)?.let { Pill(it, muted) }
        }
        if (i.why.isNotBlank()) Text(i.why, style = MaterialTheme.typography.bodySmall)
        if (i.watchOut.isNotBlank()) {
            Text("Watch: ${i.watchOut}", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

internal fun entryZone(i: AskIdea): String? {
    val lo = i.entryLow ?: return null
    val hi = i.entryHigh ?: return null
    return "Buy ${Formatting.price(lo)}–${Formatting.price(hi)}"
}

/**
 * Where the answer's numbers came from, and what was missing. A block the server could not build is
 * named rather than hidden — an answer written without the dip scan is a different answer.
 */
internal fun asOfLine(a: AskResponse): String? {
    val parts = mutableListOf<String>()
    a.asOf?.session?.let { parts += "Market ${it.lowercase(Locale.US)}" }
    a.asOf?.stockScanNight?.let { parts += "stock scan $it" }
    if (a.gatePassed == false) parts += "market gate closed"
    if (a.missing.isNotEmpty()) parts += "missing: ${a.missing.joinToString()}"
    if (a.dropped.isNotEmpty()) parts += "removed unpriced: ${a.dropped.joinToString()}"
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
