package com.stocktracker.app.ui.funds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import com.stocktracker.app.ui.components.spotlightGlow
import com.stocktracker.app.ui.theme.LossRed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.remote.FundGroup
import com.stocktracker.app.data.remote.FundOverlapResponse
import com.stocktracker.app.data.remote.FundProfile
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.BackendStatusBanner
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.components.Skeleton
import com.stocktracker.app.ui.detail.FundCostText
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.Signal
import kotlinx.coroutines.launch

/**
 * FUND-1..6 — the Funds spoke of the Markets tab, laid out as Tiles (the layout the user picked on
 * 2026-09-26, the same family as Reports): one headline that names the funds that overlap, then a
 * tile each for performance, overlap, fees, a before-you-buy check and cheaper copies. Every tile
 * opens its detail; nothing below the headline needs reading to get the gist.
 *
 * [vm] is shared with the three detail screens, so they draw the same reading without refetching.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundsScreen(
    vm: FundsViewModel,
    onBack: () -> Unit,
    onOpenDetail: (Asset) -> Unit,
    onOpenCompare: (List<String>) -> Unit,
    onOpenRanking: (RankSort) -> Unit,
    onOpenOverlap: () -> Unit,
    onOpenCopies: () -> Unit,
    onOpenSignalsSettings: () -> Unit = {},
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var showCheck by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Funds")
                        FundsLogic.asOfLine(ui.perf?.alignedTo)?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = neutral)
                        }
                    }
                },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackendStatusBanner()
            ModeChips(ui, vm)
            val resp = ui.overlap
            when {
                !ui.configured -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Fund comparisons come from the self-hosted signals service, which isn't set up yet.",
                        style = MaterialTheme.typography.bodyMedium, color = neutral)
                    TextButton(onClick = onOpenSignalsSettings) { Text("Set up signals") }
                }
                ui.loading && resp == null -> {
                    Skeleton(Modifier.fillMaxWidth().height(150.dp))
                    Skeleton(Modifier.fillMaxWidth().height(190.dp))
                    Skeleton(Modifier.fillMaxWidth().height(130.dp))
                }
                ui.failed && resp == null -> Column {
                    Text("Couldn't load your funds.", style = MaterialTheme.typography.bodyMedium, color = Signal)
                    TextButton(onClick = { vm.load() }) { Text("Retry") }
                }
                resp != null -> {
                    if (ui.failed) {
                        Text("Couldn't refresh — showing the last result.", style = MaterialTheme.typography.labelMedium, color = Signal)
                    }
                    val ov = FundsLogic.overlapView(resp, ui.values)
                    HeroCard(ui, resp, ov)
                    if (resp.funds.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                            PerformanceTile(ui, resp, Modifier.weight(1f).fillMaxHeight()) { onOpenRanking(RankSort.RETURN) }
                            OverlapTile(resp, ov, Modifier.weight(1f).fillMaxHeight(), onOpenOverlap)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                            FeesTile(ui, resp, Modifier.weight(1f).fillMaxHeight()) { onOpenRanking(RankSort.FEE) }
                            CheckTile(Modifier.weight(1f).fillMaxHeight()) { showCheck = true }
                        }
                        CopiesTile(ui, resp, onOpenCopies)
                    } else {
                        CheckTile(Modifier.fillMaxWidth()) { showCheck = true }
                    }
                }
            }
            if (ui.configured) FeeAlertToggle()
            Text(
                "Funds that rise and fall together over two years count as overlapping. Context, not advice.",
                style = MaterialTheme.typography.bodySmall,
                color = neutral,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        }
    }

    if (showCheck) {
        ModalBottomSheet(onDismissRequest = { showCheck = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                BeforeYouBuyCard(ui.check, onCheck = { vm.check(it) }, onClear = { vm.clearCheck() },
                    onCompare = { sym ->
                        showCheck = false
                        val mine = ui.overlap?.funds?.keys?.toList().orEmpty()
                        onOpenCompare((listOf(sym) + mine.filter { it != sym }).take(2))
                    })
            }
        }
    }
}

@Composable
internal fun ModeChips(ui: FundsViewModel.UiState, vm: FundsViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = ui.mode == FundsViewModel.Mode.HOLDINGS,
            onClick = { vm.setMode(FundsViewModel.Mode.HOLDINGS) },
            label = { Text("Your holdings") },
        )
        FilterChip(
            selected = ui.mode == FundsViewModel.Mode.WATCHLIST,
            onClick = { vm.setMode(FundsViewModel.Mode.WATCHLIST) },
            label = { Text("Watchlist") },
        )
    }
}

/** The colour each group that moves together is drawn in, in the order FundsLogic.overlapView gives. */
internal val GroupColors = listOf(Color(0xFFB4A0FF), Color(0xFFD9A54A), Color(0xFF5CC8D6), Color(0xFFE89AC7))
internal val OwnWayColor = Color(0xFF5B6470)

