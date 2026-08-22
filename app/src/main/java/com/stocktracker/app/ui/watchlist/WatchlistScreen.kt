package com.stocktracker.app.ui.watchlist

import java.util.Locale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.util.Formatting
import com.stocktracker.app.util.Freshness
import com.stocktracker.app.util.listFreshness
import com.stocktracker.app.util.staleRowCount
import com.stocktracker.app.util.MarketClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// Built-in tabs; user-defined watchlist names extend the row after these.
private const val TAB_ALL = "All"
private const val TAB_STOCKS = "Stocks"
private const val TAB_CRYPTO = "Crypto"
private const val TAB_BELOW = "Below 200w" // computed tab, shown only when a name is below its 200-week line

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
    val vix by produceState<VixQuote?>(initialValue = null) {
        while (true) {
            runCatching { ServiceLocator.repository.vix() }.getOrNull()?.let { value = it }
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
    var contextOpen by rememberSaveable { mutableStateOf(false) }
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
                title = { Text("StockTracker") },
                actions = {
                    IconButton(onClick = { vm.openMarketNow() }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Market now — AI overview")
                    }
                    IconButton(onClick = onOpenHeatmap) {
                        Icon(Icons.Filled.GridView, contentDescription = "Heat map")
                    }
                    IconButton(onClick = onOpenMarketScan) {
                        Icon(Icons.Filled.Leaderboard, contentDescription = "Market scan")
                    }
                    IconButton(onClick = onOpenCalendar) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = "Catalyst calendar")
                    }
                    IconButton(onClick = { vm.refresh() }, enabled = !state.refreshing) {
                        if (state.refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh prices")
                        }
                    }
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
                            val count = when (tab) {
                                TAB_ALL -> state.items.size
                                TAB_STOCKS -> state.items.count { it.asset.type == AssetType.STOCK }
                                TAB_CRYPTO -> state.items.count { it.asset.type == AssetType.CRYPTO }
                                TAB_BELOW -> state.items.count { it.below200wma == true }
                                else -> state.items.count { it.asset.groups.contains(tab) }
                            }
                            val dot = when (tab) {
                                TAB_ALL -> null
                                TAB_STOCKS -> faint
                                TAB_CRYPTO -> Color(0xFFF7A928)
                                TAB_BELOW -> Color(0xFF4666CF)
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

                // When these prices were last read. Above the context strip because it qualifies
                // every number below it, including the ones inside the collapsed cards.
                if (state.items.isNotEmpty()) {
                    item(key = "hdr:freshness") {
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
                            onToggle = { contextOpen = !contextOpen },
                            marketState = marketState,
                            regime = reg,
                            vix = vix,
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
                        item(key = "hdr:dips") {
                            DipStripSection(
                                stale = state.dipStale,
                                state = state.dipRadar,
                                onOpenAll = onOpenDips,
                                onRetry = { vm.reloadDips() },
                            )
                        }
                        if (showMarketStatus) {
                            item(key = "hdr:timeline") { SessionTimelineBar(marketState) }
                        }
                        if (hasRegime) {
                            item(key = "hdr:regime") { RegimeCard(reg, onRefresh = { vm.loadRegime(force = true) }) }
                        }
                        // Directly under the regime banner: same question, checkable half. The banner
                        // narrates the backdrop; this one shows the five conditions and their numbers.
                        if (hasGate) {
                            item(key = "hdr:gate") { GateCard(state.gate, onRefresh = { vm.loadGate(force = true) }) }
                        }
                        if (showVix) {
                            vix?.let { v -> item(key = "hdr:vix") { FearGauge(v, onClick = onOpenVix) } }
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
                        val holdingsText = if (shares != null && shares > 0.0 && q != null) {
                            "${Formatting.shares(shares)} sh · ${Formatting.price(shares * q.price, q.currency, hideZeroCents)}"
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
                                changeText = q?.let { Formatting.changeLine(it.change, it.changePercent, it.isUp, hideZeroCents) } ?: "…",
                                up = up,
                                sparkline = item.sparkline,
                                // The level changeText is measured from, so the line and the
                                // number can be read against the same baseline.
                                previousClose = q?.prevClose,
                                holdingsText = holdingsText,
                                isCrypto = item.asset.type == AssetType.CRYPTO,
                                isEtf = item.quote?.isEtf == true,
                                belowLine = item.below200wma == true,
                                onClick = { onOpenDetail(item.asset) },
                                showDragHandle = draggable,
                                favorite = item.asset.favorite,
                                onToggleFavorite = { vm.toggleFavorite(item.asset) },
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = !refreshing) { onRefresh() }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (refreshing) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        } else {
            Icon(
                imageVector = if (warn) Icons.Filled.Warning else Icons.Default.Refresh,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
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
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        if (!refreshing) {
            Text(
                text = "Tap to refresh",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MarketContext(
    expanded: Boolean,
    onToggle: () -> Unit,
    marketState: com.stocktracker.app.util.MarketState,
    regime: RegimeUi,
    vix: VixQuote?,
    /** The dip fact in two words, or null when there is nothing honest to compress. */
    dipChip: String?,
    /** SWT-13 — the gate verdict, already resolved. Null = no reading, and no claim. */
    gate: GateSummary?,
    showMarketStatus: Boolean,
    showVix: Boolean,
    hasRegime: Boolean,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val bits = buildList<Pair<String, Color>> {
        if (showMarketStatus) add(marketState.label to neutral)
        if (hasRegime) {
            regime.result?.regime?.label?.takeIf { it.isNotBlank() }?.let { lbl ->
                val trend = regime.result?.regime?.trend
                add(lbl to if (trend == "up") GainGreen else if (trend == "down") LossRed else neutral)
            }
        }
        // Unmeasured gets the amber, never the red: a gate that couldn't read a leg has not
        // observed a bearish market, and one glance at this line is all most readings get.
        gate?.let { g ->
            add(g.chip to when (g.verdict) {
                GateVerdict.OPEN -> GainGreen
                GateVerdict.SHUT -> LossRed
                GateVerdict.UNMEASURED -> Color(0xFFB0872B)
                GateVerdict.UNAVAILABLE -> neutral
            })
        }
        if (showVix) vix?.let { add("VIX ${String.format(Locale.US, "%.1f", it.value)}" to neutral) }
        dipChip?.let { add(it to neutral) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bits.forEachIndexed { i, (text, tint) ->
                if (i > 0) Text("·", style = MaterialTheme.typography.labelSmall, color = neutral)
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (tint == neutral) FontWeight.Normal else FontWeight.SemiBold,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Hide market context" else "Show market context",
            tint = neutral,
        )
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
    val amber = Color(0xFFD29922)
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
    "mega_dip" -> "MEGA DIP" to Color(0xFFB0543D)
    "below_line" -> "BELOW LINE" to Color(0xFF4666CF)
    "oversold" -> "OVERSOLD" to Color(0xFF0F8A7E)
    "pullback_10" -> "DIP" to Color(0xFFD29922)
    else -> "SMALL DIP" to Color(0xFFD29922)
}

/** The dip as a plain signed percent off the year's high (negative), e.g. "-29%". */
private fun dipPct(d: DipEntry): String =
    (d.pctOff52w ?: d.pctOffHigh)?.let { "%.0f%%".format(it) } ?: ""

/** One list tab as a soft card: a colour dot (list identity), the name, and its live count. Selected
 *  gets the primary tint. Replaces the flat Material filter-chips with something that scales to many
 *  custom lists and calls out the value-signal "Below 200w" tab in its own colour. */
@Composable
private fun ListChip(label: String, count: Int, dotColor: Color?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceVariant
    val fg = if (selected) scheme.primary else scheme.onSurface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() }
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
            count.toString(),
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
    val accent = if (label == WatchlistVerticals.FAVORITES) Color(0xFFD29922)
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
fun DipListScreen(onBack: () -> Unit, onOpenDetail: (Asset) -> Unit = {}) {
    var reload by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<DipRadarState>(DipRadarState.Loading) }
    var bySym by remember { mutableStateOf(emptyMap<String, Asset>()) }
    // rememberSaveable: the audit section stays open across a retry and a rotation, so a user who
    // opened it to read the reasons isn't sent back to the summary by a refresh.
    var rejectsOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reload) {
        state = DipRadarState.Loading
        val base = ServiceLocator.settingsStore.signalsApiUrl.first()
        bySym = ServiceLocator.watchlistStore.watchlist.first().associateBy { it.symbol.uppercase() }
        val res = runCatching { SignalsApiService().latestScan(base) }
        state = DipRadar.state(res.getOrNull(), res.exceptionOrNull(), configured = base.isNotBlank())
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
                    IconButton(onClick = { reload++ }) {
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
                        onRetry = { reload++ },
                    )
                }
                is DipRadarState.NotConfigured -> item {
                    DipNotice(
                        title = "No scan service configured",
                        body = "Set the Signals service URL in Settings and the dip radar starts working.",
                        onRetry = null,
                    )
                }
                // The server answered and told us it has nothing. Its answer, in its words.
                is DipRadarState.NoScan -> item {
                    DipNotice(
                        title = "No scan has run yet",
                        body = s.reason?.takeIf { it.isNotBlank() }
                            ?.replaceFirstChar { c -> c.uppercase() }
                            ?: "The scan service has no results stored, so nothing has been measured yet.",
                        onRetry = { reload++ },
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

/** An error/absence panel: what happened, and (when retrying could help) a way to try again. */
@Composable
private fun DipNotice(title: String, body: String, onRetry: (() -> Unit)?) {
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
                tint = Color(0xFFD29922),
                modifier = Modifier.size(18.dp),
            )
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("Try again") }
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
                    tint = Color(0xFFD29922),
                    modifier = Modifier.size(15.dp),
                )
                Text(it, style = MaterialTheme.typography.labelMedium, color = Color(0xFFD29922))
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
        DipRejectGroup("Near misses", state.nearMiss, Color(0xFFD29922), onOpenSymbol)
        // The flat middle is long and dull by nature — capped, with the remainder counted so the cap
        // never reads as the whole list.
        DipRejectGroup("Nowhere near a dip", state.nowhereNear, neutral, onOpenSymbol, cap = 12)
        // Last and in its own group on purpose: these were NOT judged to be dip-free, they were never
        // measured. Merging them into the group above is the lie this feature exists to stop.
        DipRejectGroup("Couldn't be measured", state.unmeasured, Color(0xFFC64040), onOpenSymbol)
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

/**
 * Theme D — the market-regime banner. COLLAPSED by default to a one-line summary (trend-colored label +
 * volatility + the S&P's position vs its 200-day); tap to expand the positioning note and the S&P's
 * 50/200-day + RSI stats. Auto-loaded, cached ~30 min server-side. Refresh (top-right) is always
 * reachable — including on a failed load — and errors are surfaced rather than swallowed.
 */
@Composable
private fun RegimeCard(ui: RegimeUi, onRefresh: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val green = Color(0xFF2E9E57)
    val red = Color(0xFFC64040)
    val amber = Color(0xFFB0872B)
    val r = ui.result?.regime
    val st = ui.result?.spyTrend
    val hasContent = r != null && r.label.isNotBlank()
    val trendColor = when (r?.trend?.lowercase()) {
        "up" -> green
        "down" -> red
        else -> amber
    }
    // rememberSaveable so the expanded view survives the LazyColumn recycling the header item on scroll
    // and survives a configuration change / process death.
    var open by rememberSaveable { mutableStateOf(false) }
    // Locale-safe + negative-zero-safe signed percent ("+1.2%", "-0.8%", "0.0%" — never "+-0.0%").
    fun signed(v: Double): String {
        val s = String.format(Locale.US, "%.1f", v)
        val d = s.toDoubleOrNull() ?: v
        return (if (d > 0) "+" else "") + (if (d == 0.0) "0.0" else s) + "%"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .then(if (hasContent) Modifier.clickable { open = !open } else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header: the label chip IS the collapsed summary; the note lives behind the chevron. Refresh
        // stays in the header so it's reachable whether collapsed, expanded, or errored.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasContent) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .background(trendColor.copy(alpha = 0.16f), RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(r!!.label, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold, color = trendColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Collapsed summary stays to a clean "label · vol" — the S&P's 200-day position is
                    // shown in the expanded stats line, so it doesn't crowd the header here.
                    r.volatility.takeIf { it.isNotBlank() }?.let {
                        Text("· vol ${it.lowercase()}", style = MaterialTheme.typography.labelMedium,
                            color = neutral, maxLines = 1)
                    }
                }
            } else {
                Text("Market regime", style = MaterialTheme.typography.labelLarge, color = neutral,
                    modifier = Modifier.weight(1f))
            }
            if (ui.loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh regime",
                            tint = if (ui.error != null) red else neutral, modifier = Modifier.size(18.dp))
                    }
                    if (hasContent) {
                        Icon(
                            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (open) "Collapse market regime" else "Expand market regime",
                            tint = neutral,
                        )
                    }
                }
            }
        }

        // Surface a load/refresh failure (visible collapsed or expanded); the header ↻ retries.
        if (ui.error != null && !ui.loading) {
            Text(
                if (hasContent) "Couldn't refresh — showing the last read." else ui.error,
                style = MaterialTheme.typography.labelSmall, color = red,
            )
        }

        if (hasContent && open) {
            if (r!!.note.isNotBlank()) {
                Text(r.note, style = MaterialTheme.typography.bodySmall)
            }
            val bits = listOfNotNull(
                st?.pctVsSma50?.let { "vs 50-day ${signed(it)}" },
                st?.pctVsSma200?.let { "vs 200-day ${signed(it)}" },
                st?.rsi14?.let { "RSI ${String.format(Locale.US, "%.0f", it)}" },
            )
            if (bits.isNotEmpty()) {
                Text("S&P 500 · " + bits.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium, color = neutral)
            }
            if (r.note.isBlank() && bits.isEmpty()) {
                Text("No additional detail available.", style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        } else if (!hasContent && ui.loading) {
            Text("Reading the tape…", style = MaterialTheme.typography.bodySmall, color = neutral)
        }
    }
}

/**
 * SWT-13 — the five-leg market gate, beside the regime banner.
 *
 * The banner next to this one is the NARRATIVE read: an analyst's sentence about the backdrop. This
 * is the checkable half of the same question — five stated conditions, the number behind each, and
 * the threshold it had to clear — so the verdict can be argued with instead of believed.
 *
 * FOUR outcomes, and the third is the reason this card is written carefully:
 *   - open   → green
 *   - shut   → red, and the failing legs are NAMED, because "shut" without "which" is a mood
 *   - could not be measured (`passed == null`) → AMBER, never the shut red. A leg that couldn't be
 *     read is not a bearish market; colouring it red asserts one out of a failed fetch.
 *   - nothing measurable at all (`available == false`) → grey, and it says so plainly.
 *
 * A failed REFRESH keeps the last reading and prints the error beside it (the [GateUi] shape), so a
 * blip never blanks a card that is still holding a real answer. Refresh is reachable in every state.
 */
@Composable
private fun GateCard(ui: GateUi, onRefresh: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val green = Color(0xFF2E9E57)
    val red = Color(0xFFC64040)
    val amber = Color(0xFFB0872B)
    val summary = GateRead.summary(ui.result)
    val verdictColor = when (summary?.verdict) {
        GateVerdict.OPEN -> green
        GateVerdict.SHUT -> red
        // Unmeasured and unavailable are ABSENCES of a verdict. They must never share the shut
        // colour, or a failed fetch reads as a market call.
        GateVerdict.UNMEASURED -> amber
        GateVerdict.UNAVAILABLE -> neutral
        null -> neutral
    }
    val score = GateRead.scoreText(ui.result)
    // rememberSaveable so the legs stay open across the LazyColumn recycling this item on scroll.
    var open by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .then(if (summary != null) Modifier.clickable { open = !open } else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (summary != null) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .background(verdictColor.copy(alpha = 0.16f), RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            summary.headline, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold, color = verdictColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // The score is null whenever a leg went unmeasured — the server refuses to
                    // average over a hole, so this prints a dash rather than a confident middle.
                    Text(
                        "Score ${score ?: "—"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = neutral,
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    "Market gate", style = MaterialTheme.typography.labelLarge, color = neutral,
                    modifier = Modifier.weight(1f),
                )
            }
            if (ui.loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Refresh, contentDescription = "Refresh the market gate",
                            tint = if (ui.error != null) red else neutral, modifier = Modifier.size(18.dp),
                        )
                    }
                    if (summary != null) {
                        Icon(
                            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (open) "Collapse the market gate" else "Expand the market gate",
                            tint = neutral,
                        )
                    }
                }
            }
        }

        // Which legs, and in which direction. Shown collapsed too: "shut" that doesn't say what shut
        // it is a colour, not a reason, and the unmeasured case NEEDS its sentence to not read as a fail.
        summary?.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (summary.verdict == GateVerdict.OPEN) neutral else verdictColor,
            )
        }

        if (ui.error != null && !ui.loading) {
            Text(
                if (summary != null) "Couldn't refresh — showing the last read." else ui.error,
                style = MaterialTheme.typography.labelSmall, color = red,
            )
        }

        if (summary != null && open) {
            val legs = ui.result?.legs.orEmpty()
            // The gate's own sentence, verbatim — it is the one that says WHY.
            ui.result?.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (legs.isEmpty()) {
                Text(
                    "This reading came with no legs, so there's nothing to check it against.",
                    style = MaterialTheme.typography.labelSmall, color = neutral,
                )
            } else {
                legs.forEach { GateLegRow(it) }
            }
            GateRead.cachedNote(ui.result)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        } else if (summary == null && ui.loading) {
            Text("Checking the gate…", style = MaterialTheme.typography.bodySmall, color = neutral)
        }
    }
}

