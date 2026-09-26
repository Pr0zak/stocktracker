package com.stocktracker.app.ui.report

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.Report
import com.stocktracker.app.data.remote.ReportBook
import com.stocktracker.app.data.remote.ReportBreadth
import com.stocktracker.app.data.remote.ReportDailyPick
import com.stocktracker.app.data.remote.ReportIndex
import com.stocktracker.app.data.remote.ReportMover
import com.stocktracker.app.data.remote.ReportMovers
import com.stocktracker.app.data.remote.ReportSector
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.components.Skeleton
import com.stocktracker.app.ui.components.directionTint
import com.stocktracker.app.ui.components.spotlightGlow
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.NumberSmall
import com.stocktracker.app.ui.theme.PriceLarge
import com.stocktracker.app.ui.theme.PriceMedium
import com.stocktracker.app.ui.theme.PriceSmall
import com.stocktracker.app.ui.theme.Signal
import com.stocktracker.app.util.MarketHolidays
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * RPT-1 — one weekly or monthly report, in the Tiles layout the user picked (2026-09-26): the market,
 * their own portfolio and the AI sandbox side by side with equal weight, then the top and bottom
 * stocks and funds, the sectors, and one pair of period-specific tiles. Every tile opens a sheet with
 * the detail, so the screen itself stays one glance long.
 *
 * The market and sandbox come from the backend's stored report; the portfolio section is priced on
 * the phone (the holdings never leave it) and stored with the report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    reportId: String,
    onBack: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenDetail: (Asset) -> Unit,
    onOpenSandbox: () -> Unit,
    onOpenPortfolio: () -> Unit,
    onOpenSignalsSettings: () -> Unit,
    vm: ReportViewModel = viewModel(),
) {
    LaunchedEffect(reportId) { vm.load(reportId) }
    val st by vm.state.collectAsStateWithLifecycle()
    val rep = st.report
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = rep?.kind ?: if (reportId.startsWith("month")) "month" else "week"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ReportRead.title(kind))
                        val sub = listOfNotNull(rep?.label, ReportRead.closeLabel(rep?.end)).joinToString(" · ")
                        if (sub.isNotEmpty()) {
                            Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onOpenReports) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "All reports") }
                    if (rep != null) {
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, ReportRead.shareText(rep, st.portfolio?.changePct))
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, null)) }
                        }) { Icon(Icons.Filled.Share, contentDescription = "Share") }
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                !st.configured -> NoticeCard(
                    "Reports need the Signals service",
                    "Set its address in Settings to see weekly and monthly reports.",
                    "Open Settings", onOpenSignalsSettings,
                )
                rep == null && st.loading -> LoadingTiles()
                rep == null -> NoticeCard(
                    "Couldn't load this report", st.error ?: "Something went wrong.", "Try again",
                ) { vm.load(reportId, force = true) }
                else -> ReportTiles(rep, st, onOpen = { sheet = it })
            }
        }
    }

    if (rep != null) {
        when (sheet) {
            "market" -> ReportSheet({ sheet = null }) { MarketSheet(rep) }
            "portfolio" -> ReportSheet({ sheet = null }) {
                PortfolioSheet(rep, st, vm::recalculatePortfolio, onOpenPortfolio = { sheet = null; onOpenPortfolio() })
            }
            "sandbox" -> ReportSheet({ sheet = null }) { SandboxSheet(rep, onOpenSandbox = { sheet = null; onOpenSandbox() }) }
            "stocks" -> ReportSheet({ sheet = null }) {
                MoversSheet("Top & bottom stocks", rep.market?.stocks, rep.isMonth) { m -> sheet = null; onOpenDetail(m) }
            }
            "etfs" -> ReportSheet({ sheet = null }) {
                MoversSheet("Top & bottom funds", rep.market?.etfs, rep.isMonth) { m -> sheet = null; onOpenDetail(m) }
            }
            "sectors" -> ReportSheet({ sheet = null }) { SectorsSheet(rep.market?.sectors.orEmpty(), rep.isMonth) }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// The tiles
// ------------------------------------------------------------------------------------------------

@Composable
private fun ReportTiles(rep: Report, st: ReportUiState, onOpen: (String) -> Unit) {
    val main = rep.sandbox?.main
    GlowCard(tint = directionTint(rep.sp500Pct), spacing = 8.dp) {
        Text(ReportRead.overline(rep.kind, rep.label), style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(rep.headline ?: "—", style = MaterialTheme.typography.bodyLarge)
    }

    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val sp = rep.index("sp500")
        StatTile(
            "MARKET", Icons.Filled.Leaderboard, sp?.pct,
            line1 = "S&P 500", line1Color = null,
            line2 = sp?.close?.let { String.format(Locale.US, "%,.2f", it) },
            line1IsNumber = false, line2IsNumber = true,
            modifier = Modifier.weight(1f).fillMaxHeight(), onClick = { onOpen("market") },
        )
        val p = st.portfolio
        StatTile(
            "PORTFOLIO", Icons.Filled.PieChart, p?.changePct,
            line1 = when {
                st.noHoldings -> "No holdings"
                p?.priced == true -> ReportRead.usd(p.changeUsd)
                p != null -> "Couldn't price"
                else -> null
            },
            line1Color = p?.changeUsd?.let { if (it >= 0) GainGreen else LossRed },
            line2 = when {
                st.noHoldings -> "Add shares to a ticker"
                p?.priced == true -> ReportRead.vsSp(p.changePct?.let { y -> rep.sp500Pct?.let { y - it } })
                else -> null
            },
            loading = st.portfolioLoading && p == null,
            modifier = Modifier.weight(1f).fillMaxHeight(), onClick = { onOpen("portfolio") },
        )
        StatTile(
            "SANDBOX", Icons.Filled.SmartToy, main?.changePct,
            line1 = main?.changeUsd?.let { ReportRead.usd(it) } ?: rep.sandbox?.reason?.let { "No record" },
            line1Color = main?.changeUsd?.let { if (it >= 0) GainGreen else LossRed },
            line2 = "Paper money",
            modifier = Modifier.weight(1f).fillMaxHeight(), onClick = { onOpen("sandbox") },
        )
    }

    if (rep.isMonth) CalendarTile(rep)

    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MoversTile("Stocks", Icons.Filled.TrendingUp, "Companies worth \$10B+", rep.market?.stocks,
            Modifier.weight(1f).fillMaxHeight()) { onOpen("stocks") }
        MoversTile("ETFs", Icons.Filled.GridView, "Popular funds", rep.market?.etfs,
            Modifier.weight(1f).fillMaxHeight()) { onOpen("etfs") }
    }

    SectorsTile(rep.market?.sectors.orEmpty(), if (rep.isMonth) "Sectors this month" else "Sectors") { onOpen("sectors") }

    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rep.isMonth) {
            SandboxMoneyTile(main, Modifier.weight(1f).fillMaxHeight()) { onOpen("sandbox") }
            HoldingsTile(st, Modifier.weight(1f).fillMaxHeight()) { onOpen("portfolio") }
        } else {
            RisingTile(rep.market?.breadth, Modifier.weight(1f).fillMaxHeight())
            PickTile(rep.dailyPick, rep.sessions, Modifier.weight(1f).fillMaxHeight())
        }
    }

    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Context, not advice.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val made = rep.madeAt?.let {
            Instant.ofEpochMilli((it * 1000).toLong()).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("EEE h:mm a z", Locale.US))
        }
        Text(listOfNotNull(ReportRead.closeLabel(rep.end), made?.let { "made $it" }).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A Markets-hub style tile: 16dp, raised surface, a primary icon + title + chevron header. */
@Composable
private fun ReportTile(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .spotlightGlow(tint)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Open $title", onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
            if (onClick != null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        content()
    }
}

private fun pctColor(p: Double?, neutral: Color): Color =
    if (p == null || !p.isFinite()) neutral else if (p >= 0) GainGreen else LossRed

/** One of the three equal tiles: which part, its percent, and two short lines under it. */
@Composable
private fun StatTile(
    label: String,
    icon: ImageVector,
    pct: Double?,
    line1: String?,
    line1Color: Color?,
    line2: String?,
    modifier: Modifier,
    loading: Boolean = false,
    /** Line 1 is a figure (tabular digits) unless it is a label, like "S&P 500". */
    line1IsNumber: Boolean = true,
    line2IsNumber: Boolean = false,
    onClick: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .spotlightGlow(if (loading) null else directionTint(pct))
            .clickable(onClickLabel = "Open ${label.lowercase(Locale.US)}", onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, color = neutral, maxLines = 1)
        }
        if (loading) {
            Skeleton(Modifier.fillMaxWidth(0.85f).height(22.dp))
            Skeleton(Modifier.fillMaxWidth(0.6f).height(14.dp))
        } else {
            Text(ReportRead.pct(pct), style = PriceMedium, fontWeight = FontWeight.SemiBold,
                color = pctColor(pct, neutral), maxLines = 1, softWrap = false)
            line1?.let {
                Text(it, style = if (line1IsNumber) NumberSmall else MaterialTheme.typography.labelMedium,
                    color = line1Color ?: neutral, maxLines = 1)
            }
            line2?.let {
                Text(it, style = if (line2IsNumber) NumberSmall else MaterialTheme.typography.labelSmall,
                    color = neutral, maxLines = 2)
            }
        }
    }
}

