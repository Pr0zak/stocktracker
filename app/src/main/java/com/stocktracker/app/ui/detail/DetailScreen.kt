package com.stocktracker.app.ui.detail

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.CalendarMonth
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.stocktracker.app.data.remote.CycleResponse
import com.stocktracker.app.data.remote.EntryPlan
import com.stocktracker.app.data.remote.InsiderResponse
import com.stocktracker.app.data.remote.QualityResponse
import com.stocktracker.app.data.remote.ShortPressureResponse
import com.stocktracker.app.data.remote.TouchStudyResponse
import com.stocktracker.app.data.remote.TrendResponse
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.signals.BacktestResult
import com.stocktracker.app.signals.SignalLabel
import com.stocktracker.app.signals.SignalResult
import com.stocktracker.app.ui.components.ChartLineOverlay
import com.stocktracker.app.ui.components.ChartMarker
import com.stocktracker.app.ui.components.ChartSubPane
import com.stocktracker.app.ui.components.FiftyTwoWeekRangeBar
import com.stocktracker.app.ui.components.ThresholdMeter
import com.stocktracker.app.ui.components.TwoHundredWeekLineBar
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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    asset: Asset,
    onBack: () -> Unit,
    onOpenCalendar: () -> Unit = {},
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
                    IconButton(onClick = onOpenCalendar) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = "This asset's calendar")
                    }
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
            // Past FTD spike settlement days (amber) — the "did fails line up with big moves?" visual.
            val ftdMarkers = if (indicators.contains(Indicator.FTD_SPIKES.key)) {
                (state.shortPressure?.ftdSpikeDates ?: emptyList()).mapNotNull { d ->
                    runCatching {
                        java.time.LocalDate.parse(d, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                            .atStartOfDay(java.time.ZoneOffset.UTC).plusHours(12).toInstant().toEpochMilli()
                    }.getOrNull()?.let { ChartMarker(it, Color(0xFFD97706), "FTD") }
                }
            } else {
                emptyList()
            }
            // Past BTC halving dates (visible on 3Y/ALL ranges) — the cycle anchor points.
            val halvingMarkers = if (indicators.contains(Indicator.HALVING.key)) {
                (state.cycleInfo?.halvingDates ?: emptyList()).mapNotNull { d ->
                    runCatching {
                        java.time.LocalDate.parse(d)
                            .atStartOfDay(java.time.ZoneOffset.UTC).plusHours(12).toInstant().toEpochMilli()
                    }.getOrNull()?.let { ChartMarker(it, Color(0xFF8B5CF6), "Halving") }
                }
            } else {
                emptyList()
            }
            val allMarkers = divMarkers + ftdMarkers + halvingMarkers
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
                        // The 200-week line drawn on the chart — long ranges only (off-scale on 1D/1W).
                        sma200wLine = if (!percentMode &&
                            state.range in setOf(ChartRange.YEAR, ChartRange.THREE_YEAR, ChartRange.ALL)
                        ) state.stockTrend?.sma200w else null,
                        overlays = allOverlays,
                        subPanes = indicatorResult.subPanes,
                        markers = allMarkers,
                        onScrubChange = { scrubbed = it },
                        valueFormatter = chartValueFormatter,
                        timeFormatter = chartTimeFormatter,
                    )
                    // No data at all (delisted/expired symbol) → say so, no pointless retry.
                    state.chartNoData -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Chart unavailable — no historical data for this symbol",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (quote != null) {
                            Text(
                                "Live price still tracked above.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // A transient failure is offered a retry.
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

            // Warrant/OTC symbols Yahoo doesn't carry are charted from the signals service's Webull
            // fallback — say so, since it's an unofficial source.
            if (state.chartSource == "webull" && state.chart.size >= 2) {
                Text(
                    "History via Webull (Yahoo has none for this symbol)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

            if (state.signal != null || state.aiEnabled) {
                SignalsCard(
                    signal = state.signal,
                    backtest = state.backtest,
                    verdict = state.aiVerdict,
                    model = state.aiModel,
                    loading = state.aiLoading,
                    error = state.aiError,
                    aiEnabled = state.aiEnabled,
                    onAnalyze = { vm.requestAiVerdict(deep = false) },
                    onDeepDive = { vm.requestAiVerdict(deep = true) },
                )
            }
            state.shortPressure?.let { ShortPressureCard(it) }
            state.insider?.let { InsiderBuyingCard(it) }
            state.cycleInfo?.let { HalvingCycleCard(it) }
            state.stockTrend?.let { StockTrendCard(it, state.touchStudy) }
            state.quality?.let { QualityCard(it) }

            if (state.aiEnabled) {
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
    FTD_SPIKES("ftd", "FTD spike markers"),
    HALVING("halv", "Halving markers (BTC)"),
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

private fun aiDirectionalScore(v: AiVerdict): Int {
    // The AI's conviction is confidence in its LABEL, not a direction — map both onto the rule
    // engine's 0-100 directional scale (50 neutral) so the two reads are visually comparable.
    val dir = when (v.signal) {
        "strong_buy" -> 1.0
        "buy" -> 0.5
        "sell" -> -0.5
        "strong_sell" -> -1.0
        else -> 0.0
    }
    return (50 + dir * (v.conviction.coerceIn(0, 100) / 100.0) * 50).roundToInt()
}

/** bullish = +1, neutral = 0, bearish = -1 — for consensus/divergence between the two reads. */
private fun ruleBucket(l: SignalLabel): Int = when (l) {
    SignalLabel.STRONG_BUY, SignalLabel.BUY -> 1
    SignalLabel.SELL, SignalLabel.STRONG_SELL -> -1
    SignalLabel.HOLD -> 0
}

private fun aiBucket(signal: String): Int = when {
    signal.contains("buy") -> 1
    signal.contains("sell") -> -1
    else -> 0
}

/**
 * One unified card for both reads on this chart — the mechanical rule engine and the Claude
 * analyst — on the same directional 0-100 scale, with an explicit consensus/divergence pill.
 * Collapsed by default; the AI half only runs when the user taps Analyze (token cost is opt-in).
 */
@Composable
private fun SignalsCard(
    signal: SignalResult?,
    backtest: BacktestResult?,
    verdict: AiVerdict?,
    model: String,
    loading: Boolean,
    error: String?,
    aiEnabled: Boolean,
    onAnalyze: () -> Unit,
    onDeepDive: () -> Unit,
) {
    val buy = Color(0xFF16A34A)
    val sell = Color(0xFFDC2626)
    val mixed = Color(0xFFD97706)
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    fun bucketColor(b: Int) = when {
        b > 0 -> buy
        b < 0 -> sell
        else -> neutral
    }
    fun reasonColor(score: Double) = when {
        score > 0.05 -> buy
        score < -0.05 -> sell
        else -> neutral
    }

    var open by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf(false) }

    val rb = signal?.let { ruleBucket(it.label) }
    val ab = verdict?.let { aiBucket(it.signal) }
    val diverging = rb != null && ab != null && rb != ab
    // Consensus pill: agreement shows the shared read; disagreement is called out as MIXED.
    val (pillText, pillColor) = when {
        rb != null && ab != null && diverging -> "MIXED" to mixed
        ab != null -> when {
            ab > 0 -> "BULLISH" to buy
            ab < 0 -> "BEARISH" to sell
            else -> "NEUTRAL" to neutral
        }
        rb != null -> when {
            rb > 0 -> "BULLISH" to buy
            rb < 0 -> "BEARISH" to sell
            else -> "NEUTRAL" to neutral
        }
        else -> null to neutral
    }

    @Composable
    fun meterRow(label: String, score: Int, text: String, color: Color) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = neutral)
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(neutral.copy(alpha = 0.18f), RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((score / 100f).coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(color, RoundedCornerShape(3.dp)),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header: consensus pill + chevron.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Signals", style = MaterialTheme.typography.labelLarge, color = neutral)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pillText != null) {
                    Box(
                        modifier = Modifier
                            .background(pillColor.copy(alpha = 0.16f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text(
                            pillText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = pillColor,
                        )
                    }
                }
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Collapse signals" else "Expand signals",
                    tint = neutral,
                )
            }
        }

        // Collapsed: both reads in one line.
        if (!open) {
            val rulesPart = signal?.let { "Rules ${it.label.display} ${it.score}" } ?: "Rules —"
            val aiPart = when {
                verdict != null -> "AI ${verdict.signal.replace('_', ' ')} ${aiDirectionalScore(verdict)}"
                aiEnabled -> "AI not run"
                else -> null
            }
            Text(
                listOfNotNull(rulesPart, aiPart).joinToString(" · ") + " · tap for detail",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }

        if (open) {
            // --- Rule engine ---
            signal?.let { s ->
                meterRow("Rule engine", s.score, "${s.label.display} · ${s.score}/100", bucketColor(ruleBucket(s.label)))
                s.components.take(2).forEach { c ->
                    Text(c.reason, style = MaterialTheme.typography.bodySmall, color = reasonColor(c.score))
                }
                s.regimeNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = neutral)
                }
                // Backtest = the honesty check: did this rule signal beat buy-and-hold, after costs?
                backtest?.let { bt ->
                    val edge = bt.edgeVsBuyHoldPct
                    val edgeColor = if (edge >= 0) buy else sell
                    Text(
                        "Backtest (${bt.bars} bars): signal ${"%+.0f%%".format(bt.strategyReturnPct)} vs " +
                            "buy-hold ${"%+.0f%%".format(bt.buyHoldReturnPct)} = " +
                            "${"%+.0f%%".format(edge)} edge",
                        style = MaterialTheme.typography.labelSmall,
                        color = edgeColor,
                    )
                    Text(
                        "${bt.trades} trades · ${"%.0f".format(bt.winRatePct)}% win · " +
                            "${"%.0f".format(bt.maxDrawdownPct)}% max drawdown · " +
                            "${"%.0f".format(bt.exposurePct)}% in market",
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                }
            }

            // --- Claude analyst ---
            if (aiEnabled) {
                when {
                    verdict != null -> {
                        meterRow(
                            "AI analyst",
                            aiDirectionalScore(verdict),
                            verdict.signal.replace('_', ' ').replaceFirstChar { it.uppercase() } +
                                " · conviction ${verdict.conviction}" +
                                (if (verdict.horizon.isNotBlank()) " · ${verdict.horizon}" else ""),
                            bucketColor(aiBucket(verdict.signal)),
                        )
                        if (diverging) {
                            Text(
                                "Reads diverge — the AI explains the difference under Why?",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = mixed,
                            )
                        }
                        if (verdict.thesis.isNotBlank()) {
                            Text(verdict.thesis, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (why) {
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
                            TextButton(onClick = { why = !why }) {
                                Text(if (why) "Hide detail" else "Why?")
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
                        TextButton(onClick = onAnalyze) { Text("Retry") }
                    }
                    else -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "AI second opinion — one model call, only when you tap.",
                            style = MaterialTheme.typography.bodySmall,
                            color = neutral,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onAnalyze) { Text("Analyze") }
                    }
                }
            }

            val modelTag = if (verdict != null && model.isNotBlank()) "$model · " else ""
            Text(
                "${modelTag}daily bars · decision support, not advice",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
    }
}

/**
 * Halving cycle + multi-year trend card (crypto): where we are in the ~4-year cycle, what prior
 * cycles did from this position, and the long-term structural metrics. Honest about the sample
 * size — four halvings is anecdote, not statistics. Collapsed by default.
 */
@Composable
private fun HalvingCycleCard(ci: CycleResponse) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val purple = Color(0xFF8B5CF6)
    var open by remember { mutableStateOf(false) }
    val hc = ci.halvingCycle
    val lt = ci.longTermTrend

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (hc != null) "Halving cycle" else "Long-term trend",
                style = MaterialTheme.typography.labelLarge,
                color = neutral,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                hc?.cyclePct?.let { pct ->
                    Box(
                        modifier = Modifier
                            .background(purple.copy(alpha = 0.16f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text(
                            "%.0f%%".format(pct),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = purple,
                        )
                    }
                }
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Collapse cycle" else "Expand cycle",
                    tint = neutral,
                )
            }
        }
        if (!open) {
            val parts = listOfNotNull(
                hc?.let { "${it.daysToNextEst}d to next halving (est.)" },
                lt?.priceVs200wSmaPct?.let { "%+.1f%% vs 200w SMA".format(it) },
                lt?.mayerMultiple?.let { "Mayer %.2f".format(it) },
            )
            Text(
                (parts.joinToString(" · ").ifBlank { "long-term data" }) + " · tap for detail",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
        if (open) {
            hc?.let { h ->
                // Cycle progress: last halving ──────●────── next (est.)
                h.cyclePct?.let { pct ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(neutral.copy(alpha = 0.18f), RoundedCornerShape(3.dp)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((pct / 100f).toFloat().coerceIn(0.02f, 1f))
                                .height(6.dp)
                                .background(purple, RoundedCornerShape(3.dp)),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(h.lastHalving, style = MaterialTheme.typography.labelSmall, color = neutral)
                    Text(
                        "${h.daysSinceHalving}d in · ${h.daysToNextEst}d left",
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                    Text("${h.nextHalvingEst} (est.)", style = MaterialTheme.typography.labelSmall, color = neutral)
                }
                if (h.phase.isNotBlank()) {
                    Text(h.phase.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
                }
                h.pastCycleAnalog?.let { a ->
                    Text(
                        "From this cycle position, the ${a.priorCyclesMeasured} prior cycles returned a median " +
                            "${a.medianFwd12moPct?.let { "%+.0f%%".format(it) } ?: "—"} over the next 12 months " +
                            "(worst ${a.worstFwd12moPct?.let { "%+.0f%%".format(it) } ?: "—"}, best " +
                            "${a.bestFwd12moPct?.let { "%+.0f%%".format(it) } ?: "—"}). Four halvings ever — " +
                            "weak-sample history, not a law.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            lt?.let { t ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    t.priceVs200wSmaPct?.let {
                        StatCell("vs 200w SMA", "%+.1f%%".format(it), modifier = Modifier.weight(1f))
                    }
                    t.mayerMultiple?.let {
                        StatCell("Mayer multiple", "%.2f".format(it), modifier = Modifier.weight(1f))
                    }
                    t.pctOffAllTimeHigh?.let {
                        StatCell("Off ATH", "%.1f%%".format(it), modifier = Modifier.weight(1f))
                    }
                }
                t.cagr3yPct?.let {
                    Text(
                        "3-year CAGR %+.1f%% over %.0f years of history".format(it, t.historyYears ?: 0.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                }
            }
            Text(
                "Cycle context, not timing — enable “Halving markers” in Indicators to see past " +
                    "halvings on the 3Y/ALL chart · not advice",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
    }
}

/**
 * Below-the-200-week-line card (stocks): the equity mirror of the crypto cycle card. Where price
 * sits vs its 200-week SMA (the mungbeans "line"), the below-line zone + recovering/deepening
 * direction, a 14-week RSI oversold read, and — when the name has history below the line — what
 * happened the last N times it was there (forward returns vs the S&P). Long-term mean-reversion
 * CONTEXT, deliberately not folded into the momentum signal. Free data, auto-loaded, collapsed.
 */
@Composable
private fun StockTrendCard(tr: TrendResponse, touch: TouchStudyResponse?) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val below = tr.belowLine == true
    // Amber = below the line (a heads-up, not a buy); neutral otherwise — keeps the stance neutral.
    val accent = if (below) Color(0xFFD29922) else neutral
    var open by remember { mutableStateOf(false) }

    val zoneLabel = tr.zone?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
    val dirLabel = when (tr.direction) {
        "recovering" -> "↑ Recovering"
        "deepening" -> "↓ Deepening"
        "approaching" -> "↓ Approaching"
        "moving_away" -> "↑ Moving away"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("200-week line", style = MaterialTheme.typography.labelLarge, color = neutral)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tr.priceVs200wSmaPct?.let { pct ->
                    Box(
                        modifier = Modifier
                            .background(accent.copy(alpha = 0.16f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text(
                            "%+.1f%%".format(pct),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                        )
                    }
                }
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Collapse trend" else "Expand trend",
                    tint = neutral,
                )
            }
        }
        if (!open) {
            val parts = listOfNotNull(
                tr.priceVs200wSmaPct?.let {
                    "%+.1f%% %s the 200-week line".format(it, if (below) "below" else "above")
                },
                zoneLabel,
                dirLabel,
                tr.weeklyOversold?.takeIf { it }?.let { "14w RSI oversold" },
                tr.volumeSignal?.takeIf { it != "neutral" }?.replace('_', ' '),
            )
            Text(
                (parts.joinToString(" · ").ifBlank { "long-term trend" }) + " · tap for detail",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
        if (open) {
            // Visual: where price sits relative to its 200-week line (the line fixed at centre).
            tr.priceVs200wSmaPct?.let { pct -> TwoHundredWeekLineBar(pct, below) }
            // %-from-line lives in the bar above; the cells cover the rest.
            Row(modifier = Modifier.fillMaxWidth()) {
                tr.sma200w?.let {
                    StatCell("200w SMA", "%.2f".format(it), modifier = Modifier.weight(1f))
                }
                zoneLabel?.let { StatCell("Zone", it, modifier = Modifier.weight(1f)) }
                tr.pctOffAllTimeHigh?.let {
                    StatCell("Off ATH", "%.1f%%".format(it), modifier = Modifier.weight(1f))
                }
            }
            dirLabel?.let { d ->
                Text("Direction: $d", style = MaterialTheme.typography.labelSmall, color = neutral)
            }
            // RSI as a mini gauge — green when oversold (<30), red when overbought (>70).
            tr.rsi14w?.let { rsi ->
                val rsiColor = when {
                    rsi < 30 -> Color(0xFF2E9E57)
                    rsi > 70 -> Color(0xFFB0543D)
                    else -> neutral
                }
                ThresholdMeter(
                    "14-week RSI",
                    "%.0f".format(rsi) + if (tr.weeklyOversold == true) " · oversold" else "",
                    (rsi / 100.0).toFloat(),
                    rsiColor,
                    thresholdFraction = 0.30f,
                )
            }
            tr.cagr3yPct?.let {
                Text(
                    "3-year CAGR %+.1f%% over %.0f years of history".format(it, tr.historyYears ?: 0.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                )
            }
            tr.volumeSignal?.takeIf { it != "neutral" }?.let { vs ->
                Text(
                    "Weekly volume: ${vs.replace('_', ' ')}" + (tr.rvol14?.let { " · RVOL %.1f".format(it) } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                )
            }
            // Historical touch study — evidence, not a signal. Only when the name has been below before.
            touch?.takeIf { it.touchCount > 0 && it.medianFwd12mPct != null }?.let { t ->
                val body = buildString {
                    append("Last ${t.touchCount} time")
                    if (t.touchCount != 1) append("s")
                    append(" below its 200-week line: median ")
                    append("%+.1f%%".format(t.medianFwd12mPct))
                    append(" over the next 12 months")
                    t.pctPositive12m?.let { append(" (${it}% higher)") }
                    t.spyAvgFwd12mPct?.let { append(" · S&P %+.1f%% over the same windows".format(it)) }
                    append(".")
                    if (t.measured12m < 3) append(" Small sample (${t.measured12m}) — anecdote, not a rule.")
                }
                Text(body, style = MaterialTheme.typography.bodySmall)
                t.pctPositive12m?.let { pp ->
                    ThresholdMeter(
                        "Resolved higher after a dip",
                        "$pp% of ${t.touchCount}",
                        (pp / 100.0).toFloat(),
                        if (pp >= 50) Color(0xFF2E9E57) else Color(0xFFB0543D),
                        thresholdFraction = 0.5f,
                    )
                }
            }
            Text(
                "Long-term mean-reversion context — a low reading isn't a buy signal on its own · not advice",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
    }
}

/**
 * Insider-buying card (stocks): open-market Form 4 PURCHASES over the last 12 months — the bullish
 * informed-money mirror of the short-pressure card. Only shown when there were actual buys. Free data.
 */
@Composable
private fun InsiderBuyingCard(ins: InsiderResponse) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val green = Color(0xFF2E9E57)
    var open by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Insider buying", style = MaterialTheme.typography.labelLarge, color = neutral)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .background(green.copy(alpha = 0.16f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        fmtUsdCompact(ins.buyTotal12m),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = green,
                    )
                }
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Collapse insider" else "Expand insider",
                    tint = neutral,
                )
            }
        }
        if (!open) {
            val parts = listOfNotNull(
                "${ins.buyCount12m} open-market buy${if (ins.buyCount12m != 1) "s" else ""} (12mo)",
                if (ins.hasConvictionBuy) "conviction" else null,
                if (ins.hasClusterBuy) "cluster" else null,
            )
            Text(
                parts.joinToString(" · ") + " · tap for detail",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
        if (open) {
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell("Largest buy", fmtUsdCompact(ins.largestBuyValue), valueColor = green, modifier = Modifier.weight(1f))
                StatCell("12-mo total", fmtUsdCompact(ins.buyTotal12m), modifier = Modifier.weight(1f))
                StatCell("Buys (12mo)", "${ins.buyCount12m}", modifier = Modifier.weight(1f))
            }
            if (ins.hasConvictionBuy || ins.hasClusterBuy) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ins.hasConvictionBuy) InsiderChip("Conviction buy (≥\$500k)", green)
                    if (ins.hasClusterBuy) InsiderChip("Cluster (3+ insiders)", green)
                }
            }
            ins.latestBuys.take(3).forEach { b ->
                Text(
                    "${b.date} · ${b.name.ifBlank { "Insider" }} · ${fmtUsdCompact(b.value)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Open-market Form 4 purchases — a modest bullish base rate; confirming context, not timing · not advice",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
    }
}

/**
 * Quality card (stocks): business-quality descriptors from Finnhub basic-financials — ROE, margins,
 * debt-to-equity, and Buffett-quality / wide-moat / dividend-aristocrat flags. Stance-NEUTRAL context.
 */
@Composable
private fun QualityCard(q: QualityResponse) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val blue = Color(0xFF4666CF)
    var open by remember { mutableStateOf(false) }
    val headline = when {
        q.buffettQuality -> "Buffett quality"
        q.wideMoat -> "Wide moat"
        q.dividendAristocrat -> "Aristocrat"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Quality", style = MaterialTheme.typography.labelLarge, color = neutral)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (headline != null) {
                    Box(
                        modifier = Modifier
                            .background(blue.copy(alpha = 0.16f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text(
                            headline,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = blue,
                        )
                    }
                }
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Collapse quality" else "Expand quality",
                    tint = neutral,
                )
            }
        }
        if (!open) {
            val parts = listOfNotNull(
                q.roe?.let { "ROE %.0f%%".format(it) },
                q.grossMargin?.let { "gross %.0f%%".format(it) },
                q.debtToEquity?.let { "D/E %.2f".format(it) },
            )
            Text(
                (parts.joinToString(" · ").ifBlank { "quality metrics" }) + " · tap for detail",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
        if (open) {
            // Mini-meters with the "good" threshold ticked, instead of bare numbers.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                q.roe?.let {
                    ThresholdMeter("ROE", "%.0f%%".format(it), (it / 40.0).toFloat(),
                        if (it > 15) Color(0xFF2E9E57) else neutral, thresholdFraction = 15f / 40f)
                }
                q.grossMargin?.let {
                    ThresholdMeter("Gross margin", "%.0f%%".format(it), (it / 100.0).toFloat(),
                        if (it > 40) Color(0xFF2E9E57) else neutral, thresholdFraction = 0.40f)
                }
                q.debtToEquity?.let {
                    ThresholdMeter("Debt / equity", "%.2f".format(it), (it / 2.0).toFloat(),
                        if (it < 0.5) Color(0xFF2E9E57) else Color(0xFFD29922), thresholdFraction = 0.5f / 2f)
                }
            }
            if (q.hasAnyFlag) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (q.buffettQuality) InsiderChip("Buffett quality", blue)
                    if (q.wideMoat) InsiderChip("Wide moat", blue)
                    if (q.dividendAristocrat) InsiderChip("Dividend aristocrat", blue)
                    if (q.highRoe) InsiderChip("High ROE", blue)
                    if (q.lowDebt) InsiderChip("Low debt", blue)
                }
            }
            Text(
                "Business-quality descriptors (Finnhub) — durability context, not a buy/sell signal · not advice",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
    }
}

@Composable
private fun InsiderChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Compact USD for insider dollar values: 9186831 → "$9.2M". */
private fun fmtUsdCompact(v: Long): String = when {
    v >= 1_000_000_000 -> "\$%.1fB".format(v / 1e9)
    v >= 1_000_000 -> "\$%.1fM".format(v / 1e6)
    v >= 1_000 -> "\$%.0fK".format(v / 1e3)
    else -> "\$$v"
}

/** "20260611" → "06-11" for compact chart axis labels. */
private fun fmtYmd(d: String): String = if (d.length == 8) "${d.substring(4, 6)}-${d.substring(6)}" else d

/**
 * Short-pressure card (stocks): official FINRA short interest + daily short volume + SEC FTDs,
 * a quiet/fuel/ignition state, this symbol's own after-FTD-spike track record, and upcoming key
 * dates. Free data — auto-loaded, collapsed by default.
 */
@Composable
private fun ShortPressureCard(sp: ShortPressureResponse) {
    val buy = Color(0xFF16A34A)
    val sell = Color(0xFFDC2626)
    val amber = Color(0xFFD97706)
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val (stateLabel, stateColor) = when (sp.state) {
        "ignition" -> "IGNITION" to sell // high-risk fireworks, not a calm buy — flag it hot
        "fuel" -> "FUEL" to amber
        else -> "QUIET" to neutral
    }
    var open by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Short pressure", style = MaterialTheme.typography.labelLarge, color = neutral)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .background(stateColor.copy(alpha = 0.16f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        stateLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = stateColor,
                    )
                }
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Collapse short pressure" else "Expand short pressure",
                    tint = neutral,
                )
            }
        }
        if (!open) {
            Text(
                listOfNotNull(
                    sp.daysToCover?.let { "DTC %.1f".format(it) },
                    sp.shortVolRatio5d?.let { "short vol %.0f%%".format(it * 100) },
                    sp.ftdTrend?.let { "FTDs $it" },
                ).joinToString(" · ") + " · tap for detail",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
        if (open) {
            sp.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Row(modifier = Modifier.fillMaxWidth()) {
                sp.shortInterest?.let {
                    StatCell("Short interest", "%,d".format(it), modifier = Modifier.weight(1f))
                }
                sp.daysToCover?.let {
                    StatCell("Days to cover", "%.1f".format(it), modifier = Modifier.weight(1f))
                }
                sp.shortVolRatio5d?.let {
                    StatCell("Short vol (5d)", "%.0f%%".format(it * 100), modifier = Modifier.weight(1f))
                }
            }
            sp.siDate?.let {
                Text(
                    "Official FINRA short interest as of $it (published ~9 business days later).",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                )
            }
            sp.eventStudy?.let { es ->
                val f5 = es.fwd5MedianPct?.let { "%+.1f%%".format(it) } ?: "—"
                val f10 = es.fwd10MedianPct?.let { "%+.1f%%".format(it) } ?: "—"
                val hit = es.fwd10HitRate?.let { " · up %d%% of the time".format((it * 100).roundToInt()) } ?: ""
                Text(
                    "This symbol after its ${es.events} past FTD spikes: median $f5 in 5 days, " +
                        "$f10 in 10$hit — history, not a promise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if ((es.fwd10MedianPct ?: 0.0) > 0) buy else neutral,
                )
            }
            // FTD history as bars — spike-vs-baseline is visible at a glance.
            if (sp.ftdSeries.size >= 3) {
                Text(
                    "Fails-to-deliver, last ${sp.ftdSeries.size} settlement days",
                    style = MaterialTheme.typography.labelMedium,
                    color = neutral,
                )
                val bars = sp.ftdSeries
                Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    val maxQ = bars.maxOf { it.qty }.coerceAtLeast(1L).toFloat()
                    val bw = size.width / bars.size
                    bars.forEachIndexed { i, b ->
                        val h = (b.qty / maxQ) * size.height
                        drawRect(
                            color = amber,
                            topLeft = Offset(i * bw + bw * 0.15f, size.height - h),
                            size = Size(bw * 0.7f, h.coerceAtLeast(1f)),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtYmd(bars.first().date), style = MaterialTheme.typography.labelSmall, color = neutral)
                    Text(
                        "peak %,d".format(bars.maxOf { it.qty }),
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                    Text(fmtYmd(bars.last().date), style = MaterialTheme.typography.labelSmall, color = neutral)
                }
            }
            // Days-to-cover trend — is the covering fuel building or draining across SI reports?
            val dtcPts = sp.siHistory.mapNotNull { it.dtc }
            if (dtcPts.size >= 3) {
                Text("Days-to-cover trend", style = MaterialTheme.typography.labelMedium, color = neutral)
                Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                    val minV = dtcPts.min().toFloat()
                    val maxV = dtcPts.max().toFloat().coerceAtLeast(minV + 0.1f)
                    val stepX = size.width / (dtcPts.size - 1)
                    val path = Path()
                    dtcPts.forEachIndexed { i, v ->
                        val y = size.height - ((v.toFloat() - minV) / (maxV - minV)) * size.height
                        if (i == 0) path.moveTo(0f, y) else path.lineTo(i * stepX, y)
                    }
                    drawPath(path, color = amber, style = Stroke(width = 4f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(sp.siHistory.first().date, style = MaterialTheme.typography.labelSmall, color = neutral)
                    Text(
                        "%.1f → %.1f".format(dtcPts.first(), dtcPts.last()),
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                    Text(sp.siHistory.last().date, style = MaterialTheme.typography.labelSmall, color = neutral)
                }
            }
            if (sp.upcoming.isNotEmpty()) {
                Text("Upcoming dates", style = MaterialTheme.typography.labelMedium, color = neutral)
                sp.upcoming.take(4).forEach { u ->
                    Text("${u.date} — ${u.label}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "FINRA + SEC data · SI and FTDs publish with a lag — context, not timing · not advice",
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
