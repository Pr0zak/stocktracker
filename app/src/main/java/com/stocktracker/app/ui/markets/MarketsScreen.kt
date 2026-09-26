package com.stocktracker.app.ui.markets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.stocktracker.app.data.remote.MarketBreadth
import com.stocktracker.app.data.remote.SignalsApiService
import kotlinx.coroutines.flow.first
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stocktracker.app.data.MarketContextStore
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.BackendStatusBanner
import com.stocktracker.app.ui.theme.Signal
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.stocktracker.app.data.model.VixZone
import com.stocktracker.app.data.remote.HeatmapResponse
import com.stocktracker.app.ui.components.Skeleton
import com.stocktracker.app.ui.components.spotlightGlow
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.PriceSmall
import java.util.Locale
import com.stocktracker.app.ui.detail.ageAgo
import com.stocktracker.app.ui.watchlist.DipRadarState
import com.stocktracker.app.util.readingAgeLabel

/**
 * The Markets hub — the one structural move in this overhaul.
 *
 * Five features used to be reachable through exactly one unlabelled glyph each, all of them parked
 * in the Watchlist's app bar: an `AutoAwesome` sparkle for the AI market brief, a `GridView` for the
 * heat map, a `Leaderboard` for a nightly scan of roughly three thousand names, a `CalendarMonth`
 * for catalysts. The dip radar and the VIX detail were worse than unlabelled — they were reachable
 * only by expanding a collapsed strip, and the dip list could not be opened at all on a day with no
 * dips, which is exactly the day its reject audit is worth reading.
 *
 * Meanwhile a permanent tab was spent on a gallery of mock prices whose entire job is a one-time
 * widget pin. That tab is what this screen takes.
 *
 * Rows say what they are. That is the whole idea, and it is not a clever one — it is the
 * conventional one this app had skipped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketsScreen(
    onOpenReports: () -> Unit = {},
    onOpenScan: () -> Unit = {},
    onOpenHeatmap: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenDips: () -> Unit = {},
    onOpenVix: () -> Unit = {},
    onOpenFunds: () -> Unit = {},
) {
    // The same reading the watchlist strip and the dip radar are looking at, from the one store
    // that holds it. A hub whose rows only describe what is behind each door in the abstract asks
    // the user to open all five to find out whether anything happened today.
    val ctx = ServiceLocator.marketContext
    val market by ctx.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        ctx.refreshScan()
        ctx.refreshVix()
    }
    // The market scan's OWN date. The store above holds the watchlist scan, a different nightly
    // job — its age was being shown under "Market scan" (15h, when the market scan was 1h old).
    // Null = not answered yet; Result.failure = could not read it.
    val marketScan by produceState<Result<MarketBreadth?>?>(initialValue = null) {
        val base = ServiceLocator.settingsStore.signalsApiUrl.first()
        value = if (base.isBlank()) Result.success(null)
        else runCatching { SignalsApiService().marketBreadth(base) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Markets") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackendStatusBanner()

            // Tiles, not a menu: each one shows its own answer where this screen holds one. The heat
            // map is the one extra fetch — the same server-cached call its own screen makes. The
            // calendar holds no reading here, so its tile describes rather than inventing events.
            val heat by produceState<Result<HeatmapResponse?>?>(initialValue = null) {
                val base = ServiceLocator.settingsStore.signalsApiUrl.first()
                value = if (base.isBlank()) Result.success(null)
                else runCatching { SignalsApiService().heatmap(base, mode = "market") }
            }
            // RPT-1: the newest weekly or monthly report, leading the hub because it is the one door
            // that summarises all the others (and the user's portfolio and the sandbox besides).
            val latestReport by produceState<Result<Pair<com.stocktracker.app.data.remote.ReportSummary?, Double?>>?>(initialValue = null) {
                val base = ServiceLocator.settingsStore.signalsApiUrl.first()
                value = if (base.isBlank()) Result.success(null to null)
                else runCatching {
                    val r = SignalsApiService().reports(base, limit = 1)?.reports?.firstOrNull()
                    r to r?.id?.let { com.stocktracker.app.ui.report.ReportPortfolioStore.get(it)?.changePct }
                }
            }
            ReportsTile(latestReport, onOpenReports)
            ScanTile(marketScanStatus(market, marketScan), marketScan?.getOrNull(), onOpenScan)
            // FUND-1..6. Describes rather than reads: the overlap map needs the holdings priced and a
            // multi-fund history fetch, which is the screen's work, not the hub's.
            Tile("Funds", Icons.Filled.Layers, onOpenFunds, Modifier.fillMaxWidth()) {
                Text("How your funds overlap, what they cost you a year, and cheaper funds that hold the same thing.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                HeatTile(heat, onOpenHeatmap, Modifier.weight(1f).fillMaxHeight())
                VixTile(market, vixStatus(market), onOpenVix, Modifier.weight(1f).fillMaxHeight())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                DipTile(market, dipStatus(market), onOpenDips, Modifier.weight(1f).fillMaxHeight())
                Tile("Calendar", Icons.Filled.CalendarMonth, onOpenCalendar, Modifier.weight(1f).fillMaxHeight()) {
                    Text("Earnings, option expiries and short-interest dates for what you follow.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Market now is deliberately NOT here. It reads the session through YOUR watchlist's
            // names, so it belongs beside them — it just stops being a sparkle glyph and becomes a
            // named item in the Watchlist's overflow.
            Text(
                "Context, not advice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
        }
    }
}

/**
 * The live line under a row, or null where there is nothing true to put there.
 *
 * Three of these five rows lead to something this screen already holds a reading for, so they say
 * it. The heat map and the catalyst calendar do not: nothing here has fetched either, and inventing
 * a summary — or worse, a reassuring one — for a door whose contents nobody has looked at is the
 * defect this app spends most of its comments on. Those two keep their description and no number.
 */
