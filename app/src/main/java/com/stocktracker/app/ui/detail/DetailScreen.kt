package com.stocktracker.app.ui.detail

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetAlerts
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.PriceChart
import com.stocktracker.app.widget.WidgetRefreshScheduler
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.PriceLarge
import com.stocktracker.app.util.Formatting
import com.stocktracker.app.util.asPercentChange
import com.stocktracker.app.util.formatPercentChange
import com.stocktracker.app.widget.WidgetPinning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    asset: Asset,
    onBack: () -> Unit,
) {
    val vm: DetailViewModel = viewModel(key = asset.id) { DetailViewModel(asset) }
    val state by vm.state.collectAsState()
    val hideZeroCents by ServiceLocator.settingsStore.hideZeroCents.collectAsState(initial = false)
    val showVolume by ServiceLocator.settingsStore.showVolume.collectAsState(initial = false)
    val allGroups by ServiceLocator.settingsStore.watchlistGroups.collectAsState(initial = emptyList())
    var showNewListDialog by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val quote = state.quote
    val up = quote?.isUp ?: true
    var percentMode by remember { mutableStateOf(false) }

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
                text = quote?.let { Formatting.price(it.price, it.currency, hideZeroCents) } ?: "—",
                style = PriceLarge,
            )
            if (quote != null) {
                Text(
                    text = "${Formatting.changeLine(quote.change, quote.changePercent, up, hideZeroCents)} Today",
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
                val chartPoints = if (percentMode) state.chart.asPercentChange() else state.chart
                val chartUp = if (percentMode) (chartPoints.lastOrNull()?.price ?: 0.0) >= 0.0 else up
                when {
                    state.loadingChart -> CircularProgressIndicator()
                    state.chart.size >= 2 -> PriceChart(
                        points = chartPoints,
                        up = chartUp,
                        modifier = Modifier.fillMaxSize(),
                        showVolume = showVolume,
                        showHighLow = true,
                        valueFormatter = {
                            if (percentMode) formatPercentChange(it)
                            else Formatting.price(it, quote?.currency ?: "USD", hideZeroCents)
                        },
                        timeFormatter = { com.stocktracker.app.util.formatChartTimestamp(it, state.range) },
                    )
                    else -> Text(
                        "No chart data for this range",
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
                ChartRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.range == range,
                        onClick = { vm.selectRange(range) },
                        label = { Text(range.label) },
                    )
                }
                // Rebase the line to % change from the range start.
                FilterChip(
                    selected = percentMode,
                    onClick = { percentMode = !percentMode },
                    label = { Text(if (percentMode) "%" else "$") },
                )
            }

            Text("Statistics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatGrid(
                rows = listOf(
                    "Open" to fmt(quote?.open, hideZeroCents),
                    "High" to fmt(quote?.high, hideZeroCents),
                    "Low" to fmt(quote?.low, hideZeroCents),
                    "Prev Close" to fmt(quote?.prevClose, hideZeroCents),
                    "52W High" to fmt(state.fiftyTwoWeekHigh, hideZeroCents),
                    "52W Low" to fmt(state.fiftyTwoWeekLow, hideZeroCents),
                ),
            )

            HoldingsAndAlertsSection(
                quote = quote,
                hideZeroCents = hideZeroCents,
                shares = state.shares,
                avgCost = state.avgCost,
                alerts = state.alerts,
                onSave = { newShares, newAvgCost, newAlerts ->
                    vm.saveHoldingsAndAlerts(newShares, newAvgCost, newAlerts)
                    if (!newAlerts.isEmpty) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        WidgetRefreshScheduler.refreshNow(context) // check the new thresholds promptly
                    }
                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                },
            )

            Text(
                "Lists",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                allGroups.forEach { g ->
                    FilterChip(
                        selected = state.groups.contains(g),
                        onClick = { vm.toggleGroup(g) },
                        label = { Text(g) },
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { showNewListDialog = true },
                    label = { Text("＋ New list") },
                )
            }

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

    if (showNewListDialog) {
        AlertDialog(
            onDismissRequest = { showNewListDialog = false; newListName = "" },
            title = { Text("New list") },
            text = {
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    label = { Text("List name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.createGroupAndAdd(newListName)
                        showNewListDialog = false
                        newListName = ""
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewListDialog = false; newListName = "" }) { Text("Cancel") }
            },
        )
    }
}

private fun fmt(value: Double?, hideZeroCents: Boolean): String =
    value?.let { Formatting.price(it, hideZeroCents = hideZeroCents) } ?: "—"

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

private fun numText(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

@Composable
private fun HoldingsAndAlertsSection(
    quote: Quote?,
    hideZeroCents: Boolean,
    shares: Double?,
    avgCost: Double?,
    alerts: AssetAlerts,
    onSave: (Double?, Double?, AssetAlerts) -> Unit,
) {
    var sharesText by remember(shares) { mutableStateOf(shares?.let { numText(it) } ?: "") }
    var costText by remember(avgCost) { mutableStateOf(avgCost?.let { numText(it) } ?: "") }
    var above by remember(alerts) { mutableStateOf(alerts.priceAbove?.let { numText(it) } ?: "") }
    var below by remember(alerts) { mutableStateOf(alerts.priceBelow?.let { numText(it) } ?: "") }
    var pctUp by remember(alerts) { mutableStateOf(alerts.percentUp?.let { numText(it) } ?: "") }
    var pctDown by remember(alerts) { mutableStateOf(alerts.percentDown?.let { numText(it) } ?: "") }

    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    Text("Holdings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = sharesText,
            onValueChange = { sharesText = it },
            label = { Text("Shares owned") },
            singleLine = true,
            keyboardOptions = decimal,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = costText,
            onValueChange = { costText = it },
            label = { Text("Avg cost / sh") },
            singleLine = true,
            keyboardOptions = decimal,
            modifier = Modifier.weight(1f),
        )
    }
    val sh = sharesText.toDoubleOrNull()
    val cost = costText.toDoubleOrNull()
    if (sh != null && sh > 0.0 && quote != null) {
        Text(
            "Position value: ${Formatting.price(sh * quote.price, quote.currency, hideZeroCents)}",
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (cost != null && cost > 0.0) {
            val gain = sh * (quote.price - cost)
            val gainPct = if (cost != 0.0) (quote.price - cost) / cost * 100.0 else 0.0
            val gUp = gain >= 0.0
            Text(
                "Total return: ${Formatting.changeLine(gain, gainPct, gUp, hideZeroCents)}",
                fontWeight = FontWeight.Medium,
                color = if (gUp) GainGreen else LossRed,
            )
        }
    }

    Text(
        "Alerts",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(above, { above = it }, label = { Text("Above $") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
        OutlinedTextField(below, { below = it }, label = { Text("Below $") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(pctUp, { pctUp = it }, label = { Text("Up ≥ %") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
        OutlinedTextField(pctDown, { pctDown = it }, label = { Text("Down ≥ %") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
    }
    Text(
        "Get notified when the price crosses a level, or the day's move exceeds a percentage.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(
        onClick = {
            onSave(
                sharesText.toDoubleOrNull()?.takeIf { it > 0.0 },
                costText.toDoubleOrNull()?.takeIf { it > 0.0 },
                AssetAlerts(
                    priceAbove = above.toDoubleOrNull(),
                    priceBelow = below.toDoubleOrNull(),
                    percentUp = pctUp.toDoubleOrNull(),
                    percentDown = pctDown.toDoubleOrNull(),
                ),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save holdings & alerts") }
}
