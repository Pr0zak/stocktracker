package com.stocktracker.app.ui.watchlist

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.Speed
import java.util.Locale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.VixQuote
import com.stocktracker.app.data.remote.DipReject
import com.stocktracker.app.data.remote.GateLeg
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.first
import com.stocktracker.app.ui.components.AssetRow
import com.stocktracker.app.ui.components.FearGauge
import com.stocktracker.app.ui.components.SessionTimelineBar
import com.stocktracker.app.ui.components.SwipeToDeleteRow
import androidx.compose.material3.Card
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.util.Formatting
import com.stocktracker.app.util.Freshness
import com.stocktracker.app.util.listFreshness
import com.stocktracker.app.util.readingAgeLabel
import com.stocktracker.app.util.staleRowCount
import com.stocktracker.app.util.MarketClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.stocktracker.app.ui.theme.CryptoAccent
import com.stocktracker.app.ui.theme.EtfAccent
import com.stocktracker.app.ui.theme.Signal
import com.stocktracker.app.ui.theme.CategoricalRamp

// Built-in tabs; user-defined watchlist names extend the row after these.
private const val TAB_ALL = "All"
private const val TAB_STOCKS = "Stocks"
private const val TAB_CRYPTO = "Crypto"
private const val TAB_BELOW = "Below 4-yr avg" // computed tab, shown only when a name is below its 200-week line

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    onOpenDetail: (Asset) -> Unit,
    onAdd: () -> Unit,
    onOpenVix: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenDips: () -> Unit = {},
    onOpenHeatmap: () -> Unit = {},
    onOpenMarketScan: () -> Unit = {},
    onOpenSignalsSettings: () -> Unit = {},
) {
    val vm: WatchlistViewModel = viewModel()
    val state by vm.state.collectAsState()
    val hideZeroCents by ServiceLocator.settingsStore.hideZeroCents.collectAsState(initial = false)
    val showMarketStatus by ServiceLocator.settingsStore.showMarketStatus.collectAsState(initial = true)
    val showVix by ServiceLocator.settingsStore.showVix.collectAsState(initial = true)
    val showGate by ServiceLocator.settingsStore.showGate.collectAsState(initial = true)
    val groups by ServiceLocator.settingsStore.watchlistGroups.collectAsState(initial = emptyList())
    val groupBySector by ServiceLocator.settingsStore.watchlistGroupBySector.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    val marketState by produceState(initialValue = MarketClock.now()) {
        while (true) {
            value = MarketClock.now()
            delay(60_000)
        }
    }
    // The freshness line has to age on its own. Without a ticking clock it would read "Updated just
    // now" for as long as the screen sits untouched — which is exactly the window where a price
    // quietly going stale is worth knowing about.
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(20_000)
            value = System.currentTimeMillis()
        }
    }
    // One VIX for the whole app, from the shared market context. This used to be a poll of its own
    // here, a second one on the VIX detail screen and a third inside the detail view model's signal
    // inputs; the repository's own cache hid most of the cost, but not the disagreement — each
    // caller had its own idea of whether the last read had succeeded.
    val marketCtx = ServiceLocator.marketContext
    val marketContext by marketCtx.state.collectAsStateWithLifecycle()
    val vix = marketContext.vix
    LaunchedEffect(Unit) {
        while (true) {
            marketCtx.refreshVix()
            delay(120_000)
        }
    }
    var selected by remember { mutableStateOf(TAB_ALL) }
    val collapsed by ServiceLocator.settingsStore.collapsedVerticals.collectAsState(initial = emptySet())
    // A deleted/emptied list shouldn't leave us stranded on a missing tab.
    LaunchedEffect(groups) {
        if (selected !in listOf(TAB_ALL, TAB_STOCKS, TAB_CRYPTO, TAB_BELOW) && selected !in groups) selected = TAB_ALL
    }
    // Switching to grouped removes the Stocks/Crypto pills. Standing on one when it disappears would
    // leave the list silently filtered by a control no longer on screen — a subset with nothing to
    // explain it, and no way back except guessing.
    LaunchedEffect(groupBySector) {
        if (groupBySector && selected in listOf(TAB_STOCKS, TAB_CRYPTO)) selected = TAB_ALL
    }
    // Collapsed by default — the holdings are why the screen exists.
    // Persisted, not remembered. Collapsed-by-default is the right default; collapsing again on
    // every cold start is not, because two of the things inside this strip — the dip list and the
    // VIX detail — had no other way in, so a user who opened it yesterday had to rediscover that it
    // opens at all.
    val contextOpen by ServiceLocator.settingsStore.contextStripOpen.collectAsState(initial = false)
    var showNewListDialog by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    var confirmDeleteGroup by remember { mutableStateOf<String?>(null) }

    // Reorder is only meaningful in the unfiltered, UNGROUPED view, where display order == stored
    // order. Grouped, the position of a row is derived from its sector and a drag has nowhere to land.
    val reorderEnabled = selected == TAB_ALL && !groupBySector
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromId = from.key as? String
        val toId = to.key as? String
        if (fromId != null && toId != null) vm.moveLocal(fromId, toId)
    }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging) vm.persistOrder()
    }

    // A deleted ticker takes its shares, cost basis and alerts with it, and nothing else in the app
    // holds them — so the delete has to be reversible rather than merely hard to trigger.
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.recentlyRemoved) {
        val gone = state.recentlyRemoved ?: return@LaunchedEffect
        val held = (gone.shares ?: 0.0) > 0.0
        val result = snackbarHost.showSnackbar(
            message = if (held) "Removed ${gone.symbol} and its position" else "Removed ${gone.symbol}",
            actionLabel = "Undo",
            duration = if (held) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoRemove() else vm.clearUndo()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                // The freshness line lives here, under the title, since 2026-09-24 (user request): it
                // qualifies every number on the screen, so it sits above all of them, beside the
                // refresh button it pairs with, and no longer spends a row of the list.
                title = {
                    Column {
                        Text("StockTracker")
                        if (state.items.isNotEmpty()) {
                            val stamps = state.items.map { it.quote?.asOfEpochMs ?: 0L }
                            FreshnessLine(
                                freshness = listFreshness(stamps, nowMs, marketState.phase),
                                staleRows = staleRowCount(stamps, nowMs, marketState.phase),
                                totalRows = stamps.size,
                                // Also while the initial load runs: on a first-ever launch every row has
                                // no timestamp, and "Never updated" is true but reads as a fault when
                                // the truth is that the first fetch simply has not landed yet.
                                refreshing = state.refreshing || state.loading,
                                error = state.refreshError,
                                onRefresh = { vm.refresh() },
                            )
                        }
                    }
                },
                actions = {
                    // Five unlabelled glyphs became one icon and one menu. The heat map, the market
                    // scan and the catalyst calendar moved to the Markets tab, where they have
                    // names; what is left here is the refresh, which is conventional, and the two
                    // things that are genuinely about YOUR list rather than the market's.
                    IconButton(onClick = { vm.refresh() }, enabled = !state.refreshing) {
                        if (state.refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh prices")
                        }
                    }
                    com.stocktracker.app.ui.components.LabeledOverflow(
                        listOf(
                            com.stocktracker.app.ui.components.OverflowAction(
                                label = "Market now — AI overview",
                                icon = Icons.Default.AutoAwesome,
                                onClick = { vm.openMarketNow() },
                            ),
                            com.stocktracker.app.ui.components.OverflowAction(
                                label = "Add a ticker",
                                icon = Icons.Default.Add,
                                onClick = onAdd,
                            ),
                        ),
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add ticker")
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { innerPadding ->
        val filtered = state.items.filter { item ->
            when (selected) {
                TAB_ALL -> true
                TAB_STOCKS -> item.asset.type == AssetType.STOCK
                TAB_CRYPTO -> item.asset.type == AssetType.CRYPTO
                TAB_BELOW -> item.below200wma == true
                else -> item.asset.groups.contains(selected)
            }
        }

        // Hoisted: LazyListScope isn't composable, and an empty banner item would still eat its
        // 12dp of spacedBy, leaving a dead band above the first row whenever the backend is fine.
        val backendOffline = com.stocktracker.app.ui.components.backendOffline()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (backendOffline) {
                    item(key = "hdr:offline") { com.stocktracker.app.ui.components.BackendStatusBanner() }
                }
                // DP-5: the Daily Pick leads the tab. It renders nothing when no Signals URL is set.
                item(key = "hdr:pick") {
                    com.stocktracker.app.ui.pick.DailyPickCard(
                        onOpenSymbol = { sym, name ->
                            onOpenDetail(Asset(symbol = sym, type = AssetType.STOCK, displayName = name ?: sym))
                        },
                        onOpenSettings = onOpenSignalsSettings,
                    )
                }
                item(key = "hdr:tabs") {
                    val belowTab = if (state.items.any { it.below200wma == true }) listOf(TAB_BELOW) else emptyList()
                    // Stocks and Crypto are dropped while grouping is on, because the sections
                    // already do that job: everything crypto sits under its own heading and
                    // everything else is, by definition, the Stocks tab. Keeping them would leave
                    // two controls for one split, and the pill row is where the horizontal space
                    // runs out first -- "Below 200w" was already scrolling off the right edge.
                    //
                    // They stay in the flat view, where they are the ONLY way to separate the two.
                    val typeTabs = if (groupBySector) emptyList() else listOf(TAB_STOCKS, TAB_CRYPTO)
                    val tabs = listOf(TAB_ALL) + typeTabs + belowTab + groups
                    val faint = MaterialTheme.colorScheme.onSurfaceVariant
                    val primary = MaterialTheme.colorScheme.primary
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The view mode leads the row rather than owning a line of its own. It was a
                        // full-width row carrying a label and five words of instruction ("Tap to
                        // reorder manually") for one binary toggle, on a screen where four stacked
                        // control rows stood between the title and the first ticker.
                        ModeChip(
                            label = if (groupBySector) "Sector" else "Manual",
                            onClick = {
                                scope.launch {
                                    ServiceLocator.settingsStore.setWatchlistGroupBySector(!groupBySector)
                                }
                            },
                        )
                        tabs.forEach { tab ->
                            // Null while the first load is still running: an empty list then means
                            // "not read yet", and printing it as "All 0" claims an empty watchlist.
                            val count = if (state.loading && state.items.isEmpty()) null else when (tab) {
                                TAB_ALL -> state.items.size
                                TAB_STOCKS -> state.items.count { it.asset.type == AssetType.STOCK }
                                TAB_CRYPTO -> state.items.count { it.asset.type == AssetType.CRYPTO }
                                TAB_BELOW -> state.items.count { it.below200wma == true }
                                else -> state.items.count { it.asset.groups.contains(tab) }
                            }
                            val dot = when (tab) {
                                TAB_ALL -> null
                                TAB_STOCKS -> faint
                                TAB_CRYPTO -> CryptoAccent
                                TAB_BELOW -> CategoricalRamp[1]
                                else -> primary
                            }
                            ListChip(
                                label = tab,
                                count = count,
                                dotColor = dot,
                                selected = selected == tab,
                                onClick = { selected = tab },
                            )
                        }
                        NewListChip(onClick = { showNewListDialog = true })
                    }
                }

                // Market context — dips, session, regime, VIX — behind ONE line by default.
                //
                // These four cards ran to roughly a thousand pixels before the first holding, which
                // put the list this screen exists for below the fold. Collapsing rather than
                // removing keeps every affordance (the VIX card opens its detail, the regime card
                // refreshes, dips expand) while the summary line still carries the state: session,
                // regime, VIX level and dip count. Expanded state is remembered.
                val reg = state.regime
                val hasRegime = reg.result?.regime?.label?.isNotBlank() == true || reg.loading || reg.error != null
                val gateSummary = GateRead.summary(state.gate.result)
                // The gate card appears whenever it holds a reading, is fetching one, or failed to
                // get one — and never on a device with no Signals URL, where GateUi is left empty.
                val hasGate = showGate && (gateSummary != null || state.gate.loading || state.gate.error != null)
                // SWT-14: the strip now has something to say in every state, so it no longer needs a
                // non-empty dip list to justify the context row. The ONE exception is a radar nobody
                // configured — "set the Signals URL" shouldn't conjure a market-context section onto
                // the dashboard of a user who never asked for one. (It still renders inside the
                // section when something else opened it, which is where that state is worth knowing.)
                val hasDips = state.dipRadar !is DipRadarState.NotConfigured
                val anyContext = hasDips || showMarketStatus || hasRegime || hasGate || (showVix && vix != null)
                if (anyContext) {
                    item(key = "hdr:context") {
                        MarketContext(
                            expanded = contextOpen,
                            onToggle = {
                                scope.launch {
                                    ServiceLocator.settingsStore.setContextStripOpen(!contextOpen)
                                }
                            },
                            marketState = marketState,
                            regime = reg,
                            vix = vix,
                            vixStale = marketContext.vixFailed,
                            // The strip's own summary word, from the shared state machine — so the
                            // collapsed line cannot claim "no dips" while the card behind it says the
                            // scan service is unreachable.
                            dipChip = DipRadar.chip(state.dipRadar),
                            gate = gateSummary.takeIf { showGate },
                            showMarketStatus = showMarketStatus,
                            showVix = showVix,
                            hasRegime = hasRegime,
                        )
                    }
                    if (contextOpen) {
                        // Option A (2026-09-24): one card, one line per question. Each line opens
                        // the full card it summarises, in place, so every affordance the five
                        // stacked cards had (refresh, the checks' legs, the dip list) is still one
                        // tap away — the card just stops being a screen and a half tall.
                        item(key = "hdr:checklist") {
                            MarketChecklist(
                                marketState = marketState,
                                showMarketStatus = showMarketStatus,
                                regime = reg.takeIf { hasRegime },
                                gate = state.gate.takeIf { hasGate },
                                gateSummary = gateSummary,
                                vix = vix.takeIf { showVix },
                                vixAge = readingAgeLabel(marketContext.vixFetchedAtMs, nowMs, failed = marketContext.vixFailed),
                                dipRadar = state.dipRadar.takeIf { hasDips },
                                dipChip = DipRadar.chip(state.dipRadar),
                                onOpenVix = onOpenVix,
                                onRefreshAll = {
                                    if (hasRegime) vm.loadRegime(force = true)
                                    if (hasGate) vm.loadGate(force = true)
                                    if (hasDips) vm.reloadDips()
                                },
                                session = { SessionTimelineBar(marketState) },
                                // Opening a line shows only what the line does not already say —
                                // the full cards would repeat their own header under it.
                                regimeCard = { RegimeDetail(reg) },
                                gateCard = { GateDetail(state.gate) },
                                dipCard = {
                                    DipStripSection(
                                        // A same-session refresh failure names itself; short of that, a
                                        // reading merely old still says its age rather than passing for
                                        // current (DATA-9).
                                        stale = state.dipStale
                                            ?: DipRadar.restoredNote(state.dipRadar, state.scanFetchedAtMs, nowMs),
                                        state = state.dipRadar,
                                        onOpenAll = onOpenDips,
                                        onRetry = { vm.reloadDips() },
                                    )
                                },
                            )
                        }
                    }
                }

                // Offer to delete the currently-selected user list (not the computed Below-200w tab).
                if (selected !in listOf(TAB_ALL, TAB_STOCKS, TAB_CRYPTO, TAB_BELOW)) {
                    item(key = "hdr:deletelist") {
                        TextButton(onClick = { confirmDeleteGroup = selected }) {
                            Text("Delete “$selected” list")
                        }
                    }
                }

                if (filtered.isEmpty() && !state.loading) {
                    item(key = "hdr:empty") {
                        Text(
                            when (selected) {
                                TAB_ALL -> "No tickers yet — tap + to add one."
                                TAB_STOCKS, TAB_CRYPTO -> "Nothing here yet."
                                else -> "No tickers in this list. Open a ticker → Lists to add it."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // One row, rendered identically whether it sits in a flat list or under a heading.
                // Hoisted so the two paths below cannot drift apart — a favourite that lost its
                // sparkline, or a grouped row that stopped being swipeable, is exactly the kind of
                // difference nobody notices until it has shipped.
                @Composable
                fun GroupedAssetRow(item: WatchlistItemUi, handle: Modifier, draggable: Boolean) {
                    SwipeToDeleteRow(onDelete = { vm.remove(item.asset) }) {
                        val q = item.quote
                        val up = q?.isUp ?: true
                        val shares = item.asset.shares
                        val hasPosition = shares != null && shares > 0.0 && q != null
                        val holdingsShares = if (hasPosition) "${Formatting.shares(shares!!)} sh" else null
                        val holdingsValue = if (hasPosition) {
                            Formatting.price(shares!! * q!!.price, q.currency, hideZeroCents)
                        } else {
                            null
                        }
                        // The drag modifier is passed IN, not built here: longPressDraggableHandle
                        // is an extension on ReorderableItem's own scope, which this hoisted helper
                        // is deliberately outside of so the grouped path can reuse it.
                        Box(handle) {
                            AssetRow(
                                symbol = item.asset.symbol,
                                name = item.asset.displayName,
                                priceText = q?.let { Formatting.price(it.price, it.currency, hideZeroCents) } ?: "—",
                                changeText = q?.let { Formatting.changeLine(it.change, it.changePercent, it.isUp, hideZeroCents, reference = it.price) } ?: "…",
                                up = up,
                                sparkline = item.sparkline,
                                // The level changeText is measured from, so the line and the
                                // number can be read against the same baseline.
                                previousClose = q?.prevClose,
                                holdingsShares = holdingsShares,
                                holdingsValue = holdingsValue,
                                isCrypto = item.asset.type == AssetType.CRYPTO,
                                isEtf = item.quote?.isEtf == true,
                                belowLine = item.below200wma == true,
                                onClick = { onOpenDetail(item.asset) },
                                showDragHandle = draggable,
                                favorite = item.asset.favorite,
                                onToggleFavorite = { vm.toggleFavorite(item.asset) },
                                changePercent = q?.changePercent,
                            )
                        }
                    }
                }

                if (groupBySector) {
                    val sections = WatchlistVerticals.group(
                        rows = filtered,
                        isFavorite = { it.asset.favorite },
                        verticalOf = {
                            WatchlistVerticals.verticalFor(
                                type = it.asset.type,
                                symbol = it.asset.symbol,
                                isEtf = it.quote?.isEtf == true,
                                knownSectors = state.sectors,
                            )
                        },
                    )
                    sections.forEach { (heading, rows) ->
                        val isOpen = heading !in collapsed
                        item(key = "sec:$heading") {
                            SectionHeading(
                                label = heading,
                                count = rows.size,
                                expanded = isOpen,
                                onToggle = {
                                    scope.launch {
                                        ServiceLocator.settingsStore.toggleCollapsedVertical(heading)
                                    }
                                },
                            )
                        }
                        // Collapsed sections emit no row items at all, so the LazyColumn does not
                        // compose or measure them. With 52 tickers across a dozen sectors that is
                        // the difference between a screen you scroll and one you navigate.
                        if (isOpen) {
                            items(rows, key = { it.asset.id }) { item ->
                                GroupedAssetRow(item, Modifier, draggable = false)
                            }
                        }
                    }
                } else {
                    items(filtered, key = { it.asset.id }) { item ->
                        ReorderableItem(reorderState, key = item.asset.id) { _ ->
                            GroupedAssetRow(
                                item,
                                if (reorderEnabled) Modifier.longPressDraggableHandle() else Modifier,
                                draggable = reorderEnabled,
                            )
                        }
                    }
                }
            }

            if (state.loading && state.items.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }

    if (state.marketNow.open) {
        MarketNowDialog(
            ui = state.marketNow,
            onRefresh = { vm.loadMarketNow(force = true) },
            onDismiss = { vm.dismissMarketNow() },
            onOpenSignalsSettings = onOpenSignalsSettings,
        )
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
                TextButton(onClick = {
                    val name = newListName.trim()
                    if (name.isNotEmpty()) {
                        vm.createGroup(name)
                        selected = name
                    }
                    showNewListDialog = false
                    newListName = ""
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewListDialog = false; newListName = "" }) { Text("Cancel") }
            },
        )
    }

    confirmDeleteGroup?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDeleteGroup = null },
            title = { Text("Delete list") },
            text = { Text("Delete the “$name” list? Your tickers stay in the app; only this grouping is removed.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteGroup(name)
                    if (selected == name) selected = TAB_ALL
                    confirmDeleteGroup = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteGroup = null }) { Text("Cancel") } },
        )
    }
}