private data class DoorStatus(val text: String, val warn: Boolean = false)

/**
 * "Measured the Mon Sep 22 close" — which session the market scan last read, from the scan's own
 * breadth route. Falls back to [scanStatus]'s service-state messages when there is no Signals URL.
 */
private fun marketScanStatus(m: MarketContextStore.State, r: Result<MarketBreadth?>?): DoorStatus? {
    if (r == null) return null
    val b = r.getOrNull()
    if (r.isFailure) return DoorStatus("Couldn't read when the scan last ran", warn = true)
    if (b == null) return scanStatus(m)
    if (!b.available) return DoorStatus("No market scan stored yet", warn = true)
    val d = b.asOf?.takeIf { it.length >= 8 }?.let {
        runCatching {
            java.time.LocalDate.of(it.substring(0, 4).toInt(), it.substring(4, 6).toInt(), it.substring(6, 8).toInt())
                .format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", java.util.Locale.US))
        }.getOrNull()
    } ?: return DoorStatus("Scan date unknown", warn = true)
    return DoorStatus("Measured the $d close")
}

private fun scanStatus(m: MarketContextStore.State): DoorStatus? = when (m.dipRadar) {
    // FRESHNESS ONLY, deliberately. The obvious thing to put here is a count, and the count this
    // screen holds — dipCounts.scanned — is the dip radar's coverage of YOUR WATCHLIST, not the
    // market scan's universe, which is thousands of names fetched by a different endpoint on the
    // scan screen itself. The first cut said "14 names, scanned 7h ago" under a row called Market
    // scan, which is a wrong number worn confidently. The age is a fact this screen actually has.
    is DipRadarState.Ready -> m.scan?.generatedAt
        ?.let { ageAgo(it.toString()) }
        ?.let { DoorStatus("Scanned $it") }
    is DipRadarState.Unreachable -> DoorStatus("Scan service unreachable", warn = true)
    is DipRadarState.NotConfigured -> DoorStatus("No Signals service URL set", warn = true)
    is DipRadarState.NoScan -> DoorStatus("No scan has run yet", warn = true)
    DipRadarState.Loading -> null
}

private fun dipStatus(m: MarketContextStore.State): DoorStatus? = when (val s = m.dipRadar) {
    // "Nothing qualified" is a claim about the market, and it is only ours to make when we are
    // holding a scan that actually ran — see DipRadar's own note on why this is the one message
    // that must never be emitted by default.
    is DipRadarState.Ready -> {
        // The denominator matters: "2 off their highs" alone leaves the reader to guess whether the
        // radar looked at five names or five hundred. scanned is the radar's own partition total.
        val of = s.counts.scanned?.let { " of $it" }.orEmpty()
        when (val n = s.dips.size) {
            0 -> DoorStatus("Nothing qualified" + (s.counts.scanned?.let { " out of $it names" } ?: " in the last scan"))
            1 -> DoorStatus("1$of off its highs")
            else -> DoorStatus("$n$of off their highs")
        }
    }
    is DipRadarState.Unreachable -> DoorStatus("Couldn't reach the scan — not a calm market", warn = true)
    is DipRadarState.NotConfigured -> DoorStatus("No Signals service URL set", warn = true)
    is DipRadarState.NoScan -> DoorStatus("No scan has run yet", warn = true)
    DipRadarState.Loading -> null
}

