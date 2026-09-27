package com.stocktracker.app.ui.funds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.ExploreCategory
import com.stocktracker.app.data.remote.ExploreFund
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.BackendStatusBanner
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.components.Skeleton
import com.stocktracker.app.ui.components.directionTint
import com.stocktracker.app.ui.components.spotlightGlow
import com.stocktracker.app.ui.detail.FundCostText
import com.stocktracker.app.ui.marketscan.watchedStockSymbols
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.Signal
import kotlinx.coroutines.launch

/**
 * FUND-8 — Explore ETFs: about 180 well-known funds, most of which the user does not own, to find
 * one by type, return, worst drop and fee. Glance first (the three top picks of a type), then the
 * ranked list, then a fund's sheet with its cheaper copy, the funds like it, and whether it repeats
 * what the user already holds.
 *
 * [initialCategory] opens the screen on one type (a pill on the Funds screen deep-links here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundExploreScreen(
    initialCategory: String?,
    onBack: () -> Unit,
    onOpenDetail: (Asset) -> Unit,
    onOpenCompare: (List<String>) -> Unit,
    onOpenSignalsSettings: () -> Unit = {},
) {
    val vm: FundExploreViewModel = viewModel()
    val ui by vm.state.collectAsStateWithLifecycle()
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val watchlist by ServiceLocator.watchlistStore.watchlist.collectAsState(initial = emptyList())
    val watched = remember(watchlist) { watchedStockSymbols(watchlist) }
    val owned = remember(watchlist) {
        watchlist.filter { it.type == AssetType.STOCK && (it.shares ?: 0.0) > 0.0 }.map { it.symbol.uppercase() }.toSet()
    }
    var openSym by rememberSaveable { mutableStateOf<String?>(null) }
    var appliedInitial by rememberSaveable { mutableStateOf(false) }
    val resp = ui.resp
    LaunchedEffect(resp != null) {
        if (resp != null && !appliedInitial) {
            appliedInitial = true
            if (initialCategory != null && resp.categories.any { it.id == initialCategory }) vm.setCategory(initialCategory)
        }
    }

    val all = resp?.funds.orEmpty()
    val inType = remember(all, ui.category) { ExploreLogic.filter(all, ui.category, "") }
    val shown = remember(all, ui.category, ui.query, ui.period, ui.sort) {
        ExploreLogic.exactFirst(ExploreLogic.sort(ExploreLogic.filter(all, ui.category, ui.query), ui.period, ui.sort), ui.query)
    }
    val max = shown.mapNotNull { ExploreLogic.ret(it, ui.period) }.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
    val surface = MaterialTheme.colorScheme.surfaceVariant

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Explore ETFs")
                        ExploreLogic.subtitle(resp, ui.category)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = neutral) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item { BackendStatusBanner() }
            when {
                !ui.configured -> item {
                    Column {
                        Text("Needs the signals service. It isn't set up yet.", style = MaterialTheme.typography.bodyMedium, color = neutral)
                        TextButton(onClick = onOpenSignalsSettings) { Text("Set up signals") }
                    }
                }
                resp == null && ui.loading -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Skeleton(Modifier.fillMaxWidth().height(56.dp))
                        Skeleton(Modifier.fillMaxWidth().height(96.dp))
                        Skeleton(Modifier.fillMaxWidth().height(320.dp))
                    }
                }
                resp == null -> item {
                    Column {
                        Text("Couldn't load the fund list.", style = MaterialTheme.typography.bodyMedium, color = Signal)
                        TextButton(onClick = { vm.load() }) { Text("Retry") }
                    }
                }
                else -> {
                    item { SearchField(ui.query, vm::setQuery) }
                    item { CategoryChips(resp.categories, ui.category, vm::setCategory) }
                    if (ui.failed) item {
                        Text("Couldn't refresh. Showing the last list.", style = MaterialTheme.typography.labelMedium, color = Signal,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                    ExploreLogic.staleLine(resp.builtAt)?.let { stale ->
                        item { Text(stale, style = MaterialTheme.typography.labelMedium, color = Signal, modifier = Modifier.padding(bottom = 6.dp)) }
                    }
                    if (ui.query.isBlank()) item { TopPicksRow(inType, ui.period) { openSym = it.symbol } }
                    item { Controls(ui.period, ui.sort, vm::setPeriod, vm::setSort) }
                    item { ListHeader(ui.period, surface, empty = shown.isEmpty()) }
                    itemsIndexed(shown, key = { _, f -> f.symbol }) { i, f ->
                        val shape: Shape = if (i == shown.lastIndex) RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp) else RoundedCornerShape(0.dp)
                        ExploreRow(f, ui.period, max, watched, owned, shape, surface) { openSym = f.symbol }
                    }
                    item {
                        Text(
                            "Returns include dividends. Drop: the biggest fall from a high in that time. " +
                                "Fee: cost a year per \$10,000. —: too new or unknown. Not advice.",
                            style = MaterialTheme.typography.bodySmall, color = neutral, modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
    }

    val open = openSym?.let { s -> all.firstOrNull { it.symbol == s } }
    if (open != null) {
        LaunchedEffect(open.symbol) { vm.check(open.symbol) }
        val scope = rememberCoroutineScope()
        ModalBottomSheet(
            onDismissRequest = { openSym = null; vm.clearCheck() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            FundSheet(
                f = open,
                all = all,
                period = ui.period,
                categoryLabel = resp?.categories?.firstOrNull { it.id == open.category }?.label,
                check = ui.check?.takeIf { it.symbol == open.symbol },
                owned = open.symbol in owned,
                watched = open.symbol in watched,
                onPick = { openSym = it },
                onWatch = {
                    scope.launch { ServiceLocator.watchlistStore.add(Asset(open.symbol, AssetType.STOCK, open.longName ?: open.name)) }
                },
                onOpenChart = {
                    openSym = null
                    onOpenDetail(Asset(open.symbol, AssetType.STOCK, open.longName ?: open.name))
                },
                onCompare = { syms -> openSym = null; onOpenCompare(syms) },
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search, e.g. VOO or gold") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Hide the keyboard, keep focus: clearing focus let a hardware Enter's key-up land on Back.
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    )
}

@Composable
private fun CategoryChips(categories: List<ExploreCategory>, selected: String?, onPick: (String?) -> Unit) {
    val shown = categories.filter { it.count > 0 }
    val state = rememberLazyListState()
    // A type picked from outside (a pill on the Funds screen) may sit off-screen: bring it into view.
    LaunchedEffect(selected) {
        val i = shown.indexOfFirst { it.id == selected }
        if (i >= 0) state.animateScrollToItem(i + 1)
    }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        item { FilterChip(selected = selected == null, onClick = { onPick(null) }, label = { Text("All") }) }
        shown.forEach { c ->
            item(key = c.id) { FilterChip(selected = selected == c.id, onClick = { onPick(c.id) }, label = { Text(c.label) }) }
        }
    }
}

/** Best return, lowest fee and smallest drop of the type on screen — the glance. */
@Composable
private fun TopPicksRow(funds: List<ExploreFund>, period: String, onOpen: (ExploreFund) -> Unit) {
    val picks = ExploreLogic.topPicks(funds, period)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min).padding(bottom = 8.dp)) {
        val best = picks.best
        PickTile("BEST ${period.uppercase()}", best, best?.let { FundsLogic.pctShort(ExploreLogic.ret(it, period)) },
            directionTint(best?.let { ExploreLogic.ret(it, period) }), Modifier.weight(1f).fillMaxHeight(), onOpen)
        val cheap = picks.cheapest
        PickTile("CHEAPEST", cheap, cheap?.let { ExploreLogic.fee(it) + "/yr" }, null, Modifier.weight(1f).fillMaxHeight(), onOpen)
        val calm = picks.calmest
        PickTile("SMALLEST DROP", calm, calm?.let { dropText(ExploreLogic.drop(it, period)) }, null, Modifier.weight(1f).fillMaxHeight(), onOpen)
    }
}