internal fun groupColor(i: Int): Color = GroupColors[i % GroupColors.size]

@Composable
internal fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

/** A Funds tile: the Markets-hub tile, same shape, same header, tappable into its detail. */
@Composable
private fun FundsTile(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: () -> Unit,
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

/** "7 of your 12 funds overlap", the funds named, and a bar of how they split. */
@Composable
private fun HeroCard(ui: FundsViewModel.UiState, resp: FundOverlapResponse, ov: OverlapView) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    GlowCard(tint = if (ov.overlapping > 0) GroupColors[0] else null, spacing = 8.dp) {
        Text(if (ui.mode == FundsViewModel.Mode.HOLDINGS) "YOUR FUNDS" else "YOUR WATCHLIST'S FUNDS",
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = neutral)
        if (resp.funds.isEmpty()) {
            Text("No funds here", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (resp.unknown.isEmpty()) {
                Text(if (ui.mode == FundsViewModel.Mode.HOLDINGS) "None of your holdings is a fund." else "Your watchlist has no funds.",
                    style = MaterialTheme.typography.bodyMedium, color = neutral)
            }
        } else {
            Text(FundsLogic.overlapHeadline(resp.funds.size, ov.overlapping),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ov.groups.forEach { g ->
                Text(FundsLogic.groupSentence(g, resp.funds), style = MaterialTheme.typography.bodyMedium)
            }
            if (ov.groups.isEmpty()) {
                Text("Each one moves its own way.", style = MaterialTheme.typography.bodyMedium, color = neutral)
            }
            SplitBar(ov, resp)
            if (ui.mode == FundsViewModel.Mode.HOLDINGS && ui.values.isNotEmpty()) {
                val fees = FundsLogic.feesYouPay(ui.values, resp.funds, ui.groupsById)
                val total = FundCostText.dollars(Math.round(ui.values.values.sum()).toDouble())
                Text(
                    when {
                        fees.knownCount == 0 -> "$total in funds · yearly fees unknown"
                        fees.unknownFee.isEmpty() -> "$total in funds · about ${FundCostText.dollars(fees.perYear)} a year in fees"
                        else -> "$total in funds · about ${FundCostText.dollars(fees.perYear)} a year in fees on the " +
                            "${FundCostText.dollars(Math.round(fees.countedValue).toDouble())} with a known fee"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = neutral,
                )
            } else if (ui.mode == FundsViewModel.Mode.HOLDINGS && ui.unpriced.isNotEmpty()) {
                Text("Couldn't price ${ui.unpriced.joinToString(", ")}, so there are no dollar figures here.",
                    style = MaterialTheme.typography.labelMedium, color = Signal)
            }
        }
        if (ui.cachePriced.isNotEmpty()) {
            Text("Priced from the last saved quote (a live one failed): ${ui.cachePriced.joinToString(", ")}.",
                style = MaterialTheme.typography.labelSmall, color = neutral)
        }
        if (resp.unknown.isNotEmpty()) {
            Text("Couldn't look up ${resp.unknown.joinToString(", ")}: the quote service didn't answer, so they're left out.",
                style = MaterialTheme.typography.labelMedium, color = Signal)
        }
        if (resp.unmeasured.isNotEmpty()) {
            Text("Too many funds to measure at once; left out: ${resp.unmeasured.joinToString(", ")}.",
                style = MaterialTheme.typography.labelMedium, color = Signal)
        }
        if (ui.noHeldFunds) {
            Text("None of your holdings is a fund, so this shows your watchlist's.",
                style = MaterialTheme.typography.bodySmall, color = neutral)
        }
    }
}

/** One segment per fund, coloured by the group it moves with; grey for a fund on its own. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SplitBar(ov: OverlapView, resp: FundOverlapResponse) {
    Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ov.groups.forEachIndexed { i, g ->
            Box(Modifier.weight(g.size.toFloat()).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(groupColor(i)))
        }
        ov.singles.forEach { _ ->
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(OwnWayColor))
        }
    }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ov.groups.forEachIndexed { i, g -> LegendDot(groupColor(i), "${FundsLogic.groupName(g, resp.funds)} · ${g.size}") }
        if (ov.singles.isNotEmpty()) LegendDot(OwnWayColor, "Each its own way · ${ov.singles.size}")
    }
}

@Composable
internal fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A thin bar for a return: green up, red down, its length relative to [max]. Unknown draws nothing. */
@Composable
internal fun ReturnBar(value: Double?, max: Double, modifier: Modifier = Modifier) {
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f))) {
        if (value != null && max > 0) {
            val frac = (kotlin.math.abs(value) / max).toFloat().coerceIn(0.02f, 1f)
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                .background(if (value >= 0) GainGreen else LossRed))
        }
    }
}