private fun vixStatus(m: MarketContextStore.State): DoorStatus? {
    val v = m.vix ?: return if (m.vixFailed) DoorStatus("Couldn't load the VIX", warn = true) else null
    val line = "${String.format("%.2f", v.value)} · ${v.zone.label.lowercase()}"
    // A held reading whose last refresh failed, or one merely old — restored from disk on a cold
    // start, or unrefreshed a long while (DATA-9) — says so rather than passing for the current one.
    val age = readingAgeLabel(m.vixFetchedAtMs, System.currentTimeMillis(), failed = m.vixFailed)
    return if (age != null) DoorStatus("$line — $age", warn = true) else DoorStatus(line)
}

/** A Markets tile: a rounded card with a small header, optionally tinted by what it shows. */
@Composable
private fun Tile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .spotlightGlow(tint)
            .clickable(onClickLabel = "Open $title", onClick = onClick)
            .heightIn(min = 112.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        content()
    }
}

@Composable
private fun StatusLine(status: DoorStatus?) {
    status?.let {
        Text(it.text, style = MaterialTheme.typography.labelMedium,
            color = if (it.warn) Signal else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

/** Where the gate's breadth leg reads its verdict: share of the scan above its 50-day, against 55%. */
private const val BREADTH_HEALTHY_PCT = 55.0

/**
 * The market scan, full width, led by breadth — the one number that decides the Daily Pick's
 * "narrow market" check. Gold below 55%, green above; no tint and no bar when it was not measured.
 */
@Composable
private fun ScanTile(status: DoorStatus?, breadth: MarketBreadth?, onClick: () -> Unit) {
    val pct = breadth?.takeIf { it.available }?.pctAboveSma50?.takeIf { it.isFinite() }
    val healthy = pct?.let { it > BREADTH_HEALTHY_PCT }
    val color = when (healthy) { true -> GainGreen; false -> Signal; null -> MaterialTheme.colorScheme.onSurfaceVariant }
    Tile("Market scan", Icons.Filled.Leaderboard, onClick, Modifier.fillMaxWidth(), tint = if (pct == null) null else color) {
        if (pct != null) {
            Text(if (healthy == true) "Broad market" else "Narrow market",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            BreadthBar(pct, color)
            Text(String.format(Locale.US, "%.0f%% of stocks above their 50-day average · healthy is over %.0f%%", pct, BREADTH_HEALTHY_PCT),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Where every name sits against the whole market, from the nightly scan.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusLine(status)
    }
}

@Composable
private fun BreadthBar(pct: Double, color: Color) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    val mark = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = String.format(Locale.US, "%.0f percent, healthy above %.0f", pct, BREADTH_HEALTHY_PCT) }) {
        val h = 8.dp.toPx(); val y = (size.height - h) / 2; val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(track, Offset(0f, y), Size(size.width, h), r)
        drawRoundRect(color, Offset(0f, y), Size(size.width * (pct / 100.0).toFloat().coerceIn(0f, 1f), h), r)
        val mx = size.width * (BREADTH_HEALTHY_PCT / 100.0).toFloat()
        var yy = 0f
        while (yy < size.height) { drawLine(mark, Offset(mx, yy), Offset(mx, yy + 3.dp.toPx()), 1.5.dp.toPx()); yy += 5.dp.toPx() }
    }
}

/**
 * RPT-1: the newest report — its period, then the S&P, the user's portfolio and the AI sandbox as one
 * row of pills. "You" appears only once the phone has priced the portfolio for that report; before
 * that it is left out rather than shown as a number the phone has not worked out.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReportsTile(r: Result<Pair<com.stocktracker.app.data.remote.ReportSummary?, Double?>>?, onClick: () -> Unit) {
    val (sum, you) = r?.getOrNull() ?: (null to null)
    Tile("Reports", Icons.Filled.Assessment, onClick, Modifier.fillMaxWidth(),
        tint = sum?.sp500Pct?.let { com.stocktracker.app.ui.components.directionTint(it) }) {
        when {
            r == null -> Skeleton(Modifier.fillMaxWidth().height(40.dp))
            r.isFailure -> Text("Couldn't load reports", style = MaterialTheme.typography.labelMedium, color = Signal)
            sum == null -> Text("A weekly and a monthly review of the market, your portfolio and the AI sandbox.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                Text(com.stocktracker.app.ui.report.rowTitle(sum), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    fun col(p: Double?) = if (p == null) Signal else if (p >= 0) GainGreen else LossRed
                    val pct = com.stocktracker.app.ui.report.ReportRead::pct
                    com.stocktracker.app.ui.components.Pill("S&P ${pct(sum.sp500Pct)}", col(sum.sp500Pct))
                    you?.let { com.stocktracker.app.ui.components.Pill("You ${pct(it)}", col(it)) }
                    sum.sandboxPct?.let { com.stocktracker.app.ui.components.Pill("AI ${pct(it)}", col(it)) }
                }
                sum.headline?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A tiny treemap of the six largest names, coloured by today's move, plus the up/down count. */
@Composable
private fun HeatTile(r: Result<HeatmapResponse?>?, onClick: () -> Unit, modifier: Modifier) {
    val resp = r?.getOrNull()
    val tiles = resp?.tiles.orEmpty().filter { it.scale == "price" && it.value.isFinite() }.sortedByDescending { it.size }.take(6)
    Tile("Heat map", Icons.Filled.GridView, onClick, modifier) {
        when {
            r == null -> Skeleton(Modifier.fillMaxWidth().height(56.dp))
            tiles.size >= 3 -> {
                val rows = listOf(tiles.take(3), tiles.drop(3))
                Column(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rows.filter { it.isNotEmpty() }.forEach { row ->
                        Row(Modifier.weight(row.sumOf { it.size }.toFloat().coerceAtLeast(0.001f)).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { t ->
                                Box(Modifier.weight(t.size.toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(moveColor(t.value)),
                                    contentAlignment = Alignment.Center) {
                                    Text(t.symbol, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                val up = resp?.advancing; val down = resp?.declining
                if (up != null && down != null) {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = GainGreen)) { append("$up up") }
                        append(" · ")
                        withStyle(SpanStyle(color = LossRed)) { append("$down down") }
                    }, style = MaterialTheme.typography.labelMedium)
                }
            }
            r.isFailure -> Text("Couldn't load the heat map", style = MaterialTheme.typography.labelMedium, color = Signal)
            else -> Text("The day's moves by sector, sized by market cap.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Today's move as a tile colour: stronger with the size of the move, saturating at 3%. */
private fun moveColor(pct: Double): Color {
    val a = 0.35f + (kotlin.math.abs(pct) / 3.0).toFloat().coerceIn(0f, 1f) * 0.55f
    return (if (pct >= 0) GainGreen else LossRed).copy(alpha = a)
}

/** The VIX as a half dial over 0-40, coloured by its zone. No reading → the status line only. */
@Composable
private fun VixTile(m: MarketContextStore.State, status: DoorStatus?, onClick: () -> Unit, modifier: Modifier) {
    val v = m.vix
    val color = when (v?.zone) {
        VixZone.CALM, VixZone.NORMAL -> GainGreen
        VixZone.ELEVATED -> Signal
        VixZone.HIGH, VixZone.EXTREME -> LossRed
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fresh = status?.warn != true
    Tile("Fear · VIX", Icons.Filled.Speed, onClick, modifier) {
        if (v != null) {
            val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
            Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.BottomCenter) {
                Canvas(Modifier.size(width = 110.dp, height = 60.dp)) {
                    val st = 8.dp.toPx(); val d = size.width - st
                    val tl = Offset(st / 2, st / 2); val arc = Size(d, d)
                    drawArc(track, 180f, 180f, false, tl, arc, style = Stroke(st, cap = StrokeCap.Round))
                    val f = (v.value / 40.0).toFloat().coerceIn(0.02f, 1f)
                    drawArc(if (fresh) color else track.copy(alpha = 0.5f), 180f, 180f * f, false, tl, arc, style = Stroke(st, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(String.format(Locale.US, "%.2f", v.value), style = PriceSmall, fontWeight = FontWeight.Bold)
                    Text(v.zone.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (fresh) color else Signal)
                }
            }
            if (!fresh) StatusLine(status)
        } else {
            StatusLine(status ?: DoorStatus("Loading…"))
        }
    }
}

/** How many of the radar's names are off their highs, as a count and a bar of the whole. */
@Composable
private fun DipTile(m: MarketContextStore.State, status: DoorStatus?, onClick: () -> Unit, modifier: Modifier) {
    val s = m.dipRadar as? DipRadarState.Ready
    val n = s?.dips?.size
    val of = s?.counts?.scanned
    Tile("Dip radar", Icons.Filled.TrendingDown, onClick, modifier) {
        if (n != null && of != null && of > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$n", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(" / $of", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
            val fill = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val r = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                drawRoundRect(fill, size = Size(size.width * (n.toFloat() / of).coerceIn(0f, 1f), size.height), cornerRadius = r)
            }
            Text(if (n == 1) "off its highs" else "off their highs", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            StatusLine(status ?: DoorStatus("Loading…"))
        }
    }
}