@Composable
private fun MoversTile(title: String, icon: ImageVector, helper: String, m: ReportMovers?, modifier: Modifier, onClick: () -> Unit) {
    ReportTile(title, icon, modifier, onClick = onClick) {
        Text(helper, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (m == null || (m.best.isEmpty() && m.worst.isEmpty())) {
            Text("Not measured this time", style = MaterialTheme.typography.labelMedium, color = Signal)
            return@ReportTile
        }
        SmallLabel("BEST", GainGreen)
        m.best.take(3).forEach { MoverLine(it) }
        SmallLabel("WORST", LossRed, Modifier.padding(top = 2.dp))
        m.worst.take(3).forEach { MoverLine(it) }
    }
}

@Composable
private fun SmallLabel(text: String, color: Color, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        color = color, modifier = modifier)

@Composable
private fun MoverLine(m: ReportMover) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(m.symbol ?: "—", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            m.name?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(ReportRead.pct(m.pct), style = NumberSmall, color = pctColor(m.pct, MaterialTheme.colorScheme.onSurfaceVariant))
    }
}

/** A sector's tile colour: the heat map's scale (stronger with the move, saturating at 3%). */
private fun sectorColor(p: Double): Pair<Color, Color> {
    val a = 0.35f + (abs(p) / 3.0).toFloat().coerceIn(0f, 1f) * 0.55f
    val c = (if (p >= 0) GainGreen else LossRed).copy(alpha = a)
    // Dark ink on the bright greens, where white would be unreadable; white everywhere else.
    val ink = if (p >= 0 && a >= 0.7f) Color(0xFF0D1116) else Color.White
    return c to ink
}