/**
 * One line standing in for the market-context cards, with a chevron to open them.
 *
 * Carries the same four facts the cards do — session, regime, VIX level, dip count — so collapsing
 * costs state, not information. Everything behind it stays reachable; nothing is deleted.
 */
/**
 * When these prices were last read, and a way to read them again.
 *
 * Every price surface in this app falls back to the last cached quote when a fetch fails, so an
 * outage and a quiet market render identically — same numbers, nothing moving. This line is the only
 * thing on the screen that can tell them apart, which is why it stays visible when everything is
 * fine rather than appearing only on trouble: a line that shows up only when something is wrong
 * teaches nobody where to look, and its absence is indistinguishable from not having noticed.
 *
 * Tapping it refreshes. That makes the diagnosis and the fix the same gesture, in the same place,
 * instead of a warning here and an icon in the app bar.
 */
@Composable
private fun FreshnessLine(
    freshness: Freshness,
    staleRows: Int,
    totalRows: Int,
    refreshing: Boolean,
    error: String?,
    onRefresh: () -> Unit,
) {
    // A refresh in flight is never a warning, whatever the timestamp still says — the alarm colour
    // belongs to "this is old and nothing is being done about it", and on a first launch every row
    // is legitimately timestamp-less while the first fetch is still on the wire.
    val warn = !refreshing && (error != null || freshness.stale)
    val tint = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    // A subtitle under the app title: one short line, tappable to refresh (the refresh button sits
    // right beside it). No "Tap to refresh" label — the icon at the start of the line says it.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !refreshing, onClickLabel = "Refresh prices") { onRefresh() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (refreshing) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
        } else {
            Icon(
                imageVector = if (warn) Icons.Filled.Warning else Icons.Default.Refresh,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = when {
                refreshing -> "Refreshing…"
                // The age belongs in the failure, not beside it: "couldn't reach it" answers why the
                // numbers stopped moving, "last read 14m ago" answers how much that costs you.
                error != null -> "$error · last read ${freshness.since}"
                // Naming the count matters when it is a subset: "Updated 9m ago" alone invites the
                // reading that everything is 9m old, when in fact one row is and eleven are current.
                staleRows in 1 until totalRows -> "${freshness.label} · $staleRows of $totalRows out of date"
                else -> freshness.label
            },
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            // A failure keeps its full sentence; the everyday "Updated 2m ago" stays one line.
            maxLines = if (error != null && !refreshing) 2 else 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * The market context, folded to ONE line (2026-09-24 redesign — the pill version wrapped to two
 * rows and read as a jumble of chips). Left to right: a session dot and name, the one verdict that
 * matters in its colour, then the VIX and the dip count as small icon + number pairs.
 *
 * It shows ONE verdict where the pill version could show two (the regime label AND the checks'
 * lead): a failing check wins, since it is the one with a warning. The other, the full wording and
 * every card behind them are one tap away. The verdict ellipsizes rather than wrapping.
 */
@Composable
private fun MarketContext(
    expanded: Boolean,
    onToggle: () -> Unit,
    marketState: com.stocktracker.app.util.MarketState,
    regime: RegimeUi,
    vix: VixQuote?,
    /** The dip fact in two words, or null when there is nothing honest to compress. */
    dipChip: String?,
    /** True when the last VIX re-read failed and [vix] is the previous reading. */
    vixStale: Boolean,
    /** SWT-13 — the gate verdict, already resolved. Null = no reading, and no claim. */
    gate: GateSummary?,
    showMarketStatus: Boolean,
    showVix: Boolean,
    hasRegime: Boolean,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    // The verdict: where the AI's regime read and the market checks disagree, one phrase that says
    // both ("Indexes up, most stocks lagging"); otherwise the regime label, else the checks' lead.
    val verdict: Pair<String, Color>? = run {
        val combined = if (hasRegime) GateRead.combinedStrip(regime.result?.regime?.trend, gate) else null
        // The folded line has about 20 characters to spare on a phone; the full sentence is in the
        // expanded view. Same meaning, fewer letters.
        if (combined != null) return@run combined.replace("Indexes up, most stocks lagging", "Indexes up, most lag") to Signal
        val regimeBit = if (hasRegime) regime.result?.regime?.label?.takeIf { it.isNotBlank() }?.let { lbl ->
            val trend = regime.result?.regime?.trend
            lbl to if (trend == "up") GainGreen else if (trend == "down") LossRed else neutral
        } else null
        // Unmeasured gets the amber, never the red: a gate that couldn't read a leg has not observed
        // a bearish market. A failed check is amber too — a caution about conditions, not a loss.
        val gateBit = gate?.let { g ->
            g.chip.substringBefore(":") to when (g.verdict) {
                GateVerdict.OPEN -> GainGreen
                GateVerdict.SHUT, GateVerdict.UNMEASURED -> Signal
                GateVerdict.UNAVAILABLE -> neutral
            }
        }
        // A failing check outranks a calm regime label: it is the one with something to warn about.
        if (gateBit != null && gateBit.second == Signal) gateBit else regimeBit ?: gateBit
    }
    val sessionColor = when (marketState.phase) {
        com.stocktracker.app.util.MarketPhase.REGULAR -> GainGreen
        com.stocktracker.app.util.MarketPhase.PRE, com.stocktracker.app.util.MarketPhase.AFTER -> Signal
        com.stocktracker.app.util.MarketPhase.CLOSED -> neutral
    }
    val dipCount = dipChip?.let { Regex("^(\\d+) dips?$").find(it)?.groupValues?.get(1) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = if (expanded) "Hide market context" else "Show market context") { onToggle() }
            .heightIn(min = 44.dp)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showMarketStatus) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(7.dp).background(sessionColor, RoundedCornerShape(50)))
                Text(marketState.label.removePrefix("Market ").replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium, color = neutral, maxLines = 1)
            }
        }
        Text(
            verdict?.first ?: "",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = verdict?.second ?: neutral,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showVix) vix?.let {
            // The old reading says so in its colour here and in words for a screen reader.
            MiniStat(Icons.Filled.Speed, String.format(Locale.US, "%.1f", it.value),
                if (vixStale) Signal else neutral,
                "VIX ${String.format(Locale.US, "%.1f", it.value)}" + if (vixStale) ", last reading, refresh failed" else "")
        }
        dipChip?.let { chip ->
            if (dipCount != null) MiniStat(Icons.Filled.TrendingDown, dipCount, neutral, chip)
            else Text(chip, style = MaterialTheme.typography.labelMedium, color = neutral, maxLines = 1)
        }
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = neutral,
        )
    }
}