@Composable
private fun PerformanceTile(ui: FundsViewModel.UiState, resp: FundOverlapResponse, modifier: Modifier, onClick: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val syms = resp.funds.keys
    val period = FundsLogic.bestPeriod(ui.perf, syms)
    val rows = FundsLogic.ranking(syms, ui.perf, resp.funds, period, RankSort.RETURN).filter { it.ret != null }
    val max = rows.maxOfOrNull { kotlin.math.abs(it.ret!!) } ?: 0.0
    FundsTile("Performance", Icons.AutoMirrored.Filled.TrendingUp, modifier,
        tint = rows.firstOrNull()?.ret?.let { com.stocktracker.app.ui.components.directionTint(it) }, onClick = onClick) {
        when {
            ui.perf == null && !ui.perfFailed -> Skeleton(Modifier.fillMaxWidth().height(80.dp))
            rows.isEmpty() -> Text(if (ui.perfFailed) "Couldn't load returns." else "No returns to rank yet.",
                style = MaterialTheme.typography.bodySmall, color = if (ui.perfFailed) Signal else neutral)
            else -> {
                Text("${FundsLogic.periodWords(period)}, dividends in", style = MaterialTheme.typography.labelMedium, color = neutral)
                val shown = if (rows.size > 3) rows.take(2) + rows.last() else rows
                shown.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.symbol, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                        ReturnBar(r.ret, max, Modifier.weight(1f))
                        Text(FundsLogic.pctShort(r.ret), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                            color = if ((r.ret ?: 0.0) >= 0) GainGreen else LossRed)
                    }
                }
                if (rows.size > 3) {
                    Text("Top 2 and last of ${rows.size}", style = MaterialTheme.typography.labelSmall, color = neutral)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OverlapTile(resp: FundOverlapResponse, ov: OverlapView, modifier: Modifier, onClick: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    FundsTile("Overlap", Icons.Filled.Layers, modifier, tint = if (ov.groups.isNotEmpty()) GroupColors[0] else null, onClick = onClick) {
        if (ov.groups.isEmpty()) {
            Text("None of these move together.", style = MaterialTheme.typography.bodySmall, color = neutral)
        } else {
            Text("Move together", style = MaterialTheme.typography.labelMedium, color = neutral)
            ov.groups.take(3).forEachIndexed { i, g ->
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    g.forEach { s -> Pill(s, groupColor(i)) }
                }
            }
            // The tightest pair, said once, is the one fact most worth knowing here.
            val tight = resp.pairs.filter { PairVerdict.of(it.corr) == PairVerdict.SAME_FUND }.maxByOrNull { it.corr ?: 0.0 }
            tight?.let {
                Text("${it.a} & ${it.b}: same fund", style = MaterialTheme.typography.labelMedium, color = neutral)
            }
        }
    }
}

@Composable
private fun FeesTile(ui: FundsViewModel.UiState, resp: FundOverlapResponse, modifier: Modifier, onClick: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val range = FundsLogic.feeRange(resp.funds.keys, resp.funds)
    FundsTile("Fees", Icons.Filled.Receipt, modifier, onClick = onClick) {
        Text("A year per \$10,000", style = MaterialTheme.typography.labelMedium, color = neutral)
        if (range == null) {
            Text("Not enough known fees to compare.", style = MaterialTheme.typography.bodySmall, color = neutral)
        } else {
            Text(range.first, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("Cheapest ${range.second.joinToString(", ")} · priciest ${range.third}",
                style = MaterialTheme.typography.labelMedium, color = neutral)
        }
        if (ui.mode == FundsViewModel.Mode.HOLDINGS && ui.values.isNotEmpty()) {
            val fees = FundsLogic.feesYouPay(ui.values, resp.funds, ui.groupsById)
            if (fees.knownCount > 0) {
                Text("Yours: about ${FundCostText.dollars(fees.perYear)} a year", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CheckTile(modifier: Modifier, onClick: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    FundsTile("Before you buy", Icons.Filled.Search, modifier, onClick = onClick) {
        Text("Does it repeat what you own?", style = MaterialTheme.typography.labelMedium, color = neutral)
        Box(
            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, neutral.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("Ticker, e.g. QQQM", style = MaterialTheme.typography.bodyMedium, color = neutral)
        }
    }
}

@Composable
private fun CopiesTile(ui: FundsViewModel.UiState, resp: FundOverlapResponse, onClick: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val holdings = ui.mode == FundsViewModel.Mode.HOLDINGS && ui.values.isNotEmpty()
    val copies = FundsLogic.cheaperCopies(resp.funds.keys, resp.funds, ui.groupsById, if (holdings) ui.values else emptyMap())
    FundsTile("Cheaper copies", Icons.Filled.ContentCopy, Modifier.fillMaxWidth(), onClick = onClick) {
        when {
            ui.groups == null && !ui.groupsFailed -> Skeleton(Modifier.fillMaxWidth().height(48.dp))
            ui.groups == null -> Text("Couldn't load the look-alike groups.", style = MaterialTheme.typography.bodySmall, color = Signal)
            copies.isEmpty() -> Text("Each of these is already the cheapest of its measured look-alikes, or has none.",
                style = MaterialTheme.typography.bodySmall, color = neutral)
            else -> {
                copies.take(3).forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.from} → ${c.to}" + (c.toNote?.let { " ($it)" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("saves " + FundCostText.dollars(if (holdings && c.savesYours != null) c.savesYours else c.savesPer10k),
                            style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = GainGreen)
                    }
                }
                Text(if (holdings) "A year, on your money" else "A year per \$10,000 · ${ui.groups.groups.size} measured groups",
                    style = MaterialTheme.typography.labelMedium, color = neutral)
            }
        }
    }
}

/** One fund in a group: ticker, what it covers, its fee, and what the user has in it. */
@Composable
internal fun FundLine(p: FundProfile, value: Double?, onClick: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.symbol, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(FundCostText.shortName(p.name), style = MaterialTheme.typography.labelMedium, color = neutral,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(FundsLogic.covers(p), style = MaterialTheme.typography.labelSmall, color = neutral, maxLines = 2)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(FundCostText.rowFee(p.toCost()) + "/yr", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
            value?.let {
                Text(FundCostText.dollars(Math.round(it).toDouble()), style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
    }
}

/**
 * Pairs in DIFFERENT bets that still overlap (0.80 or closer), and pairs nobody could measure.
 *
 * A bet needs every member to clear 0.90 against every other, so a pair can move together and still
 * be counted apart: SPMO sat at 0.92 with QQQM and 0.87 with VOO on 2026-09-26. Those are the pairs
 * most worth seeing. And a pair with too little shared history is "not measured", which is not the
 * same as "different" — the bet count treats it as separate, so the screen says why.
 */
@Composable
internal fun AlsoOverlapping(resp: FundOverlapResponse) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val betOf = resp.sameBets.flatMapIndexed { i, b -> b.map { it to i } }.toMap()
    val close = setOf(PairVerdict.SAME_FUND, PairVerdict.MOVE_TOGETHER, PairVerdict.OVERLAP_A_LOT)
    val lots = resp.pairs.filter { PairVerdict.of(it.corr) in close && betOf[it.a] != betOf[it.b] }
        .sortedByDescending { it.corr }
    val unmeasured = resp.pairs.filter { it.corr == null }
    if (lots.isEmpty() && unmeasured.isEmpty()) return
    GlowCard(tint = null, spacing = 4.dp) {
        if (lots.isNotEmpty()) {
            Text("Separate bets that still overlap", style = MaterialTheme.typography.labelLarge, color = neutral)
            lots.take(6).forEach { p ->
                Text("${p.a} + ${p.b}: ${PairVerdict.of(p.corr).words.lowercase()} (${FundsLogic.corr(p.corr)})" +
                    if (p.sharedTopCount > 0) " · ${p.sharedTopCount} top holdings shared" else "",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (unmeasured.isNotEmpty()) {
            Text("Not measured (too little shared price history), so counted as separate: " +
                unmeasured.take(8).joinToString(", ") { "${it.a} + ${it.b}" } +
                if (unmeasured.size > 8) " and ${unmeasured.size - 8} more" else "",
                style = MaterialTheme.typography.labelSmall, color = Signal)
        }
    }
}

@Composable
internal fun FeesCard(ui: FundsViewModel.UiState, resp: FundOverlapResponse) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val fees = FundsLogic.feesYouPay(ui.values, resp.funds, ui.groupsById)
    GlowCard(tint = null, spacing = 6.dp) {
        Text("Fund fees", style = MaterialTheme.typography.labelLarge, color = neutral)
        if (fees.knownCount == 0) {
            // Nothing was counted, so there is no total to state — "$0" here would be a claim.
            Text("Yearly fees unknown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("No fee could be found for ${fees.unknownFee.joinToString(", ")}.",
                style = MaterialTheme.typography.labelSmall, color = Signal)
            return@GlowCard
        }
        Text("About ${FundCostText.dollars(fees.perYear)} a year" +
            if (fees.unknownFee.isNotEmpty()) " on the ${FundCostText.dollars(Math.round(fees.countedValue).toDouble())} with a known fee" else "",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        when (val c = fees.cheapestPerYear) {
            null -> Text("Couldn't check the cheaper look-alikes right now.", style = MaterialTheme.typography.bodySmall, color = neutral)
            else -> if (fees.switches.isEmpty()) {
                // "Cheapest" is only said about funds that were actually compared with something.
                when {
                    fees.compared.isEmpty() -> Text("None of these has a measured look-alike to compare it with.",
                        style = MaterialTheme.typography.bodySmall, color = neutral)
                    fees.notCompared.isEmpty() && fees.unknownFee.isEmpty() ->
                        Text("Each of your funds is already the cheapest of its look-alikes.",
                            style = MaterialTheme.typography.bodySmall, color = GainGreen)
                    else -> Text("${fees.compared.joinToString(", ")}: already the cheapest of ${if (fees.compared.size == 1) "its" else "their"} look-alikes.",
                        style = MaterialTheme.typography.bodySmall, color = GainGreen)
                }
            } else {
                Text("In the cheapest look-alikes: about ${FundCostText.dollars(c)} a year", style = MaterialTheme.typography.bodyMedium)
                fees.switches.take(4).forEach { s ->
                    Text("${s.from} → ${s.to}${s.toNote?.let { " ($it)" } ?: ""}: saves about " +
                        "${FundCostText.dollars(s.savesPerYear)} a year on your ${FundCostText.dollars(Math.round(s.value).toDouble())}",
                        style = MaterialTheme.typography.bodySmall, color = GainGreen)
                }
                Text("Selling to switch can mean tax on gains, so the gap matters most for new money.",
                    style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
        if (fees.notCompared.isNotEmpty() && fees.compared.isNotEmpty()) {
            Text("No measured look-alike for ${fees.notCompared.joinToString(", ")}.",
                style = MaterialTheme.typography.labelSmall, color = neutral)
        }
        if (fees.unknownFee.isNotEmpty()) {
            Text("Fee unknown, left out: ${fees.unknownFee.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = Signal)
        }
        val source = FundCostText.sourceLine(ui.values.keys.mapNotNull { resp.funds[it]?.toCost() }, resp.live)
        if (source.isNotBlank()) Text(source, style = MaterialTheme.typography.labelSmall, color = neutral)
        if (ui.unpriced.isNotEmpty()) {
            Text("Couldn't price ${ui.unpriced.joinToString(", ")}, so they're left out of these dollars.",
                style = MaterialTheme.typography.labelSmall, color = Signal)
        }
    }
}

@Composable
internal fun BeforeYouBuyCard(
    check: FundsViewModel.Check?,
    onCheck: (String) -> Unit,
    onClear: () -> Unit,
    onCompare: (String) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var text by rememberSaveable { mutableStateOf("") }
    GlowCard(tint = null, spacing = 8.dp) {
        Text("Check a fund against what you own", style = MaterialTheme.typography.labelLarge, color = neutral)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.uppercase().take(12) },
                label = { Text("Ticker, e.g. QQQM") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onCheck(text) }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onCheck(text) }, enabled = text.isNotBlank()) { Text("Check") }
        }
        when {
            check == null -> Unit
            check.loading -> Skeleton(Modifier.fillMaxWidth().height(48.dp))
            check.failed -> Text("Couldn't check ${check.symbol} right now.", style = MaterialTheme.typography.bodySmall, color = Signal)
            check.notAFund -> Text("${check.symbol} isn't a fund (or Yahoo doesn't know it), so there's nothing to overlap.",
                style = MaterialTheme.typography.bodySmall, color = neutral)
            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(check.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                check.fee?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                check.covers?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral) }
                check.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row {
                    TextButton(onClick = { onCompare(check.symbol) }) { Text("Compare side by side") }
                    TextButton(onClick = { text = ""; onClear() }) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
internal fun CompareCard(resp: FundOverlapResponse?, values: Map<String, Double>, onCompare: (List<String>) -> Unit) {
    val picks = resp?.funds?.keys?.sortedByDescending { values[it] ?: 0.0 }?.take(2).orEmpty()
    GlowCard(tint = null, spacing = 6.dp, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { onCompare(picks) }) {
        Text("Compare funds side by side", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text("Returns with dividends, worst drop, fees in dollars, and how much they overlap.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun GroupsCard(
    ui: FundsViewModel.UiState,
    onOpen: (FundGroup) -> Unit,
    onOpenDetail: (Asset) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val groups = ui.groups?.groups
    when {
        groups == null && ui.groupsFailed -> Text("Couldn't load the look-alike groups.", style = MaterialTheme.typography.bodySmall, color = Signal)
        groups == null -> Skeleton(Modifier.fillMaxWidth().height(80.dp))
        else -> GlowCard(tint = null, spacing = 2.dp) {
            Text("Funds measured to hold the same thing, and what each charges a year per \$10,000.",
                style = MaterialTheme.typography.bodySmall, color = neutral)
            groups.forEach { g -> GroupRow(g, ui.groupPerf[g.id], onOpen, onOpenDetail) }
        }
    }
}

@Composable
internal fun GroupRow(
    g: FundGroup,
    perf: FundsViewModel.GroupPerf?,
    onOpen: (FundGroup) -> Unit,
    onOpenDetail: (Asset) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var open by rememberSaveable(g.id) { mutableStateOf(false) }
    // Also when an open row is restored after the screen was recreated, not only on a tap.
    LaunchedEffect(open) { if (open) onOpen(g) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .clickable { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(g.label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val cheapestEtf = g.funds.firstOrNull { it.kind == "etf" && it.expenseRatioPct != null }
                val cheapestFid = g.funds.firstOrNull { it.fidelity && it.expenseRatioPct != null }
                Text(listOfNotNull(
                    FundsLogic.costRange(g),
                    cheapestEtf?.let { "cheapest ETF ${it.symbol}" },
                    cheapestFid?.takeIf { it.symbol != cheapestEtf?.symbol }?.let { "Fidelity ${it.symbol}" },
                ).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = neutral)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = neutral)
        }
        if (open) {
            val cheapest = g.funds.firstOrNull { it.expenseRatioPct != null }
            val base2y = cheapest?.let { perf?.resp?.funds?.get(it.symbol)?.returns?.get("2y") }
            g.funds.forEach { f ->
                val r2 = perf?.resp?.funds?.get(f.symbol)?.returns?.get("2y")
                val gap = if (f.symbol == cheapest?.symbol) "cheapest"
                else if (r2 != null && base2y != null) "${FundsLogic.pct(r2 - base2y).replace("%", " pts")} vs ${cheapest?.symbol}"
                else null
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenDetail(Asset(f.symbol, com.stocktracker.app.data.model.AssetType.STOCK, f.name ?: f.symbol)) }
                        .padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(f.symbol, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(FundCostText.shortName(f.name), style = MaterialTheme.typography.labelMedium, color = neutral,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val tags = FundCostText.tags(f, isSelf = false)
                        val line = listOfNotNull(tags.ifBlank { null }, gap?.let { "2 years: $it" }).joinToString(" · ")
                        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.labelSmall,
                            color = if (f.fidelity) MaterialTheme.colorScheme.primary else neutral)
                    }
                    Text(FundCostText.rowFee(f), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                }
            }
            when {
                perf?.loading == true -> Text("Measuring 2-year results…", style = MaterialTheme.typography.labelSmall, color = neutral)
                perf?.failed == true -> Text("Couldn't load 2-year results.", style = MaterialTheme.typography.labelSmall, color = Signal)
                else -> Text("\"2 years\" is each fund's result minus the cheapest one's, dividends in: the fee plus any hidden costs.",
                    style = MaterialTheme.typography.labelSmall, color = neutral)
            }
            g.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = neutral) }
            g.funds.firstOrNull { it.feeSource == "issuer" }?.feeDated?.let {
                Text("* the fund company's own figure (${FundCostText.shortDate(it)}): Yahoo's was wrong or missing.",
                    style = MaterialTheme.typography.labelSmall, color = neutral)
            }
            g.funds.firstOrNull { it.feeSource == "saved" }?.feeDated?.let {
                Text("† from a saved list (${FundCostText.shortDate(it)}): Yahoo didn't answer.",
                    style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
    }
}

@Composable
internal fun FeeAlertToggle() {
    val settings = ServiceLocator.settingsStore
    val on by settings.fundFeeNotifyEnabled.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    GlowCard(tint = null, spacing = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Fee change alerts", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("Tell me when a fund I hold raises or cuts its fee.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = { v -> scope.launch { settings.setFundFeeNotifyEnabled(v) } })
        }
    }
}

