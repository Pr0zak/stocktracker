package com.stocktracker.app.ui.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.calls.MyCallsSection
import com.stocktracker.app.ui.components.ChartLineOverlay
import com.stocktracker.app.ui.components.PriceChart
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.PriceLarge
import com.stocktracker.app.util.Formatting
import com.stocktracker.app.util.asPercentChange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen() {
    val vm: PortfolioViewModel = viewModel()
    val state by vm.state.collectAsState()
    val hideZeroCents by ServiceLocator.settingsStore.hideZeroCents.collectAsState(initial = false)
    var percentMode by remember { mutableStateOf(false) }

    if (state.review.open) {
        PortfolioReviewDialog(
            ui = state.review,
            onRefresh = { vm.loadReview(force = true) },
            onDismiss = { vm.dismissReview() },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Portfolio") },
                actions = {
                    if (state.hasHoldings) IconButton(onClick = { vm.openReview() }) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "AI portfolio review")
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
                // Tracked call positions live here too — they don't need share holdings to exist.
                MyCallsSection()
                Box(Modifier.height(8.dp))
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
            if (state.hasCostBasis) {
                val gUp = state.totalGain >= 0
                Text(
                    text = "${Formatting.changeLine(state.totalGain, state.totalGainPercent, gUp, hideZeroCents)} total return",
                    color = if (gUp) GainGreen else LossRed,
                    fontWeight = FontWeight.Medium,
                )
            }
            // vs the same money in the S&P 500, and the worst peak-to-trough dip over the window.
            if (state.vsSpyPct != null || state.maxDrawdownPct != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    state.vsSpyPct?.let { v ->
                        Text(
                            "vs S&P ${if (v >= 0) "+" else ""}${"%.1f".format(v)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (v >= 0) GainGreen else LossRed,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    state.maxDrawdownPct?.let { d ->
                        Text(
                            "Max drawdown ${"%.1f".format(d)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Reconstructed value-over-time chart, with the S&P 500 overlaid (pink).
            val chartPoints = if (percentMode) state.chart.asPercentChange() else state.chart
            val chartUp = chartPoints.size >= 2 && chartPoints.last().price >= chartPoints.first().price
            val benchOverlay = if (state.benchmarkChart.size == state.chart.size && state.benchmarkChart.size >= 2) {
                val bp = if (percentMode) state.benchmarkChart.asPercentChange() else state.benchmarkChart
                listOf(ChartLineOverlay("S&P 500", Color(0xFFEC4899), bp.map { it.price }))
            } else {
                emptyList()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loadingChart -> CircularProgressIndicator()
                    state.chart.size >= 2 -> PriceChart(
                        points = chartPoints,
                        up = chartUp,
                        modifier = Modifier.fillMaxSize(),
                        showHighLow = true,
                        showAxis = true,
                        overlays = benchOverlay,
                        costLine = if (percentMode) null else state.totalCost.takeIf { state.hasCostBasis && it > 0.0 },
                        valueFormatter = {
                            if (percentMode) com.stocktracker.app.util.formatPercentChange(it)
                            else Formatting.price(it, hideZeroCents = hideZeroCents)
                        },
                        timeFormatter = { com.stocktracker.app.util.formatChartTimestamp(it, com.stocktracker.app.data.model.ChartRange.ALL) },
                    )
                    else -> Text(
                        "Not enough history yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PORTFOLIO_RANGES.forEach { range ->
                    FilterChip(
                        selected = state.range == range,
                        onClick = { vm.selectRange(range) },
                        label = { Text(range.label) },
                    )
                }
                FilterChip(
                    selected = percentMode,
                    onClick = { percentMode = !percentMode },
                    label = { Text(if (percentMode) "%" else "$") },
                )
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
            // Allocation donut — how the book is split, one colour per position, echoed in the rows.
            val sortedHoldings = state.holdings.sortedByDescending { it.value }
            val sliceColor = sortedHoldings
                .mapIndexed { i, h -> h.asset.symbol to DONUT_COLORS[i % DONUT_COLORS.size] }
                .toMap()
            if (sortedHoldings.size >= 2 && state.totalValue > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AllocationDonut(
                        slices = sortedHoldings.map {
                            (sliceColor[it.asset.symbol] ?: DONUT_COLORS[0]) to (it.value / state.totalValue).toFloat()
                        },
                        modifier = Modifier.size(96.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        sortedHoldings.take(5).forEach { h ->
                            val pct = h.value / state.totalValue * 100.0
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .background(sliceColor[h.asset.symbol] ?: DONUT_COLORS[0], RoundedCornerShape(50)),
                                )
                                Text(h.asset.symbol, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "${String.format(java.util.Locale.US, "%.0f", pct)}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (sortedHoldings.size > 5) {
                            Text(
                                "+${sortedHoldings.size - 5} more",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            sortedHoldings.forEach { h ->
                val pct = if (state.totalValue > 0) h.value / state.totalValue * 100.0 else 0.0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(sliceColor[h.asset.symbol] ?: DONUT_COLORS[0], RoundedCornerShape(50)),
                    )
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
                        h.gainPercent?.let { gp ->
                            val gUp = (h.gain ?: 0.0) >= 0
                            Text(
                                "${if (gUp) "▲" else "▼"} ${String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(gp))}% total",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (gUp) GainGreen else LossRed,
                            )
                        }
                    }
                }
            }

            // Manually-tracked long-call positions (OC-3) — live P/L, DTE, ITM/OTM.
            MyCallsSection()

            Box(Modifier.height(8.dp))
        }
    }
}

/** Distinct slice colours for the allocation donut, cycled by position rank (largest first). */
private val DONUT_COLORS = listOf(
    Color(0xFF7C6BD6), Color(0xFF4666CF), Color(0xFF0F8A7E), Color(0xFFD29922),
    Color(0xFFB0543D), Color(0xFFC2477E), Color(0xFF2E9E57), Color(0xFF8A6BB0),
)

/** A thin allocation donut — one arc per position, swept by its share of the book, drawn on Canvas. */
@Composable
private fun AllocationDonut(slices: List<Pair<Color, Float>>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.18f
        val d = size.minDimension - stroke
        val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        val arc = Size(d, d)
        var start = -90f
        slices.forEach { (color, frac) ->
            val sweep = frac * 360f
            drawArc(
                color = color,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = tl,
                size = arc,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            start += sweep
        }
    }
}

/** AI portfolio review dialog: overall health, concentration flags, a per-holding action list, and a
 *  cash note. Opened from the Portfolio top-bar sparkle. */
@Composable
private fun PortfolioReviewDialog(
    ui: PortfolioReviewUi,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val amber = Color(0xFFB0872B)
    val r = ui.result?.review
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Portfolio review", style = MaterialTheme.typography.titleLarge)
                ui.result?.portfolio?.let { p ->
                    Text(
                        "$" + Formatting.compact(p.totalValue) + " · " + String.format("%.0f", p.cashPct) + "% cash",
                        style = MaterialTheme.typography.labelSmall, color = neutral,
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
                when {
                    ui.loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Reviewing your book…")
                    }
                    ui.error != null -> Text(ui.error, color = MaterialTheme.colorScheme.error)
                    r != null -> {
                        Text(r.health, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        if (r.concentration.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Concentration", style = MaterialTheme.typography.labelMedium, color = neutral)
                            r.concentration.forEach {
                                Text("⚠  $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                        if (r.actions.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Actions", style = MaterialTheme.typography.labelMedium, color = neutral)
                            r.actions.forEach { a ->
                                val ac = when (a.action.lowercase()) {
                                    "add" -> GainGreen
                                    "trim" -> amber
                                    "watch" -> LossRed
                                    else -> neutral
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(ac.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    ) {
                                        Text(a.action.uppercase(), style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold, color = ac)
                                    }
                                    Text("${a.symbol} — ${a.reason}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (r.cashNote.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text("Cash: ${r.cashNote}", style = MaterialTheme.typography.bodySmall, color = neutral)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Decision support, not investment advice.",
                            style = MaterialTheme.typography.labelSmall, color = neutral)
                    }
                    else -> Text("No review yet — tap Refresh.")
                }
            }
        },
        confirmButton = { TextButton(onClick = onRefresh, enabled = !ui.loading) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