/** A small icon + number, e.g. the VIX's gauge and its level. [description] is what a reader hears. */
@Composable
private fun MiniStat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, color: Color, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** Which line of the market checklist is open, if any. */
private enum class ChecklistLine { SESSION, TREND, CHECKS, DIPS }

/**
 * The expanded market context as ONE card with a line per question — session, trend, the market
 * checks, fear, dips. Each line is a short answer plus a small visual; tapping it opens the full
 * card it stands for underneath (the VIX line opens its own screen, as its card did).
 *
 * A null argument means that line is not shown at all (its setting is off, or nothing is
 * configured). Loading and failure are shown in the line itself, never as a blank.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MarketChecklist(
    marketState: com.stocktracker.app.util.MarketState,
    showMarketStatus: Boolean,
    regime: RegimeUi?,
    gate: GateUi?,
    gateSummary: GateSummary?,
    vix: VixQuote?,
    vixAge: String?,
    dipRadar: DipRadarState?,
    dipChip: String?,
    onOpenVix: () -> Unit,
    onRefreshAll: () -> Unit,
    session: @Composable () -> Unit,
    regimeCard: @Composable () -> Unit,
    gateCard: @Composable () -> Unit,
    dipCard: @Composable () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var open by rememberSaveable { mutableStateOf<ChecklistLine?>(null) }
    fun toggle(l: ChecklistLine) { open = if (open == l) null else l }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        var first = true
        @Composable
        fun Divider() {
            if (!first) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp)
                .background(neutral.copy(alpha = 0.14f)))
            first = false
        }

        if (showMarketStatus) {
            Divider()
            val zone = java.time.ZoneId.systemDefault()
            val (text, color) = when (marketState.phase) {
                com.stocktracker.app.util.MarketPhase.REGULAR ->
                    "Open · closes ${com.stocktracker.app.ui.pick.DailyPickRead.etClock(16, 0, zone)}" to GainGreen
                com.stocktracker.app.util.MarketPhase.PRE ->
                    "Pre-market · opens ${com.stocktracker.app.ui.pick.DailyPickRead.etClock(9, 30, zone)}" to Signal
                com.stocktracker.app.util.MarketPhase.AFTER ->
                    "After-hours · until ${com.stocktracker.app.ui.pick.DailyPickRead.etClock(20, 0, zone)}" to Signal
                com.stocktracker.app.util.MarketPhase.CLOSED -> marketState.label to neutral
            }
            ChecklistRow(
                icon = Icons.Filled.Schedule, tint = color, title = "Session", detail = text,
                expanded = open == ChecklistLine.SESSION, onClick = { toggle(ChecklistLine.SESSION) },
                // During the regular session the bar is how far through 9:30-4:00 ET we are, to match
                // "closes 3:00 PM"; outside it, the position across the whole 4 AM-8 PM ET window.
                trailing = marketState.markerFraction?.let { f ->
                    val shown = if (marketState.phase == com.stocktracker.app.util.MarketPhase.REGULAR) {
                        val a = com.stocktracker.app.util.MarketClock.preEndFraction
                        val b = com.stocktracker.app.util.MarketClock.regEndFraction
                        ((f - a) / (b - a)).coerceIn(0f, 1f)
                    } else f
                    { MiniBar(shown, MaterialTheme.colorScheme.primary) }
                },
            )
            if (open == ChecklistLine.SESSION) Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { session() }
        }

        regime?.let { r ->
            Divider()
            val res = r.result?.regime
            val trendColor = when (res?.trend) { "up" -> GainGreen; "down" -> LossRed; else -> neutral }
            val detail = when {
                res?.label?.isNotBlank() == true ->
                    res.label + (res.volatility.takeIf { it.isNotBlank() }?.let { " · vol ${it.lowercase()}" } ?: "")
                r.loading -> "Reading…"
                r.error != null -> "Couldn't read the trend"
                else -> "No reading yet"
            }
            ChecklistRow(
                icon = Icons.Filled.ShowChart, tint = trendColor, title = "Trend", detail = detail,
                detailColor = if (r.error != null && res == null) Signal else neutral,
                expanded = open == ChecklistLine.TREND, onClick = { toggle(ChecklistLine.TREND) },
            )
            if (open == ChecklistLine.TREND) regimeCard()
        }

        gate?.let { g ->
            Divider()
            val legs = g.result?.legs.orEmpty()
            val passed = legs.count { it.ok == true }
            val color = when (gateSummary?.verdict) {
                GateVerdict.OPEN -> GainGreen
                GateVerdict.SHUT, GateVerdict.UNMEASURED -> Signal
                else -> neutral
            }
            val title = if (legs.isNotEmpty() && gateSummary?.verdict != GateVerdict.UNAVAILABLE)
                "Market checks · $passed of ${legs.size} pass" else "Market checks"
            val detail = when {
                gateSummary != null -> gateSummary.detail ?: gateSummary.headline
                g.loading -> "Reading…"
                g.error != null -> "Couldn't read the checks"
                else -> "No reading yet"
            }
            ChecklistRow(
                icon = Icons.Filled.Checklist, tint = color, title = title, detail = detail,
                detailColor = if (color == Signal) Signal else neutral,
                expanded = open == ChecklistLine.CHECKS, onClick = { toggle(ChecklistLine.CHECKS) },
                trailing = legs.takeIf { it.isNotEmpty() }?.let { l -> { LegDots(l) } },
            )
            if (open == ChecklistLine.CHECKS) gateCard()
        }

        vix?.let { v ->
            Divider()
            val color = when (v.zone) {
                com.stocktracker.app.data.model.VixZone.CALM, com.stocktracker.app.data.model.VixZone.NORMAL -> GainGreen
                com.stocktracker.app.data.model.VixZone.ELEVATED -> Signal
                else -> LossRed
            }
            // VIX up is more fear, so its move is coloured the other way round from a price.
            val move = if (v.change.isFinite()) {
                (if (v.change >= 0) "▲ " else "▼ ") + String.format(Locale.US, "%.2f", kotlin.math.abs(v.change)) + " today"
            } else null
            ChecklistRow(
                icon = Icons.Filled.Speed, tint = color,
                title = "Fear · VIX ${String.format(Locale.US, "%.2f", v.value)}",
                detail = listOfNotNull(v.zone.label, vixAge ?: move).joinToString(" · "),
                detailColor = if (vixAge != null) Signal else neutral,
                expanded = false, onClick = onOpenVix, chevron = true,
                trailing = { MiniBar((v.value / 40.0).toFloat(), color) },
            )
        }

        dipRadar?.let { d ->
            Divider()
            val ready = d as? DipRadarState.Ready
            val title = when {
                ready != null && ready.dips.isNotEmpty() -> "Dips · ${ready.dips.size} off their highs"
                else -> "Dips" + (dipChip?.let { " · $it" } ?: "")
            }
            ChecklistRow(
                icon = Icons.Filled.TrendingDown, tint = if (ready?.dips?.isNotEmpty() == true) LossRed else neutral,
                title = title, detail = null,
                expanded = open == ChecklistLine.DIPS, onClick = { toggle(ChecklistLine.DIPS) },
                below = ready?.dips?.takeIf { it.isNotEmpty() }?.let { dips ->
                    {
                        // Wraps rather than clipping: a chip cut to "FBTC" has lost its number.
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            dips.take(3).forEach { e ->
                                com.stocktracker.app.ui.components.Pill(
                                    e.symbol + ((e.pctOff52w ?: e.pctOffHigh)?.let { " " + "%.0f%%".format(it) } ?: ""),
                                    LossRed,
                                )
                            }
                        }
                    }
                },
            )
            if (open == ChecklistLine.DIPS) Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { dipCard() }
        }

        // One refresh for everything the card reads, instead of a button on each card.
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Refresh the market readings") { onRefreshAll() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = neutral, modifier = Modifier.size(14.dp))
            Text(" Refresh all", style = MaterialTheme.typography.labelMedium, color = neutral)
        }
    }
}

@Composable
private fun ChecklistRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    detail: String?,
    expanded: Boolean,
    onClick: () -> Unit,
    detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(28.dp).background(tint.copy(alpha = 0.15f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            detail?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = detailColor, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            below?.let { Box(Modifier.padding(top = 4.dp)) { it() } }
        }
        trailing?.invoke()
        Icon(
            if (chevron) Icons.AutoMirrored.Filled.KeyboardArrowRight else if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (chevron) "Open $title" else if (expanded) "Hide $title detail" else "Show $title detail",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
        )
    }
}

/** The trend line's detail: the analyst's note and where the S&P sits, without the card's header. */
@Composable
private fun RegimeDetail(ui: RegimeUi) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val r = ui.result?.regime
    val st = ui.result?.spyTrend
    fun signed(v: Double): String {
        val t = String.format(Locale.US, "%.1f", v)
        val d = t.toDoubleOrNull() ?: v
        return (if (d > 0) "+" else "") + (if (d == 0.0) "0.0" else t) + "%"
    }
    Column(Modifier.padding(start = 50.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ui.error != null && !ui.loading) {
            Text(if (r != null) "Couldn't refresh — showing the last read." else ui.error,
                style = MaterialTheme.typography.labelSmall, color = Signal)
        }
        r?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val bits = listOfNotNull(
            st?.pctVsSma50?.let { "vs 50-day ${signed(it)}" },
            st?.pctVsSma200?.let { "vs 200-day ${signed(it)}" },
            st?.rsi14?.let { "RSI ${String.format(Locale.US, "%.0f", it)}" },
        )
        if (bits.isNotEmpty()) {
            Text("S&P 500 · " + bits.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = neutral)
        }
        if (r != null && r.note.isBlank() && bits.isEmpty()) {
            Text("No additional detail available.", style = MaterialTheme.typography.labelSmall, color = neutral)
        }
    }
}