/**
 * One leg: its mark, its name, the server's sentence, and the number vs the threshold.
 *
 * `ok == null` renders as a DASH. A cross would say we checked SPY and it is under its 50-EMA — a
 * trend claim nobody made — when the truth is the leg could not be read at all.
 */
@Composable
private fun GateLegRow(leg: GateLeg) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val (glyph, tint) = when (GateRead.mark(leg.ok)) {
        LegMark.PASS -> "✓" to Color(0xFF2E9E57)
        LegMark.FAIL -> "✗" to Color(0xFFC64040)
        LegMark.UNKNOWN -> "—" to neutral
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = tint,
            modifier = Modifier.width(14.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(GateRead.legLabel(leg), style = MaterialTheme.typography.bodySmall)
            leg.note.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
        // Dropped entirely when neither number arrived — never a "0 vs 0".
        GateRead.legValue(leg)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = neutral)
        }
    }
}

/** AIE-5 — the instant "Market now" AI overview, shown in a dialog from the watchlist top bar. */
@Composable
private fun MarketNowDialog(
    ui: MarketNowUi,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
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
                    ui.error != null -> Text(ui.error, color = MaterialTheme.colorScheme.error)
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
                                "risk-on" -> Color(0xFF2E9E57)
                                "risk-off" -> Color(0xFFD1453B)
                                else -> Color(0xFFB0872B)
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
