package com.stocktracker.app.ui.detail

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Insights
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetAlerts
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.remote.AiVerdict
import com.stocktracker.app.data.remote.EntryPlan
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.signals.SignalLabel
import com.stocktracker.app.signals.SignalResult
import com.stocktracker.app.ui.components.ChartLineOverlay
import com.stocktracker.app.ui.components.ChartMarker
import com.stocktracker.app.ui.components.ChartSubPane
import com.stocktracker.app.ui.components.FiftyTwoWeekRangeBar
import com.stocktracker.app.ui.components.PriceChart
import com.stocktracker.app.ui.ideas.formatCashPlain
import com.stocktracker.app.ui.ideas.planActionColor
import com.stocktracker.app.ui.ideas.planActionLabel
import com.stocktracker.app.ui.ideas.sharesText
import com.stocktracker.app.ui.ideas.usd
import com.stocktracker.app.widget.WidgetRefreshScheduler
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.PriceLarge
import com.stocktracker.app.util.Formatting
import com.stocktracker.app.util.asPercentChange
import com.stocktracker.app.util.bollingerBands
import com.stocktracker.app.util.exponentialMovingAverage
import com.stocktracker.app.util.formatPercentChange
import com.stocktracker.app.util.macd
import com.stocktracker.app.util.rsi
import com.stocktracker.app.util.simpleMovingAverage
import com.stocktracker.app.util.stochastic
import com.stocktracker.app.util.vwap
import kotlinx.coroutines.launch
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
    val indicators by ServiceLocator.settingsStore.chartIndicators.collectAsState(initial = emptySet())
    val allGroups by ServiceLocator.settingsStore.watchlistGroups.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showIndicatorSheet by remember { mutableStateOf(false) }
    var showNewListDialog by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val quote = state.quote
    val up = quote?.isUp ?: true
    var percentMode by remember { mutableStateOf(false) }
    val isCrypto = asset.type == AssetType.CRYPTO
    // The chart point currently under the scrub crosshair (null at rest); drives the header.
    var scrubbed by remember(state.chart, percentMode) { mutableStateOf<PricePoint?>(null) }

    // Event/benchmark data (only fetched when the matching indicator is enabled).
    val divEnabled = indicators.contains(Indicator.DIVIDENDS.key)
    val benchEnabled = indicators.contains(Indicator.BENCHMARK.key)
    val dividends by produceState(emptyList<Pair<Long, Double>>(), asset.id, state.range, divEnabled) {
        value = if (divEnabled) runCatching { ServiceLocator.repository.dividends(asset, state.range) }.getOrDefault(emptyList()) else emptyList()
    }
    val benchPoints by produceState(emptyList<PricePoint>(), state.range, benchEnabled) {
        value = if (benchEnabled) runCatching { ServiceLocator.repository.benchmark(state.range) }.getOrDefault(emptyList()) else emptyList()
    }

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
                    IconButton(onClick = { showIndicatorSheet = true }) {
                        Icon(
                            Icons.Filled.Insights,
                            contentDescription = "Indicators",
                            tint = if (indicators.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
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

            val chartValueFormatter: (Double) -> String = {
                if (percentMode) formatPercentChange(it)
                else Formatting.price(it, quote?.currency ?: "USD", hideZeroCents)
            }
            val chartTimeFormatter: (Long) -> String = { com.stocktracker.app.util.formatChartTimestamp(it, state.range) }

            // Scrub-synced stat header: Open/High/Low/Vol at rest, the crosshair reading while dragging.
            ScrubStatHeader(
                scrubbed = scrubbed,
                quote = quote,
                isCrypto = isCrypto,
                hideZeroCents = hideZeroCents,
                valueFormatter = chartValueFormatter,
                timeFormatter = chartTimeFormatter,
            )

            val chartPoints = if (percentMode) state.chart.asPercentChange() else state.chart
            val chartUp = if (percentMode) (chartPoints.lastOrNull()?.price ?: 0.0) >= 0.0 else up
            // Technical indicators are price-based, so only compute them in $ mode.
            val indicatorResult = if (!percentMode) buildIndicators(chartPoints, indicators)
            else IndicatorResult(emptyList(), emptyList())
            val chartHeight = 200.dp + 18.dp + 64.dp * indicatorResult.subPanes.size

            // Ex-dividend markers (any mode) + S&P 500 comparison line (% mode only).
            val divMarkers = if (divEnabled) dividends.map { ChartMarker(it.first, Color(0xFF6366F1), "Div") } else emptyList()
            val benchOverlay = if (percentMode && benchEnabled) {
                benchmarkPercent(chartPoints, benchPoints).takeIf { s -> s.any { it != null } }
                    ?.let { ChartLineOverlay("S&P 500", Color(0xFFEC4899), it) }
            } else {
                null
            }
            val allOverlays = indicatorResult.overlays + listOfNotNull(benchOverlay)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loadingChart -> CircularProgressIndicator()
                    state.chart.size >= 2 -> PriceChart(
                        points = chartPoints,
                        up = chartUp,
                        modifier = Modifier.fillMaxSize(),
                        showVolume = showVolume,
                        showHighLow = true,
                        showReadout = false,
                        showAxis = true,
                        zoomable = true,
                        costLine = if (percentMode) null else state.avgCost?.takeIf { it > 0.0 },
                        overlays = allOverlays,
                        subPanes = indicatorResult.subPanes,
                        markers = divMarkers,
                        onScrubChange = { scrubbed = it },
                        valueFormatter = chartValueFormatter,
                        timeFormatter = chartTimeFormatter,
                    )
                    // A failed fetch is offered a retry; a successful-but-empty range just says so.
                    state.chartError -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Couldn't load chart",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { vm.selectRange(state.range) }) { Text("Retry") }
                    }
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

            val lo = state.fiftyTwoWeekLow
            val hi = state.fiftyTwoWeekHigh
            if (lo != null && hi != null && quote != null) {
                FiftyTwoWeekRangeBar(
                    low = lo,
                    high = hi,
                    current = quote.price,
                    up = up,
                    valueFormatter = { Formatting.price(it, quote.currency, hideZeroCents) },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            state.signal?.let { SignalCard(it) }

            if (state.aiEnabled) {
                AiAnalystCard(
                    verdict = state.aiVerdict,
                    model = state.aiModel,
                    loading = state.aiLoading,
                    error = state.aiError,
                    onRetry = { vm.requestAiVerdict(deep = false) },
                    onDeepDive = { vm.requestAiVerdict(deep = true) },
                )
                EntryPlanCard(
                    plan = state.plan,
                    loading = state.planLoading,
                    error = state.planError,
                    onPlan = { cash -> vm.requestPlan(cash) },
                )
            }

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

    if (showIndicatorSheet) {
        ModalBottomSheet(onDismissRequest = { showIndicatorSheet = false }) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .padding(bottom = 28.dp),
            ) {
                Text("Indicators", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Overlays draw on the price; RSI and MACD add a pane below the chart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Indicator.entries.forEach { ind ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ind.label, modifier = Modifier.weight(1f))
                        Switch(
                            checked = indicators.contains(ind.key),
                            onCheckedChange = { checked ->
                                val next = if (checked) indicators + ind.key else indicators - ind.key
                                scope.launch { ServiceLocator.settingsStore.setChartIndicators(next) }
                            },
                        )
                    }
                }
            }
        }
    }
}