/** The checks line's detail: each of the checks with its number, without the card's header. */
@Composable
private fun GateDetail(ui: GateUi) {
    val legs = ui.result?.legs.orEmpty()
    Column(Modifier.padding(start = 42.dp, end = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (ui.error != null && !ui.loading) {
            Text(if (ui.result != null) "Couldn't refresh — showing the last read." else ui.error,
                style = MaterialTheme.typography.labelSmall, color = Signal)
        }
        if (legs.isEmpty()) {
            Text("No checks were reported.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            // Two short lines per check: what it is, and its number against the bar. What each one
            // MEANS is a tap away, in the same explainer the Daily Pick card opens.
            legs.forEach { CompactLeg(it) }
            var explain by rememberSaveable { mutableStateOf(false) }
            Text(
                "What these mean ›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { explain = true }.padding(vertical = 6.dp),
            )
            val resp = ui.result
            if (explain && resp != null) MarketChecksDialog(resp, footer = null, onDismiss = { explain = false })
        }
    }
}

@Composable
private fun CompactLeg(leg: com.stocktracker.app.data.remote.GateLeg) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    // Pass green, fail amber, unread a dash — a check that could not be read is not a failure.
    val (mark, color) = when (leg.ok) { true -> "✓" to GainGreen; false -> "✕" to Signal; null -> "–" to neutral }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 2.dp)) {
        Text(mark, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(14.dp))
        Column {
            Text(GateRead.plain(leg.key)?.title ?: GateRead.legLabel(leg), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (leg.ok == false) color else MaterialTheme.colorScheme.onSurface)
            (GateRead.plainValue(leg) ?: GateRead.legValue(leg))?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = neutral, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A 56dp bar filled to [fraction] (clamped), for "how far through" readings. */
@Composable
private fun MiniBar(fraction: Float, color: Color) {
    Box(Modifier.width(56.dp).height(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f), RoundedCornerShape(50))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.03f, 1f)).height(6.dp).background(color, RoundedCornerShape(50)))
    }
}