@Composable
private fun SectorsTile(sectors: List<ReportSector>, title: String, onClick: () -> Unit) {
    ReportTile(title, Icons.Filled.GridView, Modifier.fillMaxWidth(), onClick = onClick) {
        if (sectors.none { it.pct != null }) {
            Text("Not measured this time", style = MaterialTheme.typography.labelMedium, color = Signal)
            return@ReportTile
        }
        Column(Modifier.clip(RoundedCornerShape(8.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            sectors.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { s ->
                        val p = s.pct
                        val (bg, ink) = if (p != null) sectorColor(p) else (MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f) to MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(
                            Modifier.weight(1f).height(44.dp).background(bg),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(s.name ?: "—", style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1)
                            Text(ReportRead.pct(p), style = NumberSmall.copy(fontSize = 11.sp), color = ink, maxLines = 1)
                        }
                    }
                    repeat(4 - row.size) {
                        Box(Modifier.weight(1f).height(44.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun RisingTile(b: ReportBreadth?, modifier: Modifier) {
    ReportTile("Stocks rising", Icons.Filled.TrendingUp, modifier) {
        val share = ReportRead.upShare(b?.up, b?.measured)
        if (share == null) {
            Text("Not measured this time", style = MaterialTheme.typography.labelMedium, color = Signal)
            return@ReportTile
        }
        val color = if (share >= 50) GainGreen else Signal
        Text("$share%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f))) {
            Box(Modifier.fillMaxWidth(share / 100f).height(6.dp).clip(RoundedCornerShape(50)).background(color))
        }
        Text("${String.format(Locale.US, "%,d", b?.up ?: 0)} of ${String.format(Locale.US, "%,d", b?.measured ?: 0)} big companies rose",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PickTile(p: ReportDailyPick?, sessions: Int?, modifier: Modifier) {
    ReportTile("Daily Pick", Icons.Filled.AutoAwesome, modifier) {
        val picks = p?.picks
        when {
            p == null || picks == null -> Text("No record", style = MaterialTheme.typography.labelMedium, color = Signal)
            picks > 0 -> {
                Text(if (picks == 1) "1 pick" else "$picks picks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(p.symbols.joinToString(", "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                Text("No picks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ReportRead.pickLine(p.runs, sessions, p.reason),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SandboxMoneyTile(main: ReportBook?, modifier: Modifier, onClick: () -> Unit) {
    ReportTile("Sandbox money", Icons.Filled.SmartToy, modifier, onClick = onClick) {
        if (main == null) {
            Text("No record this month", style = MaterialTheme.typography.labelMedium, color = Signal)
            return@ReportTile
        }
        MoneyLine("Added", ReportRead.usd(main.deposits), MaterialTheme.colorScheme.primary)
        MoneyLine("Markets", ReportRead.usd(main.changeUsd), pctColor(main.changeUsd, MaterialTheme.colorScheme.onSurfaceVariant))
        MoneyLine("S&P, same money", ReportRead.usd(main.benchChangeUsd), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MoneyLine(label: String, value: String, color: Color) {
    Row {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = NumberSmall, color = color)
    }
}

@Composable
private fun HoldingsTile(st: ReportUiState, modifier: Modifier, onClick: () -> Unit) {
    ReportTile("Your holdings", Icons.Filled.PieChart, modifier, onClick = onClick) {
        val p = st.portfolio
        when {
            st.portfolioLoading && p == null -> Skeleton(Modifier.fillMaxWidth().height(40.dp))
            st.noHoldings -> Text("No holdings yet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            p == null || !p.priced -> Text("Couldn't price them", style = MaterialTheme.typography.labelMedium, color = Signal)
            else -> {
                p.best?.let { MoneyLine("${it.symbol} best", ReportRead.pct(it.changePct), pctColor(it.changePct, Color.Unspecified)) }
                p.worst?.let { MoneyLine("${it.symbol} worst", ReportRead.pct(it.changePct), pctColor(it.changePct, Color.Unspecified)) }
                MoneyLine("Up this month", "${p.upCount} of ${p.holdings.size}", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The S&P's move each session of the month, laid out Monday to Friday. */
@Composable
private fun CalendarTile(rep: Report) {
    val end = runCatching { LocalDate.parse(rep.end) }.getOrNull() ?: return
    val moves = rep.market?.daily.orEmpty().mapNotNull { d -> runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { it to d.pct } }.toMap()
    ReportTile("S&P day by day", Icons.Filled.CalendarMonth, Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        // Start at the Monday of the month's first WEEKDAY: a month that opens on a weekend would
        // otherwise draw a first row that belongs entirely to the month before.
        var first = end.withDayOfMonth(1)
        while (first.dayOfWeek == DayOfWeek.SATURDAY || first.dayOfWeek == DayOfWeek.SUNDAY) first = first.plusDays(1)
        var monday = first.with(DayOfWeek.MONDAY)
        val neutral = MaterialTheme.colorScheme.onSurfaceVariant
        while (monday <= end) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 5) {
                    val d = monday.plusDays(i.toLong())
                    val cell = Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(8.dp))
                    val p = moves[d]
                    when {
                        d.month != end.month -> Spacer(cell)
                        p != null -> {
                            val flat = abs(p) < 0.005
                            val a = 0.35f + min(abs(p) / 3.0, 1.0).toFloat() * 0.55f
                            val bg = if (flat) neutral.copy(alpha = 0.14f) else (if (p > 0) GainGreen else LossRed).copy(alpha = a)
                            Column(cell.background(bg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                                Text(String.format(Locale.US, "%+.2f", p).replace("-", "−"), style = NumberSmall.copy(fontSize = 11.sp), color = Color.White)
                            }
                        }
                        MarketHolidays.isMarketHoliday(d) -> Column(
                            cell.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp)),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = neutral)
                            Text("closed", style = MaterialTheme.typography.labelSmall, color = neutral)
                        }
                        else -> Spacer(cell.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp)))
                    }
                }
            }
            monday = monday.plusWeeks(1)
        }
        val up = moves.values.count { it != null && it >= 0.005 }
        val down = moves.values.count { it != null && it <= -0.005 }
        val flat = moves.size - up - down
        Text("$up up days, $down down, $flat flat · % move each day", style = MaterialTheme.typography.labelMedium, color = neutral)
    }
}

@Composable
private fun LoadingTiles() {
    Skeleton(Modifier.fillMaxWidth().height(96.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { Skeleton(Modifier.weight(1f).height(104.dp)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(2) { Skeleton(Modifier.weight(1f).height(240.dp)) }
    }
    Skeleton(Modifier.fillMaxWidth().height(180.dp))
}

@Composable
private fun NoticeCard(title: String, body: String, action: String, onAction: () -> Unit) {
    GlowCard(tint = null, modifier = Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAction) { Text(action) }
    }
}

// ------------------------------------------------------------------------------------------------
// The sheets behind the tiles
// ------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun SheetTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge)

@Composable
private fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)

private fun levelText(i: ReportIndex): String {
    val c = i.close ?: return "—"
    return when (i.key) {
        "bitcoin", "oil", "gold" -> "$" + String.format(Locale.US, "%,.2f", c)
        "ten_year" -> String.format(Locale.US, "%.2f%%", c)
        else -> String.format(Locale.US, "%,.2f", c)
    }
}

@Composable
private fun ChangeChip(i: ReportIndex) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        !i.measured -> Pill("—", neutral)
        i.unit == "pts" -> {
            val ch = i.change
            val txt = if (ch == null) "—" else (if (ch >= 0) "▲ " else "▼ ") + String.format(Locale.US, "%.2f pts", abs(ch))
            Pill(txt, neutral)
        }
        else -> Pill(ReportRead.pct(i.pct), pctColor(i.pct, neutral))
    }
}

@Composable
private fun IndexRow(i: ReportIndex) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.weight(1f)) {
            Text(i.name ?: i.symbol ?: "—", style = MaterialTheme.typography.bodyLarge)
            i.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (i.measured) levelText(i) else "Not measured", style = PriceSmall,
                color = if (i.measured) Color.Unspecified else Signal)
            if (i.measured) ChangeChip(i)
        }
    }
}

@Composable
private fun MarketSheet(rep: Report) {
    SheetTitle("The market")
    val m = rep.market
    Text(rep.headline ?: "", style = MaterialTheme.typography.bodyMedium)
    val sp = rep.index("sp500")
    val typical = m?.typicalStock
    if (sp?.pct != null && typical?.pct != null) {
        val scale = maxOf(abs(sp.pct), abs(typical.pct)).takeIf { it > 0 } ?: 1.0
        RaceBar("S&P 500", sp.pct, scale, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), bold = true)
        RaceBar("Typical stock", typical.pct, scale, if (typical.pct >= 0) GainGreen else LossRed, bold = false)
        Note("“Typical stock” counts every S&P company the same, so a few giants can't carry it.")
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    m?.indexes.orEmpty().forEach { IndexRow(it) }
    m?.breadth?.let { b ->
        ReportRead.upShare(b.up, b.measured)?.let { share ->
            Note("$share% of ${String.format(Locale.US, "%,d", b.measured ?: 0)} companies worth \$10B or more rose.")
        }
    }
    if (m?.failed.orEmpty().isNotEmpty()) Note("Couldn't load: ${m!!.failed.joinToString(", ")}", Signal)
    Note("Price changes, like the ones quoted on the news. Dividends are not added in.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortfolioSheet(rep: Report, st: ReportUiState, onRecalculate: () -> Unit, onOpenPortfolio: () -> Unit) {
    SheetTitle("Your portfolio")
    val p = st.portfolio
    when {
        st.portfolioLoading && p == null -> Skeleton(Modifier.fillMaxWidth().height(120.dp))
        st.noHoldings -> Text("You have no holdings yet. Set “Shares owned” on a ticker and it will be part of the next report.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        p == null || !p.priced -> Text("Couldn't price your holdings for this period. Check the connection and recalculate.",
            style = MaterialTheme.typography.bodyMedium, color = Signal)
        else -> {
            Text(ReportRead.money(p.endValue), style = PriceLarge)
            ReportRead.closeLabel(rep.end)?.let { Note("Worth at the $it") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(ReportRead.pct(p.changePct), pctColor(p.changePct, MaterialTheme.colorScheme.onSurfaceVariant))
                Pill(ReportRead.usd(p.changeUsd), pctColor(p.changeUsd, MaterialTheme.colorScheme.onSurfaceVariant))
                val vs = p.changePct?.let { y -> rep.sp500Pct?.let { y - it } }
                ReportRead.vsSp(vs)?.let { Pill(it, pctColor(vs, MaterialTheme.colorScheme.onSurfaceVariant)) }
                Pill("${p.upCount} of ${p.holdings.size} up", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val sp = rep.sp500Pct
            if (sp != null && p.changePct != null) {
                val scale = maxOf(abs(sp), abs(p.changePct)).takeIf { it > 0 } ?: 1.0
                RaceBar("You", p.changePct, scale, if (p.changePct >= 0) MaterialTheme.colorScheme.primary else LossRed, bold = true)
                RaceBar("S&P 500", sp, scale, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), bold = false)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            p.holdings.forEach { h ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(h.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(h.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(ReportRead.pct(h.changePct), style = PriceSmall, color = pctColor(h.changePct, Color.Unspecified))
                        Text(ReportRead.usd(h.changeUsd), style = NumberSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (p.unpriced.isNotEmpty()) {
                Note("Left out, no price for this period: ${p.unpriced.joinToString(", ")}", Signal)
            }
        }
    }
    Note("Based on the shares you hold now, priced at each close. Dividends are not added in.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onRecalculate, enabled = !st.portfolioLoading) { Text("Recalculate") }
        TextButton(onClick = onOpenPortfolio) { Text("Open Portfolio ›") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SandboxSheet(rep: Report, onOpenSandbox: () -> Unit) {
    SheetTitle("AI sandbox")
    val sb = rep.sandbox
    val main = sb?.main
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    if (sb == null || main == null) {
        Text(sb?.reason ?: "No sandbox record for this period.", style = MaterialTheme.typography.bodyMedium, color = neutral)
        TextButton(onClick = onOpenSandbox) { Text("Open Sandbox ›") }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ReportRead.money(main.endEquity), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Pill("PAPER", neutral)
    }
    val bench = main.benchPct
    if (main.changePct != null && bench != null) {
        val scale = maxOf(abs(main.changePct), abs(bench)).takeIf { it > 0 } ?: 1.0
        RaceBar("AI", main.changePct, scale, if (main.changePct >= 0) MaterialTheme.colorScheme.primary else LossRed, bold = true)
        RaceBar("S&P", bench, scale, neutral.copy(alpha = 0.7f), bold = false)
    }
    val t = sb.trades
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(ReportRead.usd(main.changeUsd), pctColor(main.changeUsd, neutral))
        ReportRead.vsSp(main.vsPts)?.let { Pill(it, pctColor(main.vsPts, neutral)) }
        val trades = (t?.buys ?: 0) + (t?.sells ?: 0)
        Pill(if (trades == 1) "1 trade" else "$trades trades", neutral)
        if ((t?.blockedCount ?: 0) > 0) Pill("${t?.blockedCount} blocked", Signal)
        main.cashPct?.let { Pill("Cash ${it.toInt()}%", neutral) }
        if ((main.deposits ?: 0.0) > 0.0) Pill("Added ${ReportRead.money(main.deposits)}", MaterialTheme.colorScheme.primary)
    }
    if (main.measuredThroughEnd == false) Note("Last measured ${main.endDate} — the check did not run on the period's last day.", Signal)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    t?.fills.orEmpty().forEach { f ->
        val sell = f.side == "sell"
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Box(Modifier.width(84.dp)) { Pill(if (sell) "SELL" else "BUY", if (sell) LossRed else GainGreen) }
            Column(Modifier.weight(1f)) {
                Text("${f.symbol} · ${f.shares?.let { com.stocktracker.app.util.Formatting.shares(it) } ?: "—"} sh",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                ReportRead.fillFlag(f.flag, f.heldDays)?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Signal, modifier = Modifier.size(12.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Signal)
                    }
                } ?: ReportRead.shortDate(f.date)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = neutral) }
            }
            Text(
                if (sell) ReportRead.usd(f.realizedPl) else ReportRead.money(f.gross),
                style = PriceSmall, color = if (sell) pctColor(f.realizedPl, neutral) else Color.Unspecified,
            )
        }
    }
    t?.blocked.orEmpty().forEach { b ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Box(Modifier.width(84.dp)) { Pill("BLOCKED", Signal) }
            Column(Modifier.weight(1f)) {
                val times = b.count?.takeIf { it > 1 }?.let { " · $it times" }.orEmpty()
                Text("${b.symbol}$times", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                b.reason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = neutral) }
            }
        }
    }
    if (t?.fills.isNullOrEmpty() && t?.blocked.isNullOrEmpty()) Note("No trades this period.")
    val arms = sb.arms.filter { it.measured && it.changePct != null }
    if (arms.size > 1) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Test books", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val scale = arms.maxOf { abs(it.changePct!!) }.takeIf { it > 0 } ?: 1.0
        arms.forEach { a ->
            RaceBar(a.label ?: a.arm ?: "—", a.changePct!!, scale, if (a.changePct >= 0) GainGreen else LossRed,
                bold = a.main, labelWidth = 150)
        }
        Note("Each book's change with deposits taken out.")
    }
    sb.note?.let { Note(it) }
    TextButton(onClick = onOpenSandbox) { Text("Open Sandbox ›") }
}

@Composable
private fun MoversSheet(title: String, m: ReportMovers?, month: Boolean, onOpen: (Asset) -> Unit) {
    SheetTitle(title)
    if (m == null || (m.best.isEmpty() && m.worst.isEmpty())) {
        Text("Not measured this time.", style = MaterialTheme.typography.bodyMedium, color = Signal)
        return
    }
    val span = if (month) "this month" else "this week"
    Note(listOfNotNull(m.universe, m.measured?.let { "$it measured" }, span).joinToString(" · "))
    SmallLabel("▲ BEST", GainGreen)
    m.best.forEach { MoverRow(it, onOpen) }
    SmallLabel("▼ WORST", LossRed, Modifier.padding(top = 6.dp))
    m.worst.forEach { MoverRow(it, onOpen) }
    if ((m.best + m.worst).any { it.split == true }) Note("A split happened inside the period for a name marked here; its move uses split-adjusted prices.")
}

@Composable
private fun MoverRow(m: ReportMover, onOpen: (Asset) -> Unit) {
    val sym = m.symbol ?: return
    val up = (m.pct ?: 0.0) >= 0
    val wash = (if (up) GainGreen else LossRed).copy(alpha = 0.12f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(0.35f to Color.Transparent, 1f to wash))
            .clickable(onClickLabel = "Open $sym") { onOpen(Asset(sym, AssetType.STOCK, m.name ?: sym)) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(sym, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            m.name?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Pill(ReportRead.pct(m.pct), pctColor(m.pct, MaterialTheme.colorScheme.onSurfaceVariant))
    }
}

@Composable
private fun SectorsSheet(sectors: List<ReportSector>, month: Boolean) {
    SheetTitle(if (month) "Sectors this month" else "Sectors this week")
    val measured = sectors.filter { it.pct != null }
    if (measured.isEmpty()) {
        Text("Not measured this time.", style = MaterialTheme.typography.bodyMedium, color = Signal)
        return
    }
    val scale = measured.maxOf { abs(it.pct!!) }.takeIf { it > 0 } ?: 1.0
    val track = MaterialTheme.colorScheme.outlineVariant
    sectors.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.name ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(88.dp), maxLines = 1)
            val p = s.pct
            Row(Modifier.weight(1f).height(10.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                    if (p != null && p < 0) {
                        Box(Modifier.fillMaxWidth((abs(p) / scale).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 50.dp, bottomStart = 50.dp)).background(LossRed))
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(track))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    if (p != null && p >= 0) {
                        Box(Modifier.fillMaxWidth((abs(p) / scale).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight()
                            .clip(RoundedCornerShape(topEnd = 50.dp, bottomEnd = 50.dp)).background(GainGreen))
                    }
                }
            }
            Text(ReportRead.pct(p), style = NumberSmall, color = pctColor(p, MaterialTheme.colorScheme.onSurfaceVariant),
                textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
        }
    }
    Note("One fund per sector (the SPDR sector funds), price change only.")
}

/** The Sandbox tab's race lane: a label, a bar from zero scaled with its neighbours, the number. */
@Composable
private fun RaceBar(label: String, pct: Double, scale: Double, color: Color, bold: Boolean, labelWidth: Int = 96) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(labelWidth.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).height(10.dp).background(track, RoundedCornerShape(50))) {
            Box(Modifier.fillMaxWidth((abs(pct) / scale).toFloat().coerceIn(0.02f, 1f)).height(10.dp)
                .background(color, RoundedCornerShape(50)))
        }
        Text(String.format(Locale.US, "%+.2f%%", pct).replace("-", "−"), style = MaterialTheme.typography.labelLarge,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (bold) (if (pct >= 0) GainGreen else LossRed) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(66.dp), textAlign = TextAlign.End)
    }
}