@Composable
private fun PickTile(title: String, f: ExploreFund?, value: String?, tint: Color?, modifier: Modifier, onOpen: (ExploreFund) -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .spotlightGlow(tint)
            .then(if (f != null) Modifier.clickable { onOpen(f) } else Modifier)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = neutral, maxLines = 1)
        if (f == null) {
            Text("—", style = MaterialTheme.typography.titleMedium, color = neutral)
        } else {
            Text(f.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(value ?: "—", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
                color = tint ?: MaterialTheme.colorScheme.onSurface)
            Text(listOfNotNull(f.name, ExploreLogic.tag(f)).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = neutral, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Controls(period: String, sort: RankSort, onPeriod: (String) -> Unit, onSort: (RankSort) -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Over", style = MaterialTheme.typography.labelMedium, color = neutral, modifier = Modifier.width(52.dp))
            ExploreLogic.PERIODS.forEach { p ->
                FilterChip(selected = period == p, onClick = { onPeriod(p) }, label = { Text(p.uppercase()) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sort", style = MaterialTheme.typography.labelMedium, color = neutral, modifier = Modifier.width(52.dp))
            listOf(RankSort.RETURN to "Return", RankSort.DROP to "Smallest drop", RankSort.FEE to "Cheapest").forEach { (s, label) ->
                FilterChip(selected = sort == s, onClick = { onSort(s) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun ListHeader(period: String, surface: Color, empty: Boolean) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (empty) RoundedCornerShape(16.dp) else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(surface).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FUND", style = MaterialTheme.typography.labelSmall, color = neutral, modifier = Modifier.weight(1f))
            Text("${period.uppercase()} RETURN", style = MaterialTheme.typography.labelSmall, color = neutral,
                textAlign = TextAlign.End, modifier = Modifier.width(RET_W))
            Text("DROP", style = MaterialTheme.typography.labelSmall, color = neutral, textAlign = TextAlign.End, modifier = Modifier.width(DROP_W))
            Text("FEE", style = MaterialTheme.typography.labelSmall, color = neutral, textAlign = TextAlign.End, modifier = Modifier.width(FEE_W))
        }
        if (empty) Text("No funds match.", style = MaterialTheme.typography.bodyMedium, color = neutral, modifier = Modifier.padding(vertical = 12.dp))
    }
}

private val RET_W = 72.dp
private val DROP_W = 60.dp
private val FEE_W = 56.dp

/** "0%" for a fund that never fell, "−24.5%" otherwise, "—" when unknown. */
internal fun dropText(d: Double?): String = when {
    d == null || !d.isFinite() -> "—"
    d == 0.0 -> "0%"
    else -> FundsLogic.pct(d)
}

@Composable
private fun ExploreRow(
    f: ExploreFund,
    period: String,
    max: Double,
    watched: Set<String>,
    owned: Set<String>,
    shape: Shape,
    surface: Color,
    onClick: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val ret = ExploreLogic.ret(f, period)
    val drop = ExploreLogic.drop(f, period)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(surface).clickable(onClickLabel = "Open ${f.symbol}", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(f.symbol, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    when {
                        f.symbol in owned -> Pill("Owned", GainGreen)
                        f.symbol in watched -> Pill("Watching", neutral)
                    }
                }
                Text(listOfNotNull(f.name, ExploreLogic.tag(f)).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = neutral, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(FundsLogic.pct(ret), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(RET_W),
                color = when { ret == null -> neutral; ret >= 0 -> GainGreen; else -> LossRed })
            Text(dropText(drop), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End, modifier = Modifier.width(DROP_W), color = if (drop == null || drop == 0.0) neutral else LossRed)
            Text(ExploreLogic.fee(f), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End, modifier = Modifier.width(FEE_W))
        }
        ReturnBar(ret, max, Modifier.fillMaxWidth())
        f.cheaper?.let { c ->
            Text("Cheaper copy: ${c.symbol} saves ${FundCostText.dollars(c.savesPer10k)}" + if (c.fidelityOnly) " (Fidelity only)" else "",
                style = MaterialTheme.typography.labelSmall, color = GainGreen)
        }
    }
}

/** One fund, opened from the list: the numbers, its cheaper copy, funds like it, and what it repeats. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FundSheet(
    f: ExploreFund,
    all: List<ExploreFund>,
    period: String,
    categoryLabel: String?,
    check: FundsViewModel.Check?,
    owned: Boolean,
    watched: Boolean,
    onPick: (String) -> Unit,
    onWatch: () -> Unit,
    onOpenChart: () -> Unit,
    onCompare: (List<String>) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val ret = ExploreLogic.ret(f, period)
    val drop = ExploreLogic.drop(f, period)
    val same = ExploreLogic.sameThing(f, all)
    val similar = ExploreLogic.similar(f, all, period)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.symbol, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(f.name, categoryLabel, ExploreLogic.tag(f)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = neutral)
                f.longName?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = neutral, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            when {
                owned -> Pill("Owned", GainGreen)
                watched -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = neutral, modifier = Modifier.size(16.dp))
                    Text("On your list", style = MaterialTheme.typography.labelMedium, color = neutral)
                }
                else -> FilledTonalButton(onClick = onWatch) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Watch")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            val others = ExploreLogic.PERIODS.filter { it != period }.joinToString(" · ") {
                "${it.uppercase()} ${FundsLogic.pctShort(ExploreLogic.ret(f, it))}"
            }
            StatTile(FundsLogic.periodWords(period).uppercase(), FundsLogic.pct(ret),
                when { ret == null -> neutral; ret >= 0 -> GainGreen; else -> LossRed }, others, Modifier.weight(1f).fillMaxHeight())
            StatTile("FEE", f.expenseRatioPct?.let { if (it == 0.0) "Free" else FundCostText.perTenK(it) + " a year" } ?: "Unknown",
                MaterialTheme.colorScheme.onSurface, "per \$10,000", Modifier.weight(1f).fillMaxHeight(), mono = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            StatTile("WORST DROP", dropText(drop), if (drop == null || drop == 0.0) neutral else LossRed,
                "in ${FundsLogic.periodWords(period)}", Modifier.weight(1f).fillMaxHeight())
            StatTile("SIZE", FundsLogic.size(f.netAssets) ?: "—", MaterialTheme.colorScheme.onSurface,
                if (f.isMutualFund) "Trades once a day" else "ETF", Modifier.weight(1f).fillMaxHeight(), mono = false)
        }
        FundsLogic.smallFundWarning(f.netAssets)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Signal) }

        val c = f.cheaper
        if (c != null) {
            GlowCard(tint = GainGreen, spacing = 4.dp, padding = 14.dp) {
                Text("CHEAPER COPY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = neutral)
                Text("${c.symbol} holds the same thing.", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Saves ${FundCostText.dollars(c.savesPer10k)} a year per \$10,000.", style = MaterialTheme.typography.bodyMedium, color = GainGreen)
                when {
                    c.fidelityOnly -> "Fidelity only. Moving brokers means selling it."
                    c.mutualFund -> "A mutual fund. It trades once a day."
                    else -> null
                }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = neutral) }
                Row {
                    TextButton(onClick = { onPick(c.symbol) }) { Text("See ${c.symbol}") }
                    TextButton(onClick = { onCompare(listOf(f.symbol, c.symbol)) }) { Text("Compare") }
                }
            }
        } else if (f.groupId != null && f.expenseRatioPct != null && same.isNotEmpty()) {
            Text("Cheapest of the funds that hold this.", style = MaterialTheme.typography.bodyMedium, color = GainGreen)
        }

        if (same.isNotEmpty()) {
            Text("Same thing, other funds", style = MaterialTheme.typography.labelLarge, color = neutral)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                same.forEach { o -> Pill("${o.symbol} ${ExploreLogic.fee(o)}", MaterialTheme.colorScheme.primary) { onPick(o.symbol) } }
            }
        }

        if (similar.isNotEmpty()) {
            Text(if (similar.any { it.name == f.name }) "Similar, not the same stocks" else "Others like it",
                style = MaterialTheme.typography.labelLarge, color = neutral)
            similar.forEach { o ->
                val r = ExploreLogic.ret(o, period)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(o.symbol) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(o.symbol, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(o.name, style = MaterialTheme.typography.labelSmall, color = neutral, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(FundsLogic.pct(r), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End, modifier = Modifier.width(76.dp),
                        color = when { r == null -> neutral; r >= 0 -> GainGreen; else -> LossRed })
                    Text(ExploreLogic.fee(o), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                }
            }
        }

        Text("Does it repeat what you own?", style = MaterialTheme.typography.labelLarge, color = neutral)
        when {
            check == null || check.loading -> Skeleton(Modifier.fillMaxWidth().height(44.dp))
            check.failed -> Text("Couldn't check right now.", style = MaterialTheme.typography.bodySmall, color = Signal)
            check.notAFund -> Text("Couldn't read what it holds.", style = MaterialTheme.typography.bodySmall, color = neutral)
            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                check.covers?.let { Text("Holds: $it", style = MaterialTheme.typography.bodySmall, color = neutral) }
                check.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        Row {
            TextButton(onClick = onOpenChart) { Text("Open chart") }
            val partner = c?.symbol ?: similar.firstOrNull()?.symbol
            if (partner != null) TextButton(onClick = { onCompare(listOf(f.symbol, partner)) }) { Text("Compare with $partner") }
        }
        Text("Past returns don't predict what comes next. Not advice.", style = MaterialTheme.typography.labelSmall, color = neutral)
    }
}

@Composable
private fun StatTile(title: String, value: String, color: Color, sub: String?, modifier: Modifier, mono: Boolean = true) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = neutral)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, color = color)
        sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = neutral) }
    }
}