/** One dot per market check: green passes, amber fails, grey couldn't be read (never red). */
@Composable
private fun LegDots(legs: List<com.stocktracker.app.data.remote.GateLeg>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        legs.forEach { l ->
            val c = when (l.ok) { true -> GainGreen; false -> Signal; null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) }
            Box(Modifier.size(8.dp).background(c, RoundedCornerShape(50)))
        }
    }
}

/**
 * SWT-14 — the "dip radar" strip atop the watchlist, in every state the radar can be in.
 *
 * A cue to add EXTRA on weakness, deliberately NOT a "buy now" signal. Collapsed by default (the list
 * can take a lot of vertical space); tap the header to expand.
 *
 * The strip used to render ONE state — a list of dips — and simply not appear for the other three. A
 * user who never opens the full radar screen therefore could not tell an unreachable scan service, an
 * unconfigured one, or a server holding no scan from a market with nothing on sale. The absence read
 * as the all-clear. So the non-Ready states each get a compact notice here: the source's own words,
 * and a retry where retrying could help.
 *
 * "No dips right now" is emitted by [DipRadar.calm] and reachable from [DipRadarState.Ready] alone.
 */
@Composable
private fun DipStripSection(
    state: DipRadarState,
    stale: String?,
    onOpenAll: () -> Unit,
    onRetry: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }   // collapsed by default
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val amber = Signal
    val note = DipRadar.strip(state)
    val dips = (state as? DipRadarState.Ready)?.dips.orEmpty()
    // `stale` is set when a refresh FAILED and these dips are the previous read. The list is real —
    // it just is not current — so it stays on screen and says so, rather than being replaced by an
    // error that would throw away what we already hold.
    val expandable = dips.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandable) Modifier.clickable { open = !open } else Modifier),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Dip radar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (expandable) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${dips.size} name${if (dips.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = neutral,
                    )
                    Icon(
                        if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (open) "Collapse dip radar" else "Expand dip radar",
                        tint = neutral,
                    )
                }
            } else if (note?.tone == DipStripTone.WORKING) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }

        // A held scan whose refresh failed: the dips below are real but not current. Said here, in
        // the same amber a hard failure uses, so a stale list is never mistaken for a fresh one.
        if (stale != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = amber,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "Couldn't refresh \u2014 showing the last scan.",
                    style = MaterialTheme.typography.labelSmall,
                    color = amber,
                )
            }
        }

        if (note != null) {
            // We hold NO scan in any of these states. Nothing below may mention dips one way or the
            // other — the whole point is that this is not a report about the market.
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (note.tone == DipStripTone.WARN) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = amber,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        note.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (note.tone == DipStripTone.WARN) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (note.tone == DipStripTone.WARN) amber else neutral,
                    )
                    note.detail?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = neutral)
                    }
                }
            }
            if (note.retryable) {
                Text(
                    "Try again",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onRetry() },
                )
            }
            return@Column
        }

        // Ready, and nothing qualified. The ONE calming sentence in this feature, and it is only
        // reachable from here — see DipRadar.calm.
        DipRadar.calm(state)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = neutral)
            return@Column
        }

        if (open) {
            dips.take(6).forEach { DipRow(it) }
            Text(
                if (dips.size > 6) "See all ${dips.size} →" else "See all →",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onOpenAll() },
            )
        }
    }
}

