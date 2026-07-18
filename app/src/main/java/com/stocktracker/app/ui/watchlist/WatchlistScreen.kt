package com.stocktracker.app.ui.watchlist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.VixQuote
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.AssetRow
import com.stocktracker.app.ui.components.FearGauge
import com.stocktracker.app.ui.components.SessionTimelineBar
import com.stocktracker.app.ui.components.SwipeToDeleteRow
import com.stocktracker.app.util.Formatting
import com.stocktracker.app.util.MarketClock
import kotlinx.coroutines.delay
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
) {
    val vm: WatchlistViewModel = viewModel()
    val state by vm.state.collectAsState()
    val hideZeroCents by ServiceLocator.settingsStore.hideZeroCents.collectAsState(initial = false)
    val showMarketStatus by ServiceLocator.settingsStore.showMarketStatus.collectAsState(initial = true)
    val showVix by ServiceLocator.settingsStore.showVix.collectAsState(initial = true)
    val groups by ServiceLocator.settingsStore.watchlistGroups.collectAsState(initial = emptyList())
    val marketState by produceState(initialValue = MarketClock.now()) {
        while (true) {
            value = MarketClock.now()
            delay(60_000)
        }
    }
    val vix by produceState<VixQuote?>(initialValue = null) {
        while (true) {
            runCatching { ServiceLocator.repository.vix() }.getOrNull()?.let { value = it }
            delay(120_000)
        }
    }
    var selected by remember { mutableStateOf(TAB_ALL) }
    // A deleted/emptied list shouldn't leave us stranded on a missing tab.
    LaunchedEffect(groups) {
        if (selected !in listOf(TAB_ALL, TAB_STOCKS, TAB_CRYPTO, TAB_BELOW) && selected !in groups) selected = TAB_ALL
    }
    var showNewListDialog by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    var confirmDeleteGroup by remember { mutableStateOf<String?>(null) }

    // Reorder is only meaningful in the unfiltered "All" view, where display order == stored order.
    val reorderEnabled = selected == TAB_ALL
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromId = from.key as? String
        val toId = to.key as? String
        if (fromId != null && toId != null) vm.moveLocal(fromId, toId)
    }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging) vm.persistOrder()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StockTracker") },
                actions = {
                    IconButton(onClick = onOpenCalendar) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = "Catalyst calendar")
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
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
                item(key = "hdr:tabs") {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val belowTab = if (state.items.any { it.below200wma == true }) listOf(TAB_BELOW) else emptyList()
                        (listOf(TAB_ALL, TAB_STOCKS, TAB_CRYPTO) + belowTab + groups).forEach { tab ->
                            FilterChip(
                                selected = selected == tab,
                                onClick = { selected = tab },
                                label = { Text(tab) },
                            )
                        }
                        FilterChip(
                            selected = false,
                            onClick = { showNewListDialog = true },
                            label = { Text("＋ List") },
                        )
                    }
                }

                if (showMarketStatus) {
                    item(key = "hdr:timeline") { SessionTimelineBar(marketState) }
                }

                if (showVix) {
                    vix?.let { v -> item(key = "hdr:vix") { FearGauge(v, onClick = onOpenVix) } }
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

                items(filtered, key = { it.asset.id }) { item ->
                    ReorderableItem(reorderState, key = item.asset.id) { _ ->
                        val handleMod = if (reorderEnabled) Modifier.longPressDraggableHandle() else Modifier
                        SwipeToDeleteRow(
                            onDelete = { vm.remove(item.asset) },
                        ) {
                            val q = item.quote
                            val up = q?.isUp ?: true
                            val shares = item.asset.shares
                            val holdingsText = if (shares != null && shares > 0.0 && q != null) {
                                "${Formatting.shares(shares)} sh · ${Formatting.price(shares * q.price, q.currency, hideZeroCents)}"
                            } else {
                                null
                            }
                            Box(handleMod) {
                                AssetRow(
                                    symbol = item.asset.symbol,
                                    name = item.asset.displayName,
                                    priceText = q?.let { Formatting.price(it.price, it.currency, hideZeroCents) } ?: "—",
                                    changeText = q?.let { Formatting.changeLine(it.change, it.changePercent, it.isUp, hideZeroCents) } ?: "…",
                                    up = up,
                                    sparkline = item.sparkline,
                                    holdingsText = holdingsText,
                                    isCrypto = item.asset.type == AssetType.CRYPTO,
                                    belowLine = item.below200wma == true,
                                    onClick = { onOpenDetail(item.asset) },
                                )
                            }
                        }
                    }
                }
            }

            if (state.loading && state.items.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
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
