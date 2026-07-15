package com.stocktracker.app.ui.detail

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.model.VixQuote
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.FearGauge
import com.stocktracker.app.ui.components.PriceChart
import com.stocktracker.app.util.formatChartTimestamp
import java.util.Locale

private val VIX_RANGES = listOf(
    ChartRange.WEEK, ChartRange.MONTH, ChartRange.QUARTER, ChartRange.YEAR, ChartRange.THREE_YEAR,
)

/** Tapping the dashboard fear gauge lands here: current VIX + its history chart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VixDetailScreen(onBack: () -> Unit) {
    val repo = ServiceLocator.repository
    val vixAsset = remember { Asset(symbol = "^VIX", type = AssetType.STOCK, displayName = "Volatility Index") }

    var range by remember { mutableStateOf(ChartRange.QUARTER) }
    var loading by remember { mutableStateOf(true) }

    val vix by produceState<VixQuote?>(initialValue = null) {
        value = runCatching { repo.vix() }.getOrNull()
    }
    // Re-fetch history whenever the range changes.
    val chart by produceState(initialValue = emptyList<PricePoint>(), range) {
        loading = true
        value = runCatching { repo.history(vixAsset, range) }.getOrDefault(emptyList())
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VIX · Volatility Index") },
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
            vix?.let { FearGauge(it, modifier = Modifier.padding(top = 8.dp)) }

            val up = chart.size >= 2 && chart.last().price >= chart.first().price
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loading && chart.isEmpty() -> CircularProgressIndicator()
                    chart.size >= 2 -> PriceChart(
                        points = chart,
                        up = up,
                        modifier = Modifier.fillMaxSize(),
                        showHighLow = true,
                        valueFormatter = { String.format(Locale.US, "%.2f", it) },
                        timeFormatter = { formatChartTimestamp(it, range) },
                    )
                    else -> Text(
                        "No VIX history for this range",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VIX_RANGES.forEach { r ->
                    FilterChip(
                        selected = range == r,
                        onClick = { range = r },
                        label = { Text(r.label) },
                    )
                }
            }

            Text(
                "The VIX measures expected 30-day volatility of the S&P 500 — Wall Street's \"fear gauge.\" " +
                    "It tends to spike when markets fall, so a rising VIX signals rising fear.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