/** One dip row: symbol · tier chip · the dip percent (kept deliberately terse). Tappable (in the
 *  full list) to open the name's detail. */
@Composable
private fun DipRow(d: DipEntry, onClick: (() -> Unit)? = null) {
    val (label, color) = dipMeta(d.tier)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            d.symbol,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(64.dp),
        )
        Box(
            modifier = Modifier
                .background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
        }
        Text(dipPct(d), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

private fun dipMeta(tier: String): Pair<String, Color> = when (tier) {
    "mega_dip" -> "MEGA DIP" to LossRed
    "below_line" -> "BELOW LINE" to CategoricalRamp[1]
    "oversold" -> "OVERSOLD" to EtfAccent
    "pullback_10" -> "DIP" to Signal
    else -> "SMALL DIP" to Signal
}

/** The dip as a plain signed percent off the year's high (negative), e.g. "-29%". */
private fun dipPct(d: DipEntry): String =
    (d.pctOff52w ?: d.pctOffHigh)?.let { "%.0f%%".format(it) } ?: ""

/** One list tab as a soft card: a colour dot (list identity), the name, and its live count. Selected
 *  gets the primary tint. Replaces the flat Material filter-chips with something that scales to many
 *  custom lists and calls out the value-signal "Below 200w" tab in its own colour.
 *
 *  PLAT-4: a raw `clickable` here left the tint as the only sign of which list was active — a
 *  screen-reader user had no way to tell. `selectable` with [Role.Tab] fixes that (TalkBack adds
 *  "selected" to the one that is), chosen over Role.RadioButton because this is a horizontally
 *  scrollable strip that swaps the whole list below it, the same shape as a TabRow, not a vertical
 *  set of options in a form (that's what the widget colour-swatch picker uses RadioButton for). */
@Composable
private fun ListChip(label: String, count: Int?, dotColor: Color?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceVariant
    val fg = if (selected) scheme.primary else scheme.onSurface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        dotColor?.let { Box(Modifier.size(8.dp).background(it, RoundedCornerShape(50))) }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
        )
        Text(
            count?.toString() ?: "–",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = fg.copy(alpha = 0.6f),
        )
    }
}