private enum class Indicator(val key: String, val label: String) {
    SMA20("sma20", "SMA 20"),
    SMA50("sma50", "SMA 50"),
    EMA21("ema21", "EMA 21"),
    BOLLINGER("bb", "Bollinger Bands (20, 2)"),
    VWAP("vwap", "VWAP"),
    RSI("rsi", "RSI (14)"),
    MACD("macd", "MACD (12, 26, 9)"),
    STOCH("stoch", "Stochastic (14, 3)"),
    DIVIDENDS("div", "Ex-dividend markers"),
    BENCHMARK("bench", "S&P 500 (in % mode)"),
}

private data class IndicatorResult(val overlays: List<ChartLineOverlay>, val subPanes: List<ChartSubPane>)

/** Builds the enabled overlay lines + oscillator sub-panes for the chart from a set of indicator keys. */
private fun buildIndicators(points: List<com.stocktracker.app.data.model.PricePoint>, enabled: Set<String>): IndicatorResult {
    if (enabled.isEmpty() || points.size < 2) return IndicatorResult(emptyList(), emptyList())
    val prices = points.map { it.price }
    val volumes = points.map { it.volume }
    val overlays = mutableListOf<ChartLineOverlay>()
    val subPanes = mutableListOf<ChartSubPane>()
    fun nonEmpty(v: List<Double?>) = v.any { it != null }

    if (Indicator.SMA20.key in enabled) simpleMovingAverage(prices, 20).let { if (nonEmpty(it)) overlays += ChartLineOverlay("SMA20", Color(0xFF60A5FA), it) }
    if (Indicator.SMA50.key in enabled) simpleMovingAverage(prices, 50).let { if (nonEmpty(it)) overlays += ChartLineOverlay("SMA50", Color(0xFFF59E0B), it) }
    if (Indicator.EMA21.key in enabled) exponentialMovingAverage(prices, 21).let { if (nonEmpty(it)) overlays += ChartLineOverlay("EMA21", Color(0xFFA855F7), it) }
    if (Indicator.BOLLINGER.key in enabled) {
        val b = bollingerBands(prices, 20, 2.0)
        if (nonEmpty(b.mid)) {
            val g = Color(0xFF94A3B8)
            overlays += ChartLineOverlay("BB", g, b.mid)
            overlays += ChartLineOverlay("", g.copy(alpha = 0.7f), b.upper)
            overlays += ChartLineOverlay("", g.copy(alpha = 0.7f), b.lower)
        }
    }
    if (Indicator.VWAP.key in enabled) vwap(prices, volumes).let { if (nonEmpty(it)) overlays += ChartLineOverlay("VWAP", Color(0xFF14B8A6), it) }
    if (Indicator.RSI.key in enabled) {
        val r = rsi(prices, 14)
        if (nonEmpty(r)) subPanes += ChartSubPane(
            label = "RSI 14",
            lines = listOf(ChartLineOverlay("", Color(0xFF60A5FA), r)),
            guides = listOf(30.0, 70.0),
            fixedRange = 0.0..100.0,
        )
    }
    if (Indicator.MACD.key in enabled) {
        val m = macd(prices)
        if (nonEmpty(m.macd)) subPanes += ChartSubPane(
            label = "MACD",
            lines = listOf(
                ChartLineOverlay("", Color(0xFF60A5FA), m.macd),
                ChartLineOverlay("", Color(0xFFF59E0B), m.signal),
            ),
            histogram = m.histogram,
            guides = listOf(0.0),
        )
    }
    if (Indicator.STOCH.key in enabled) {
        val (kLine, dLine) = stochastic(prices, 14, 3)
        if (nonEmpty(kLine)) subPanes += ChartSubPane(
            label = "Stoch 14",
            lines = listOf(
                ChartLineOverlay("", Color(0xFF60A5FA), kLine),
                ChartLineOverlay("", Color(0xFFF59E0B), dLine),
            ),
            guides = listOf(20.0, 80.0),
            fixedRange = 0.0..100.0,
        )
    }
    return IndicatorResult(overlays, subPanes)
}

