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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
import com.stocktracker.app.data.remote.OptionCandidate
import com.stocktracker.app.data.remote.OptionsResponse
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
import com.stocktracker.app.ui.theme.TrafficAmber
import com.stocktracker.app.ui.theme.TrafficGreen
import com.stocktracker.app.ui.theme.TrafficRed
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

            // Snapshot — one-glance rollup of the lenses below (stocks only, when ≥2 are available).
            if (!isCrypto) {
                val snapCount = listOf(
                    state.signal != null || state.aiVerdict != null,
                    state.stockTrend != null,
                    state.quality?.let { it.hasAnyFlag || it.hasMetrics } == true,
                    state.insider?.let { it.buyCount12m > 0 } == true,
                    state.shortPressure != null,
                ).count { it }
                if (snapCount >= 2) {
                    SnapshotCard(
                        signal = state.signal,
                        verdict = state.aiVerdict,
                        aiEnabled = state.aiEnabled,
                        trend = state.stockTrend,
                        quality = state.quality,
                        insider = state.insider,
                        shortPressure = state.shortPressure,
                    )
                }
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
                    currentPrice = quote?.price,
                    loading = state.planLoading,
                    error = state.planError,
                    onPlan = { cash -> vm.requestPlan(cash) },
                )
            }

            // "Play with calls" — a beginner-first long-call suggester. Stocks/ETFs only (options
            // aren't offered for crypto), gated on the Signals URL — NOT the AI switch (it's free math).
            if (!isCrypto && state.signalsConfigured) {
                PlayWithCallsCard(
                    symbol = asset.symbol,
                    options = state.options,
                    loading = state.optionsLoading,
                    error = state.optionsError,
                    onSuggest = { budget, style -> vm.requestOptions(budget, style) },
                )
            }

            HoldingsAndAlertsSection(
                symbol = asset.symbol,
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

/** One row of the Snapshot rollup: a lens, its plain read, a detail sub-line, a sentiment bucket
 *  (+1 bullish / 0 neutral / -1 bearish) and the colour that read paints in. */
private data class SnapFactor(
    val name: String,
    val read: String,
    val sub: String,
    val bucket: Int,
    val color: androidx.compose.ui.graphics.Color,
)

/**
 * "Snapshot" — the top-of-detail rollup of every lens below (momentum, value, quality, smart money,
 * short pressure) into one glance. The headline word calls out whether the reads AGREE or CONFLICT —
 * "Mixed" whenever at least one lens is bullish and another bearish (NKE's classic case: bearish
 * momentum but cheap + insiders buying) — rather than averaging a real disagreement into one number.
 * Each factor is a coloured one-liner; tap to expand the sub-detail. Context, not advice; the detailed
 * cards remain below for the drill-down.
 */
@Composable
private fun SnapshotCard(
    signal: SignalResult?,
    verdict: AiVerdict?,
    aiEnabled: Boolean,
    trend: TrendResponse?,
    quality: QualityResponse?,
    insider: InsiderResponse?,
    shortPressure: ShortPressureResponse?,
) {
    val buy = Color(0xFF16A34A)
    val sell = Color(0xFFDC2626)
    val amber = Color(0xFFD97706)
    val value = Color(0xFFD29922)
    val moat = Color(0xFF4666CF)
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

    val factors = buildList {
        // Momentum — the rule engine + (if run) the Claude read, same consensus logic as Signals.
        val rb = signal?.let { ruleBucket(it.label) }
        val ab = verdict?.let { aiBucket(it.signal) }
        if (rb != null || ab != null) {
            val diverging = rb != null && ab != null && rb != ab
            val bucket = when {
                diverging -> 0
                ab != null -> ab
                else -> rb ?: 0
            }
            val word = when {
                diverging -> "Mixed"
                bucket > 0 -> "Bullish"
                bucket < 0 -> "Bearish"
                else -> "Neutral"
            }
            val color = when {
                diverging -> amber
                bucket > 0 -> buy
                bucket < 0 -> sell
                else -> neutral
            }
            val sub = listOfNotNull(
                signal?.let { "Rules ${it.label.display} ${it.score}" },
                when {
                    verdict != null -> "AI ${verdict.signal.replace('_', ' ')}"
                    aiEnabled -> "AI not run"
                    else -> null
                },
            ).joinToString(" · ")
            add(SnapFactor("Momentum", word, sub, bucket, color))
        }

        // Value — where price sits vs its 200-week line.
        trend?.let { t ->
            val below = t.belowLine == true
            val pct = t.priceVs200wSmaPct
            val deep = t.zone?.let { it.contains("deep") || it.contains("extreme") } == true
            val (word, bucket, color) = when {
                below -> Triple(if (deep) "Deep value" else "Cheap", 1, value)
                pct != null && pct > 20.0 -> Triple("Extended", -1, sell)
                else -> Triple("Fair value", 0, neutral)
            }
            val sub = listOfNotNull(
                pct?.let { "%+.0f%% vs 200-wk line".format(it) },
                if (t.weeklyOversold == true) "weekly oversold" else null,
            ).joinToString(" · ")
            add(SnapFactor("Value", word, sub, bucket, color))
        }

        // Quality — durability flags + the headline metrics.
        quality?.let { q ->
            if (q.hasAnyFlag || q.hasMetrics) {
                val word = when {
                    q.wideMoat || q.buffettQuality -> "Wide moat"
                    q.dividendAristocrat -> "Div. aristocrat"
                    q.highRoe -> "High ROE"
                    q.lowDebt -> "Low debt"
                    else -> "Mixed"
                }
                val sub = listOfNotNull(
                    q.roe?.let { "ROE ${it.roundToInt()}%" },
                    q.grossMargin?.let { "gross ${it.roundToInt()}%" },
                    q.debtToEquity?.let { "D/E ${"%.2f".format(it)}" },
                ).joinToString(" · ")
                add(SnapFactor("Quality", word, sub, if (q.hasAnyFlag) 1 else 0, if (q.hasAnyFlag) moat else neutral))
            }
        }

        // Smart money — open-market insider buying (bullish informed money).
        insider?.let { ins ->
            if (ins.buyCount12m > 0) {
                val word = if (ins.hasConvictionBuy || ins.hasClusterBuy) "Buying" else "Some buying"
                val sub = listOfNotNull(
                    "${ins.buyCount12m} buys",
                    fmtUsdCompact(ins.buyTotal12m),
                    if (ins.hasClusterBuy) "cluster" else null,
                ).joinToString(" · ")
                add(SnapFactor("Smart money", word, sub, 1, buy))
            }
        }

        // Short pressure — informational; only Ignition weighs on the headline as a risk.
        shortPressure?.let { sp ->
            val (word, bucket, color) = when (sp.state) {
                "ignition" -> Triple("Ignition", -1, sell)
                "fuel" -> Triple("Building", 0, amber)
                else -> Triple("Quiet", 0, neutral)
            }
            val sub = listOfNotNull(
                sp.daysToCover?.let { "DTC ${"%.1f".format(it)}" },
                sp.ftdTrend?.let { "FTDs $it" },
            ).joinToString(" · ")
            add(SnapFactor("Short pressure", word, sub, bucket, color))
        }
    }

    if (factors.size < 2) return

    val hasBull = factors.any { it.bucket > 0 }
    val hasBear = factors.any { it.bucket < 0 }
    val (headline, headColor) = when {
        hasBull && hasBear -> "MIXED" to amber
        hasBull -> "BULLISH" to buy
        hasBear -> "BEARISH" to sell
        else -> "NEUTRAL" to neutral
    }

    var open by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Snapshot", style = MaterialTheme.typography.labelLarge, color = neutral)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .background(headColor.copy(alpha = 0.16f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(headline, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = headColor)
                }
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Collapse snapshot" else "Expand snapshot",
                    tint = neutral,
                )
            }
        }

        factors.forEach { f ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(9.dp).background(f.color, RoundedCornerShape(50)))
                Text(f.name, modifier = Modifier.width(100.dp), style = MaterialTheme.typography.labelMedium, color = neutral)
                Text(f.read, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = f.color)
            }
            if (open && f.sub.isNotBlank()) {
                Text(
                    f.sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                    modifier = Modifier.padding(start = 19.dp),
                )
            }
        }

        if (open) {
            Text(
                "Rolls up the cards below · context, not advice",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
    }
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
            // Dislocation: how unusual today's drawdown is vs this name's own history (z-score).
            tr.drawdownZ?.let { z ->
                val (word, zColor) = when {
                    z <= -2.0 -> "unusually deep" to Color(0xFF2E9E57)
                    z <= -1.0 -> "below typical" to Color(0xFFD29922)
                    z >= 1.0 -> "near highs" to neutral
                    else -> "typical range" to neutral
                }
                Text(
                    "Dislocation: ${"%+.1f".format(z)}σ · $word",
                    style = MaterialTheme.typography.labelSmall,
                    color = zColor,
                )
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
            // Free cash flow trend (MB-13) — rising green, falling clay.
            q.fcfTrend?.let { trend ->
                val fcfColor = when (trend) {
                    "rising" -> Color(0xFF2E9E57)
                    "falling" -> Color(0xFFB0543D)
                    else -> neutral
                }
                val latest = q.fcfLatest?.let { " · ${fmtUsdCompact(it)}/yr" } ?: ""
                val pos = if (q.fcfPositiveYears != null && q.fcfYears != null)
                    " · positive ${q.fcfPositiveYears}/${q.fcfYears}y" else ""
                Text("Free cash flow: $trend$latest$pos", style = MaterialTheme.typography.labelSmall, color = fcfColor)
            }
            // Share count (MB-14) — falling = buybacks (green), rising = dilution (clay).
            q.sharesChangePct?.let { chg ->
                val back = chg < 0
                Text(
                    "Share count: ${"%+.1f".format(chg)}% / ${q.sharesYears ?: 5}y · ${if (back) "buybacks" else "dilution"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (back) Color(0xFF2E9E57) else Color(0xFFB0543D),
                )
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
    currentPrice: Double?,
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
            val blue = Color(0xFF4666CF)
            val dipAmber = Color(0xFFD29922)
            val deploy = plan.allocationUsd
            // Stage the allocation ACROSS the entry zone — average in on weakness rather than one lump
            // entry. Levels step down from the top of the zone (or current price, if already in it).
            val hi = currentPrice?.takeIf { it in plan.entryLow..plan.entryHigh } ?: plan.entryHigh
            val levels = listOf(hi, (hi + plan.entryLow) / 2.0, plan.entryLow)
                .filter { it > 0.0 }
                .distinct()
            val perTranche = if (levels.isNotEmpty()) deploy / levels.size else 0.0

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (deploy > 0.0) "Deploy ${usd(deploy)} · ${levels.size} buys" else "No allocation now",
                    style = MaterialTheme.typography.labelMedium,
                    color = neutral,
                )
                Text("Conviction ${plan.conviction}/100", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = c)
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
            if (deploy > 0.0) {
                levels.forEachIndexed { i, price ->
                    val dropPct = if (hi > 0.0) (price / hi - 1.0) * 100.0 else 0.0
                    val near = i == 0 || dropPct > -1.0
                    val badge = if (near) "Now" else "%.0f%%".format(dropPct)
                    val badgeColor = when {
                        near -> c
                        dropPct <= -8.0 -> blue
                        else -> dipAmber
                    }
                    val shares = if (price > 0.0) perTranche / price else 0.0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(104.dp)
                                .background(badgeColor.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text("$badge · ${usd(price)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = badgeColor)
                        }
                        Text(
                            if (i == 0) "buy first" else "add on dip",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = neutral,
                        )
                        Text("${sharesText(shares)} sh · ${usd(perTranche)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Text("Stop ${usd(plan.stop)} · target ${usd(plan.target)}", style = MaterialTheme.typography.bodySmall, color = neutral)
            if (plan.timing.isNotBlank()) {
                Text("When: ${plan.timing}", style = MaterialTheme.typography.bodySmall, color = neutral)
            }
            if (plan.thesis.isNotBlank()) {
                Text(plan.thesis, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Scenario planner · staged buy, not advice", style = MaterialTheme.typography.labelSmall, color = neutral)
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
    symbol: String,
    quote: Quote?,
    hideZeroCents: Boolean,
    shares: Double?,
    avgCost: Double?,
    alerts: AssetAlerts,
    onSave: (Double?, Double?, AssetAlerts) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val owns = shares != null && shares > 0.0
    var showSheet by remember { mutableStateOf(false) }

    // ----- Your position (display-first; edit via the pencil) -----
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
            Text("Your position", style = MaterialTheme.typography.labelLarge, color = neutral)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (owns && avgCost != null && avgCost > 0.0 && quote != null) {
                    val gainPct = (quote.price - avgCost) / avgCost * 100.0
                    val gUp = gainPct >= 0.0
                    val pc = if (gUp) GainGreen else LossRed
                    val mag = if (gainPct < 0) -gainPct else gainPct
                    Box(
                        modifier = Modifier
                            .background(pc.copy(alpha = 0.16f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    ) {
                        Text(
                            "${if (gUp) "▲" else "▼"} ${"%.1f".format(mag)}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = pc,
                        )
                    }
                }
                IconButton(onClick = { showSheet = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit position", tint = neutral)
                }
            }
        }
        if (owns && quote != null) {
            Text(
                Formatting.price(shares!! * quote.price, quote.currency, hideZeroCents),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            val line = buildString {
                append("${Formatting.shares(shares)} sh")
                if (avgCost != null && avgCost > 0.0) {
                    append(" @ ${Formatting.price(avgCost, quote.currency, hideZeroCents)} avg")
                }
            }
            Text(line, style = MaterialTheme.typography.labelMedium, color = neutral)
            if (avgCost != null && avgCost > 0.0) {
                val gain = shares * (quote.price - avgCost)
                val gUp = gain >= 0.0
                Text(
                    "${Formatting.change(gain, hideZeroCents)} total return",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (gUp) GainGreen else LossRed,
                )
                CostVsNowBar(avgCost, quote.price, quote.currency, hideZeroCents)
            }
        } else {
            TextButton(onClick = { showSheet = true }) { Text("＋ Add position") }
        }
    }

    // ----- Alerts (toggle rows; arm/disarm live, set the numbers in the sheet) -----
    val activeAlerts = listOfNotNull(alerts.priceAbove, alerts.priceBelow, alerts.percentUp, alerts.percentDown).size

    @Composable
    fun AlertRow(label: String, valueText: String?, valueColor: androidx.compose.ui.graphics.Color, cleared: AssetAlerts) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Switch(
                checked = valueText != null,
                onCheckedChange = { checked -> if (!checked) onSave(shares, avgCost, cleared) else showSheet = true },
            )
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (valueText != null) MaterialTheme.colorScheme.onSurface else neutral,
            )
            Text(
                valueText ?: "tap ✎",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (valueText != null) valueColor else neutral.copy(alpha = 0.6f),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                        "$activeAlerts on",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        AlertRow(
            "Crosses above", alerts.priceAbove?.let { "$" + numText(it) }, GainGreen,
            AssetAlerts(priceAbove = null, priceBelow = alerts.priceBelow, percentUp = alerts.percentUp, percentDown = alerts.percentDown),
        )
        AlertRow(
            "Falls below", alerts.priceBelow?.let { "$" + numText(it) }, LossRed,
            AssetAlerts(priceAbove = alerts.priceAbove, priceBelow = null, percentUp = alerts.percentUp, percentDown = alerts.percentDown),
        )
        AlertRow(
            "Jumps in a day", alerts.percentUp?.let { "≥ " + numText(it) + "%" }, GainGreen,
            AssetAlerts(priceAbove = alerts.priceAbove, priceBelow = alerts.priceBelow, percentUp = null, percentDown = alerts.percentDown),
        )
        AlertRow(
            "Drops in a day", alerts.percentDown?.let { "≥ " + numText(it) + "%" }, LossRed,
            AssetAlerts(priceAbove = alerts.priceAbove, priceBelow = alerts.priceBelow, percentUp = alerts.percentUp, percentDown = null),
        )
        Text(
            "Flip a switch to disarm; tap ✎ to set or change a level.",
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    }

    if (showSheet) {
        EditPositionSheet(
            symbol = symbol,
            shares = shares,
            avgCost = avgCost,
            alerts = alerts,
            onDismiss = { showSheet = false },
            onSave = { s, c, a -> onSave(s, c, a); showSheet = false },
        )
    }
}

/** "How does price sit vs your average cost?" — a position bar with your cost fixed at centre; the
 *  marker sits right (green) when you're up, left (red) when under water. Clamped to ±25%. */
@Composable
private fun CostVsNowBar(avgCost: Double, price: Double, currency: String, hideZeroCents: Boolean) {
    val gainPct = (price - avgCost) / avgCost * 100.0
    val up = price >= avgCost
    val mark = if (up) GainGreen else LossRed
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val frac = ((gainPct.coerceIn(-25.0, 25.0) + 25.0) / 50.0).toFloat()
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
            val dot = 12.dp
            val dotX = (maxWidth - dot) * frac
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth().height(6.dp)
                    .background(neutral.copy(alpha = 0.16f), RoundedCornerShape(50)),
            )
            Box(Modifier.align(Alignment.Center).width(2.dp).height(14.dp).background(neutral.copy(alpha = 0.55f)))
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = dotX).size(dot)
                    .background(surface, RoundedCornerShape(50)).padding(2.dp).background(mark, RoundedCornerShape(50)),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("cost ${Formatting.price(avgCost, currency, hideZeroCents)}", style = MaterialTheme.typography.labelSmall, color = neutral)
            Text("now ${Formatting.price(price, currency, hideZeroCents)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = mark)
        }
    }
}

/** Bottom sheet to enter/edit shares, average cost, and alert levels — one atomic Save (shares +
 *  alerts written together, avoiding the two-write race). Reached from the ✎ or "＋ Add position". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPositionSheet(
    symbol: String,
    shares: Double?,
    avgCost: Double?,
    alerts: AssetAlerts,
    onDismiss: () -> Unit,
    onSave: (Double?, Double?, AssetAlerts) -> Unit,
) {
    var sharesText by remember { mutableStateOf(shares?.let { numText(it) } ?: "") }
    var costText by remember { mutableStateOf(avgCost?.let { numText(it) } ?: "") }
    var above by remember { mutableStateOf(alerts.priceAbove?.let { numText(it) } ?: "") }
    var below by remember { mutableStateOf(alerts.priceBelow?.let { numText(it) } ?: "") }
    var pctUp by remember { mutableStateOf(alerts.percentUp?.let { numText(it) } ?: "") }
    var pctDown by remember { mutableStateOf(alerts.percentDown?.let { numText(it) } ?: "") }
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Edit position · $symbol", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Holdings", style = MaterialTheme.typography.labelMedium, color = neutral)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sharesText, { sharesText = it }, label = { Text("Shares owned") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
                OutlinedTextField(costText, { costText = it }, label = { Text("Avg cost / sh") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
            }
            Text("Alerts", style = MaterialTheme.typography.labelMedium, color = neutral)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(above, { above = it }, label = { Text("Above $") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
                OutlinedTextField(below, { below = it }, label = { Text("Below $") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pctUp, { pctUp = it }, label = { Text("Up ≥ %") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
                OutlinedTextField(pctDown, { pctDown = it }, label = { Text("Down ≥ %") }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.weight(1f))
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
            ) { Text("Save") }
        }
    }
}

/**
 * "Play with calls" (OC-2) — a beginner-first long-call suggester. Leads with the money: cost, then
 * the max loss in RED, then break-even; a green/amber/red go-light with one plain sentence; warnings;
 * and a copy-pasteable Fidelity order ticket. Stocks/ETFs only (never crypto). Free server-side math,
 * no LLM — gated on the Signals URL, not the AI switch. Lazy: nothing fetched until "Suggest a call".
 * Not investment advice.
 */
@Composable
private fun PlayWithCallsCard(
    symbol: String,
    options: OptionsResponse?,
    loading: Boolean,
    error: String?,
    onSuggest: (Double, String) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val savedCash by ServiceLocator.settingsStore.investableCash.collectAsState(initial = 0.0)

    // Risk budget = the MOST you're OK losing (a bought call's max loss IS the whole premium). Default
    // to the saved investable cash if present, else $1000. Deliberately NOT persisted back — "money I'll
    // lose entirely" is a different quantity from the Ideas screen's deployable cash.
    var budgetText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedCash) {
        if (budgetText == null) budgetText = formatCashPlain(if (savedCash > 0) savedCash else 1000.0)
    }
    var style by remember { mutableStateOf("balanced") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Play with calls", style = MaterialTheme.typography.labelLarge, color = neutral)
        Text(
            "A call is the right to buy 100 shares at a set price before it expires. Only spend what " +
                "you're 100% OK losing entirely.",
            style = MaterialTheme.typography.bodySmall,
            color = neutral,
        )

        // Risk-budget input — labelled plainly as the max loss, not a target allocation.
        OutlinedTextField(
            value = budgetText ?: "",
            onValueChange = { budgetText = it },
            label = { Text("Most you're OK losing (max loss)") },
            prefix = { Text("$") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        // 3-way style toggle: Safer (higher delta, pricier) · Balanced · Cheaper (lower delta).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("safer" to "Safer", "balanced" to "Balanced", "cheaper" to "Cheaper").forEach { (key, label) ->
                FilterChip(selected = style == key, onClick = { style = key }, label = { Text(label) })
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val budget = (budgetText ?: "").replace(",", "").trim().toDoubleOrNull() ?: 0.0
                    onSuggest(budget, style)
                },
                enabled = !loading,
            ) { Text("Suggest a call") }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = neutral)
            }
        }

        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral) }

        if (options != null) {
            // Primary = the candidate matching the chosen style; fall back to the first offered.
            val primary = options.candidates.firstOrNull { it.profile == style }
                ?: options.candidates.firstOrNull()
            if (primary != null) {
                CallCandidateBlock(
                    symbol = symbol, options = options, c = primary,
                    onCopy = {
                        clipboard.setText(AnnotatedString(primary.orderTicket))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                )
                val others = options.candidates.filter { it !== primary }
                if (others.isNotEmpty()) {
                    Text("Other picks", style = MaterialTheme.typography.labelMedium, color = neutral)
                    others.forEach { alt -> CallCandidateCompact(alt) }
                }
            } else {
                Text("No suitable contract for that budget.", style = MaterialTheme.typography.bodySmall, color = neutral)
            }

            // Go/no-go traffic light + one plain sentence.
            val (lightColor, lightWord) = when (options.light.lowercase()) {
                "green" -> TrafficGreen to "GO"
                "yellow" -> TrafficAmber to "CAUTION"
                "red" -> TrafficRed to "NO-GO"
                else -> neutral to "—"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .background(lightColor.copy(alpha = 0.16f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(lightWord, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = lightColor)
                }
                if (options.lightReason.isNotBlank()) {
                    Text(options.lightReason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }

            // Earnings-before-expiry heads-up (IV-crush trap).
            options.earnings?.takeIf { it.inWindow }?.let { e ->
                Text(
                    "Earnings ${e.date ?: "soon"} falls before expiry — expect an IV drop after, even if the stock moves your way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TrafficAmber,
                )
            }

            // Any server warnings as a caution list.
            options.warnings.forEach { w ->
                Text("⚠ $w", style = MaterialTheme.typography.labelSmall, color = TrafficAmber)
            }

            if (options.quoteDelayed) {
                Text(
                    "Delayed · market closed — prices are indicative; confirm the live quote on Fidelity.",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                )
            }

            Text(
                "You buy on Fidelity — this is a suggestion, not a trade.",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = neutral,
            )
        }

        Text(
            "Long calls only · sell to close, don't exercise · not investment advice",
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    }
}

/** The full "four numbers first" block for the leading call candidate, with the Copy-ticket button. */
@Composable
private fun CallCandidateBlock(
    symbol: String,
    options: OptionsResponse,
    c: OptionCandidate,
    onCopy: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val n = c.contracts ?: 1
    val total = c.maxLoss ?: c.cost?.let { it * n }

    // Contract line: "UNH  Sep 17 '26  $420 Call"
    Text(
        "$symbol  ${fmtCallExpiry(options.expiry?.iso)}  ${c.strike?.let { usd(it) } ?: "—"} Call",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )

    // You pay ~$670/contract · N contracts = $X
    Text(
        buildString {
            append("You pay ~${c.cost?.let { usd(it) } ?: "—"}/contract")
            append(" · $n contract${if (n != 1) "s" else ""}")
            total?.let { append(" = ${usd(it)}") }
        },
        style = MaterialTheme.typography.bodyMedium,
    )

    // Most you can lose — LOUD, in red.
    Text(
        "Most you can lose: ${total?.let { usd(it) } ?: "—"}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = TrafficRed,
    )

    // Break-even $426.70 (+1.4%) · Δ0.55 · IV 33% · θ −$4/day
    val greeks = listOfNotNull(
        c.breakeven?.let { be ->
            "Break-even ${usd(be)}" + (c.breakevenPct?.let { " (%+.1f%%)".format(it) } ?: "")
        },
        c.delta?.let { "Δ%.2f".format(it) },
        c.iv?.let { "IV %.0f%%".format(it * 100) },
        c.theta?.let { fmtTheta(it) },
    )
    if (greeks.isNotEmpty()) {
        Text(greeks.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = neutral)
    }

    (options.expectedMove ?: c.expectedMove)?.let { em ->
        Text("Expected move ±${usd(em)} to expiry", style = MaterialTheme.typography.bodySmall, color = neutral)
    }
    options.structureNote.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = neutral)
    }

    // The order ticket to paste into Fidelity, shown then copied.
    if (c.orderTicket.isNotBlank()) {
        Text(
            c.orderTicket,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
    Button(onClick = onCopy, enabled = c.orderTicket.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text("Copy order ticket")
    }
}

/** A one-line compact read of an alternative candidate — profile, strike, cost, and max loss (red). */
@Composable
private fun CallCandidateCompact(c: OptionCandidate) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val n = c.contracts ?: 1
    val total = c.maxLoss ?: c.cost?.let { it * n }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${c.profile.replaceFirstChar { it.uppercase() }} · ${c.strike?.let { usd(it) } ?: "—"} Call" +
                (c.cost?.let { " · pay ${usd(it)}" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
            modifier = Modifier.weight(1f),
        )
        Text(
            "max loss ${total?.let { usd(it) } ?: "—"}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = TrafficRed,
        )
    }
}

/** ISO date ("2026-09-17" or a full timestamp) → "Sep 17 '26" for the contract line. */
private fun fmtCallExpiry(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return runCatching {
        java.time.LocalDate.parse(iso.take(10))
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d ''yy", java.util.Locale.US))
    }.getOrDefault(iso)
}

/** Theta ($/day per contract, usually negative) → "θ −$4/day". */
private fun fmtTheta(theta: Double): String {
    val sign = if (theta < 0) "−" else "+"
    return "θ $sign${usd(kotlin.math.abs(theta))}/day"
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
