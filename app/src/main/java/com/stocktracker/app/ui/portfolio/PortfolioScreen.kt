package com.stocktracker.app.ui.portfolio

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.PriceChart
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.PriceLarge
import com.stocktracker.app.util.Formatting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen() {
    val vm: PortfolioViewModel = viewModel()
    val state by vm.state.collectAsState()
    val hideZeroCents by ServiceLocator.settingsStore.hideZeroCents.collectAsState(initial = false)

    Scaffold(topBar = { TopAppBar(title = { Text("Portfolio") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.hasHoldings) {
                Text(
                    "No holdings yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 32.dp),
                )
                Text(
                    "Set “Shares owned” on any ticker's detail screen to track your total value here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // Total value + day change
            Text(
                text = Formatting.price(state.totalValue, hideZeroCents = hideZeroCents),
                style = PriceLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            val up = state.dayChange >= 0
            Text(
                text = "${Formatting.changeLine(state.dayChange, state.dayChangePercent, up, hideZeroCents)} Today",
                color = if (up) GainGreen else LossRed,
                fontWeight = FontWeight.Medium,
            )

            // Reconstructed value-over-time chart
            val chartUp = state.chart.size >= 2 && state.chart.last().price >= state.chart.first().price
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loadingChart -> CircularProgressIndicator()
                    state.chart.size >= 2 -> PriceChart(points = state.chart, up = chartUp, modifier = Modifier.fillMaxSize())
                    else -> Text(
                        "Not enough history yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PORTFOLIO_RANGES.forEach { range ->
                    FilterChip(
                        selected = state.range == range,
                        onClick = { vm.selectRange(range) },
                        label = { Text(range.label) },
                    )
                }
            }
            Text(
                "History reflects your current share counts across the whole period.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "Holdings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            state.holdings.forEach { h ->
                val pct = if (state.totalValue > 0) h.value / state.totalValue * 100.0 else 0.0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(h.asset.symbol, fontWeight = FontWeight.Bold)
                        Text(
                            "${Formatting.shares(h.shares)} sh · ${String.format(java.util.Locale.US, "%.1f", pct)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Formatting.price(h.value, hideZeroCents = hideZeroCents), fontWeight = FontWeight.Medium)
                        val hUp = h.dayChange >= 0
                        Text(
                            Formatting.change(h.dayChange, hideZeroCents),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hUp) GainGreen else LossRed,
                        )
                    }
                }
            }

            Box(Modifier.height(8.dp))
        }
    }
}