/** The ghost "＋ New list" tab — a dashed-feel outlined card that sits at the end of the tab row. */
@Composable
private fun NewListChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("＋ New list", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A vertical's heading, with the number of rows under it.
 *
 * The count is carried because a heading without one invites the reader to count, and a section that
 * scrolls past the fold cannot be counted at a glance. It also makes an empty-looking group legible:
 * "Technology 1" is a fact, whereas one lonely row under a heading looks like a rendering fault.
 */
@Composable
private fun SectionHeading(label: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val accent = if (label == WatchlistVerticals.FAVORITES) Signal
                 else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        // The whole heading is the hit target, not just the chevron. A 16dp icon is a poor thing to
        // aim at on a list that now has a dozen of them.
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse $label" else "Expand $label",
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        // The count carries the section when it is CLOSED — collapsed, it is the only thing left
        // saying what is in there, so a heading without one would hide the list's shape rather than
        // tidy it.
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** The view-mode chip that leads the filter row: "Sector" or "Manual". */
@Composable
private fun ModeChip(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Default.Sort,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Text(label, style = MaterialTheme.typography.labelLarge,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Full "Good time to add" list — every current dip, most-severe first, plus (SWT-5) what the scan
 * turned down. Reached by tapping the watchlist strip; fetches the latest scan itself so it stays a
 * lightweight standalone screen.
 *
 * The states are deliberately kept apart. This screen used to collapse a failed fetch into an empty
 * list and print "No dips right now — nothing you track is notably off its highs", which told a user
 * whose scan service was down that the market was calm. "We looked and there are none" is now a
 * conclusion the screen may only draw when it is holding a scan that actually ran; everything else
 * is an error state with a retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DipListScreen(
    onBack: () -> Unit,
    onOpenDetail: (Asset) -> Unit = {},
    onOpenSignalsSettings: () -> Unit = {},
) {
    // The scan comes from the shared market context, not from a fetch of this screen's own. This
    // screen used to hold a `remember` of the state and call latestScan() itself, while the strip on
    // the watchlist did the same in its view model — two fetches of one nightly file, and two
    // answers that could disagree about whether the market is calm. Opening this screen after
    // glancing at the strip now costs nothing and shows the same reading.
    val ctx = ServiceLocator.marketContext
    val market by ctx.state.collectAsStateWithLifecycle()
    val state = market.dipRadar
    var bySym by remember { mutableStateOf(emptyMap<String, Asset>()) }
    // rememberSaveable: the audit section stays open across a retry and a rotation, so a user who
    // opened it to read the reasons isn't sent back to the summary by a refresh.
    var rejectsOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        bySym = ServiceLocator.watchlistStore.watchlist.first().associateBy { it.symbol.uppercase() }
        ctx.refreshScan()
    }
    val open: (String) -> Unit = { sym ->
        onOpenDetail(bySym[sym.uppercase()] ?: Asset(sym, AssetType.STOCK, sym))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dip radar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { ctx.refreshScan(maxAgeMs = 0L) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload the scan")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                is DipRadarState.Loading -> item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Checking the latest scan…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // We hold NO scan. Nothing here may mention dips one way or the other.
                is DipRadarState.Unreachable -> item {
                    DipNotice(
                        title = "Couldn't reach the scan service",
                        body = s.message?.takeIf { it.isNotBlank() }
                            ?: "The request failed, so there's nothing to show — this is not a quiet market.",
                        onRetry = { ctx.refreshScan(maxAgeMs = 0L) },
                    )
                }
                is DipRadarState.NotConfigured -> item {
                    DipNotice(
                        title = "No scan service configured",
                        body = "Set the Signals service URL in Settings and the dip radar starts working.",
                        onRetry = null,
                        onSetUpSignals = onOpenSignalsSettings,
                    )
                }
                // The server answered and told us it has nothing. Its answer, in its words.
                is DipRadarState.NoScan -> item {
                    DipNotice(
                        title = "No scan has run yet",
                        body = s.reason?.takeIf { it.isNotBlank() }
                            ?.replaceFirstChar { c -> c.uppercase() }
                            ?: "The scan service has no results stored, so nothing has been measured yet.",
                        onRetry = { ctx.refreshScan(maxAgeMs = 0L) },
                    )
                }
                is DipRadarState.Ready -> {
                    item { DipSummary(s) }
                    items(s.dips) { d -> DipRow(d, onClick = { open(d.symbol) }) }
                    item {
                        DipRejectSection(
                            state = s,
                            open = rejectsOpen,
                            onToggle = { rejectsOpen = !rejectsOpen },
                            onOpenSymbol = open,
                        )
                    }
                }
            }
        }
    }
}

/** An error/absence panel: what happened, and (when retrying could help) a way to try again — or,
 *  for the not-configured case where no retry ever helps, a way to go set it up instead. */
@Composable
private fun DipNotice(
    title: String,
    body: String,
    onRetry: (() -> Unit)?,
    onSetUpSignals: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = Signal,
                modifier = Modifier.size(18.dp),
            )
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("Try again") }
        }
        if (onSetUpSignals != null) {
            Button(onClick = onSetUpSignals) { Text("Set up signals") }
        }
    }
}