/** Benchmark % series (S&P 500) aligned by calendar day to the ticker's points, rebased to 0% at the start. */
private fun benchmarkPercent(
    tickerPoints: List<com.stocktracker.app.data.model.PricePoint>,
    benchPoints: List<com.stocktracker.app.data.model.PricePoint>,
): List<Double?> {
    if (tickerPoints.isEmpty() || benchPoints.size < 2) return List(tickerPoints.size) { null }
    val dayMs = 86_400_000L
    val byDay = HashMap<Long, Double>()
    benchPoints.forEach { byDay[it.epochMs / dayMs] = it.price }
    val aligned = tickerPoints.map { byDay[it.epochMs / dayMs] }
    val base = aligned.firstOrNull { it != null } ?: return List(tickerPoints.size) { null }
    return aligned.map { if (it != null) (it / base - 1.0) * 100.0 else null }
}

@Composable
private fun ScrubStatHeader(
    scrubbed: PricePoint?,
    quote: Quote?,
    isCrypto: Boolean,
    hideZeroCents: Boolean,
    valueFormatter: (Double) -> String,
    timeFormatter: (Long) -> String,
) {
    val fmtP = { v: Double? -> v?.let { Formatting.price(it, quote?.currency ?: "USD", hideZeroCents) } ?: "—" }
    val volText = quote?.volume?.let { (if (isCrypto) "$" else "") + Formatting.compact(it) } ?: "—"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Crypto has no intraday O/H/L, so show its meaningful pair instead of empty dashes.
        val restStats = if (isCrypto) {
            listOf("Prev Close" to fmtP(quote?.prevClose), "24h Vol" to volText)
        } else {
            listOf("Open" to fmtP(quote?.open), "High" to fmtP(quote?.high), "Low" to fmtP(quote?.low), "Volume" to volText)
        }
        Crossfade(targetState = scrubbed, label = "scrub") { s ->
            if (s == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (restStats.size >= 3) Arrangement.SpaceBetween else Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    restStats.forEach { (label, value) -> StatMini(label, value) }
                }
            } else {
                Column {
                    Text(
                        valueFormatter(s.price),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        timeFormatter(s.epochMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatMini(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Tier-1 rule-based signal readout: score meter, label, the top few reasons, and a not-advice note. */
@Composable
private fun SignalCard(signal: SignalResult) {
    val buy = Color(0xFF16A34A)
    val sell = Color(0xFFDC2626)
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = when (signal.label) {
        SignalLabel.STRONG_BUY, SignalLabel.BUY -> buy
        SignalLabel.SELL, SignalLabel.STRONG_SELL -> sell
        SignalLabel.HOLD -> neutral
    }
    fun reasonColor(score: Double) = when {
        score > 0.05 -> buy
        score < -0.05 -> sell
        else -> neutral
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Signal", style = MaterialTheme.typography.labelLarge, color = neutral)
            Text(
                "${signal.label.display} · ${signal.score}/100",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = labelColor,
            )
        }
        // Score meter: 0 (bearish) ─ 50 (neutral) ─ 100 (bullish).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(neutral.copy(alpha = 0.18f), RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((signal.score / 100f).coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(labelColor, RoundedCornerShape(3.dp)),
            )
        }
        signal.components.take(3).forEach { c ->
            Text(c.reason, style = MaterialTheme.typography.bodySmall, color = reasonColor(c.score))
        }
        signal.regimeNote?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = neutral)
        }
        Text(
            "Rule-based signal on daily bars — decision support, not advice.",
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    }
}

/** Tier-2 Claude analyst verdict: signal pill + conviction meter, horizon, thesis, expandable
 *  reasoning, deep-dive. Mirrors the rule-based SignalCard's visual language. */
@Composable
private fun AiAnalystCard(
    verdict: AiVerdict?,
    model: String,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDeepDive: () -> Unit,
) {
    val buy = Color(0xFF16A34A)
    val sell = Color(0xFFDC2626)
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    fun sigColor(s: String) = when {
        s.contains("buy") -> buy
        s.contains("sell") -> sell
        else -> neutral
    }
    // Collapsed by default — the full analysis runs long; header + pill + one line is the summary.
    var open by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header: title + a filled, colored signal pill (the call at a glance).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AI Analyst", style = MaterialTheme.typography.labelLarge, color = neutral)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (verdict != null) {
                    val c = sigColor(verdict.signal)
                    Box(
                        modifier = Modifier
                            .background(c.copy(alpha = 0.16f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text(
                            verdict.signal.replace('_', ' ').uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = c,
                        )
                    }
                }
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Collapse analysis" else "Expand analysis",
                    tint = neutral,
                )
            }
        }
        if (verdict != null && !open) {
            // One-line summary while collapsed — tap the card for the full analysis.
            Text(
                "Conviction ${verdict.conviction}/100" +
                    (if (verdict.horizon.isNotBlank()) " · ${verdict.horizon}" else "") +
                    " · tap for the full analysis",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
        when {
            verdict != null && open -> {
                val c = sigColor(verdict.signal)
                // Conviction (with horizon) + a meter bar, matching SignalCard.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Conviction ${verdict.conviction}/100",
                        style = MaterialTheme.typography.labelMedium,
                        color = neutral,
                    )
                    if (verdict.horizon.isNotBlank()) {
                        Text(verdict.horizon, style = MaterialTheme.typography.labelSmall, color = neutral)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(neutral.copy(alpha = 0.18f), RoundedCornerShape(3.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((verdict.conviction / 100f).coerceIn(0.02f, 1f))
                            .height(6.dp)
                            .background(c, RoundedCornerShape(3.dp)),
                    )
                }
                if (verdict.thesis.isNotBlank()) {
                    Text(verdict.thesis, style = MaterialTheme.typography.bodyMedium)
                }
                if (expanded) {
                    ReasonBlock("Rationale", verdict.rationale)
                    ReasonBlock("Risks", verdict.keyRisks)
                    if (verdict.invalidation.isNotBlank()) {
                        Text("Invalidation", style = MaterialTheme.typography.labelMedium, color = neutral)
                        Text(verdict.invalidation, style = MaterialTheme.typography.bodySmall)
                    }
                    ReasonBlock("Catalysts", verdict.catalysts)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide detail" else "Why?")
                    }
                    TextButton(onClick = onDeepDive, enabled = !loading) { Text("Deep dive") }
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = neutral,
                        )
                    }
                }
            }
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = neutral)
                Text("Analyzing…", style = MaterialTheme.typography.bodySmall, color = neutral)
            }
            error != null -> {
                Text(error, style = MaterialTheme.typography.bodySmall, color = neutral)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
        // Footer: which model produced the verdict + the standing disclaimer. (Token/cost usage now
        // lives on the signals service's usage page, so it's no longer repeated per-card here.)
        if (open || verdict == null) {
            Text(
                (if (model.isNotBlank()) "$model · " else "Claude analyst · ") + "decision support, not advice",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
    }
}

/** "What if I put $X into this?" — an on-demand entry plan (action, zone, shares, stop/target). */
@Composable
private fun EntryPlanCard(
    plan: EntryPlan?,
    loading: Boolean,
    error: String?,
    onPlan: (Double) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val savedCash by ServiceLocator.settingsStore.investableCash.collectAsState(initial = 0.0)
    var cashText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedCash) {
        if (cashText == null && savedCash > 0) cashText = formatCashPlain(savedCash)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Entry plan", style = MaterialTheme.typography.labelLarge, color = neutral)
            if (plan != null) {
                val c = planActionColor(plan.action, neutral)
                Box(
                    modifier = Modifier
                        .background(c.copy(alpha = 0.16f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        planActionLabel(plan.action),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = c,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = cashText ?: "",
                onValueChange = { cashText = it },
                label = { Text("Cash to deploy") },
                prefix = { Text("$") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val cash = (cashText ?: "").replace(",", "").trim().toDoubleOrNull() ?: 0.0
                    onPlan(cash)
                },
                enabled = !loading,
            ) { Text("Plan") }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = neutral)
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral) }
        if (plan != null) {
            val c = planActionColor(plan.action, neutral)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Conviction ${plan.conviction}/100", style = MaterialTheme.typography.labelMedium, color = neutral)
                Text(
                    "${sharesText(plan.suggestedShares)} sh · ${usd(plan.allocationUsd)}",
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
                        .fillMaxWidth((plan.conviction / 100f).coerceIn(0.02f, 1f))
                        .height(6.dp)
                        .background(c, RoundedCornerShape(3.dp)),
                )
            }
            Text(
                "Entry ${usd(plan.entryLow)}–${usd(plan.entryHigh)} · stop ${usd(plan.stop)} · target ${usd(plan.target)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (plan.timing.isNotBlank()) {
                Text("When: ${plan.timing}", style = MaterialTheme.typography.bodySmall, color = neutral)
            }
            if (plan.thesis.isNotBlank()) {
                Text(plan.thesis, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Scenario planner · decision support, not advice", style = MaterialTheme.typography.labelSmall, color = neutral)
    }
}

@Composable
private fun ReasonBlock(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
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
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val sh = sharesText.toDoubleOrNull()
    val cost = costText.toDoubleOrNull()

    // ----- Holdings card -----
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Holdings", style = MaterialTheme.typography.labelLarge, color = neutral)
            // Live return pill — the position's health at a glance, styled like the signal pill.
            if (sh != null && sh > 0.0 && cost != null && cost > 0.0 && quote != null) {
                val gainPct = (quote.price - cost) / cost * 100.0
                val gUp = gainPct >= 0.0
                val pc = if (gUp) GainGreen else LossRed
                Box(
                    modifier = Modifier
                        .background(pc.copy(alpha = 0.16f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        "${if (gUp) "+" else ""}${"%.1f".format(gainPct)}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = pc,
                    )
                }
            }
        }
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
        if (sh != null && sh > 0.0 && quote != null) {
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(
                    "Position value",
                    Formatting.price(sh * quote.price, quote.currency, hideZeroCents),
                    modifier = Modifier.weight(1f),
                )
                if (cost != null && cost > 0.0) {
                    val gain = sh * (quote.price - cost)
                    val gainPct = (quote.price - cost) / cost * 100.0
                    val gUp = gain >= 0.0
                    StatCell(
                        "Total return",
                        Formatting.changeLine(gain, gainPct, gUp, hideZeroCents),
                        valueColor = if (gUp) GainGreen else LossRed,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (cost != null && cost > 0.0) {
                Text(
                    "Now ${Formatting.price(quote.price, quote.currency, hideZeroCents)} vs your " +
                        "${Formatting.price(cost, quote.currency, hideZeroCents)} average",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                )
            }
        }
    }

    // ----- Alerts card -----
    val activeAlerts = listOf(above, below, pctUp, pctDown).count { it.toDoubleOrNull() != null }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Alerts", style = MaterialTheme.typography.labelLarge, color = neutral)
            if (activeAlerts > 0) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        "$activeAlerts active",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Text("Price crosses", style = MaterialTheme.typography.labelMedium, color = neutral)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(above, { above = it }, label = { Text("Above $") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
            OutlinedTextField(below, { below = it }, label = { Text("Below $") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
        }
        Text("Daily move", style = MaterialTheme.typography.labelMedium, color = neutral)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(pctUp, { pctUp = it }, label = { Text("Up ≥ %") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
            OutlinedTextField(pctDown, { pctDown = it }, label = { Text("Down ≥ %") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
        }
        Text(
            "Get notified when the price crosses a level, or the day's move exceeds a percentage.",
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    }

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

/** Small labeled stat: caption above a bold value — used inside the Holdings card. */
@Composable
private fun StatCell(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
