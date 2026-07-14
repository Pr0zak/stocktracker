package com.stocktracker.app.ui.detail

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.ui.components.PriceChart
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.PriceLarge
import com.stocktracker.app.util.Formatting
import com.stocktracker.app.widget.WidgetPinning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    asset: Asset,
    onBack: () -> Unit,
) {
    val vm: DetailViewModel = viewModel(key = asset.id) { DetailViewModel(asset) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val quote = state.quote
    val up = quote?.isUp ?: true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(asset.symbol, fontWeight = FontWeight.Bold)
                        Text(
                            asset.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.toggleWatchlist() }) {
                        Icon(
                            imageVector = if (state.inWatchlist) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Toggle watchlist",
                            tint = if (state.inWatchlist) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            Text(
                text = quote?.let { Formatting.price(it.price, it.currency) } ?: "—",
                style = PriceLarge,
            )
            if (quote != null) {
                Text(
                    text = "${Formatting.changeLine(quote.change, quote.changePercent, up)} Today",
                    color = if (up) GainGreen else LossRed,
                    fontWeight = FontWeight.Medium,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loadingChart -> CircularProgressIndicator()
                    state.chart.size >= 2 -> PriceChart(values = state.chart, up = up, modifier = Modifier.fillMaxSize())
                    else -> Text(
                        "No chart data for this range",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChartRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.range == range,
                        onClick = { vm.selectRange(range) },
                        label = { Text(range.label) },
                    )
                }
            }

            Text("Statistics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatGrid(
                rows = listOf(
                    "Open" to fmt(quote?.open),
                    "High" to fmt(quote?.high),
                    "Low" to fmt(quote?.low),
                    "Prev Close" to fmt(quote?.prevClose),
                ),
            )

            Button(
                onClick = {
                    val ok = WidgetPinning.requestPinTicker(context)
                    if (!ok) {
                        Toast.makeText(
                            context,
                            "Long-press your home screen to add a StockTracker widget",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) { Text("Add Widget to Home Screen") }
        }
    }
}

private fun fmt(value: Double?): String = value?.let { Formatting.price(it) } ?: "—"

@Composable
private fun StatGrid(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.chunked(2).forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, fontWeight = FontWeight.Medium)
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}