/**
 * The header over a scan that RAN: how many qualified out of how many looked at, and — even when
 * there are dips listed below — how many names could not be measured, because that is what makes the
 * list below incomplete rather than complete-and-short.
 */
@Composable
private fun DipSummary(s: DipRadarState.Ready) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The same sentence the strip can print, from the same function — two copies would be two
        // places for the calming half of this feature to drift.
        DipRadar.calm(s)?.let { Text(it, color = neutral) }
        // The counters are what make the sentence above checkable; each is dropped, never zeroed,
        // when the stored scan didn't report it.
        listOfNotNull(DipRadar.coverage(s.counts), DipRadar.breakdown(s.counts))
            .takeIf { it.isNotEmpty() }
            ?.let {
                Text(
                    it.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = neutral,
                )
            }
        DipRadar.incompleteNote(s.counts)?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Signal,
                    modifier = Modifier.size(15.dp),
                )
                Text(it, style = MaterialTheme.typography.labelMedium, color = Signal)
            }
        }
    }
}

/**
 * The audit affordance (SWT-5): what the radar turned down and why. Deliberately SECONDARY — it is
 * collapsed by default and sits under the qualifying list, because the dips are the content and this
 * is the way to check them. Near-misses come first; they are the only rejects with anything at stake.
 */
@Composable
private fun DipRejectSection(
    state: DipRadarState.Ready,
    open: Boolean,
    onToggle: () -> Unit,
    onOpenSymbol: (String) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val stale = DipRadar.staleNote(state.rejectsAvailable)
    val total = state.nearMiss.size + state.nowhereNear.size + state.unmeasured.size
    if (stale == null && total == 0) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = stale == null) { onToggle() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("What didn't qualify", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (stale == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$total", style = MaterialTheme.typography.labelMedium, color = neutral)
                    Icon(
                        if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (open) "Collapse rejects" else "Expand rejects",
                        tint = neutral,
                    )
                }
            }
        }
        if (stale != null) {
            Text(stale, style = MaterialTheme.typography.bodySmall, color = neutral)
            return@Column
        }
        if (!open) return@Column
        DipRejectGroup("Near misses", state.nearMiss, Signal, onOpenSymbol)
        // The flat middle is long and dull by nature — capped, with the remainder counted so the cap
        // never reads as the whole list.
        DipRejectGroup("Nowhere near a dip", state.nowhereNear, neutral, onOpenSymbol, cap = 12)
        // Last and in its own group on purpose: these were NOT judged to be dip-free, they were never
        // measured. Merging them into the group above is the lie this feature exists to stop.
        DipRejectGroup("Couldn't be measured", state.unmeasured, Signal, onOpenSymbol)
    }
}

@Composable
private fun DipRejectGroup(
    title: String,
    rows: List<DipReject>,
    accent: Color,
    onOpenSymbol: (String) -> Unit,
    cap: Int = Int.MAX_VALUE,
) {
    if (rows.isEmpty()) return
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        "${title.uppercase(Locale.US)} · ${rows.size}",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = accent,
    )
    rows.take(cap).forEach { r ->
        val sym = r.symbol.removeSuffix("-USD")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenSymbol(sym) }
                .padding(vertical = 2.dp),
        ) {
            Text(sym, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            // The server's own sentence, always grounded in this symbol's numbers. Rendered as-is —
            // rewriting it here would just be a second place for the wording to drift.
            Text(r.reason, style = MaterialTheme.typography.labelSmall, color = neutral)
        }
    }
    if (rows.size > cap) {
        Text(
            "+ ${rows.size - cap} more",
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    }
}

/** AIE-5 — the instant "Market now" AI overview, shown in a dialog from the watchlist top bar. */
@Composable
private fun MarketNowDialog(
    ui: MarketNowUi,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSignalsSettings: () -> Unit = {},
) {
    val snap = ui.result?.snapshot
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Market now", style = MaterialTheme.typography.titleLarge)
                if (snap != null && snap.session.isNotBlank()) {
                    val sess = snap.session.lowercase().replaceFirstChar { it.uppercase() }
                    val label = if (snap.asOfEt.isNotBlank()) "$sess · ${snap.asOfEt}" else sess
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    ui.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Reading the tape…")
                    }
                    ui.error != null -> Column {
                        Text(ui.error, color = MaterialTheme.colorScheme.error)
                        if (ui.needsSetup) {
                            TextButton(
                                onClick = onOpenSignalsSettings,
                                modifier = Modifier.padding(top = 4.dp),
                            ) { Text("Set up signals") }
                        }
                    }
                    ui.result != null && snap != null -> {
                        val idx = snap.indices.filter { it.pct != null }
                        if (idx.isNotEmpty() || snap.vix.pct != null) {
                            val header = buildString {
                                idx.take(3).forEach { append("${it.name} ${fmtPct(it.pct)}   ") }
                                snap.vix.pct?.let { append("VIX ${fmtPct(it)}") }
                            }
                            Text(
                                header.trim(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        val ov = ui.result.overviewStruct
                        if (ov != null) {
                            val toneColor = when (ov.tone.lowercase()) {
                                "risk-on" -> GainGreen
                                "risk-off" -> LossRed
                                else -> Signal
                            }
                            Box(
                                modifier = Modifier
                                    .background(toneColor.copy(alpha = 0.16f), RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                            ) {
                                Text(ov.tone.uppercase(), style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold, color = toneColor)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(ov.headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            ov.points.forEach { p ->
                                Row(modifier = Modifier.padding(bottom = 6.dp)) {
                                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                                    Text(p, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        } else {
                            Text(ui.result.overview, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    else -> Text("No overview yet — tap Refresh.")
                }
            }
        },
        confirmButton = { TextButton(onClick = onRefresh, enabled = !ui.loading) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun fmtPct(p: Double?): String =
    if (p == null) "—" else (if (p >= 0) "+" else "") + String.format("%.1f%%", p)
