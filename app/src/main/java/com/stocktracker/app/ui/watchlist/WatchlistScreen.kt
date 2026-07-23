package com.stocktracker.app.ui.watchlist

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.VixQuote
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.first
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
    onOpenDips: () -> Unit = {},
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
                    IconButton(onClick = { vm.openMarketNow() }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Market now — AI overview")
                    }
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
                    val belowTab = if (state.items.any { it.below200wma == true }) listOf(TAB_BELOW) else emptyList()
                    val tabs = listOf(TAB_ALL, TAB_STOCKS, TAB_CRYPTO) + belowTab + groups
                    val faint = MaterialTheme.colorScheme.onSurfaceVariant
                    val primary = MaterialTheme.colorScheme.primary
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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

                if (state.dips.isNotEmpty()) {
                    item(key = "hdr:dips") { GoodTimeToAddSection(state.dips, onOpenDips) }
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
                                    isEtf = item.quote?.isEtf == true,
                                    belowLine = item.below200wma == true,
                                    onClick = { onOpenDetail(item.asset) },
                                    showDragHandle = reorderEnabled,
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
 * "Good time to add" strip atop the watchlist — the dips from the latest scan, most-severe first, in
 * plain language. A cue to add EXTRA on weakness, deliberately NOT a "buy now" signal.
 */
@Composable
private fun GoodTimeToAddSection(dips: List<DipEntry>, onOpenAll: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable { onOpenAll() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Good time to add", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        dips.take(6).forEach { DipRow(it) }
        Text(
            if (dips.size > 6) "See all ${dips.size} →" else "See all →",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
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

/** Full "Good time to add" list — every current dip, most-severe first. Reached by tapping the
 *  watchlist strip. Fetches the latest scan itself so it stays a lightweight standalone screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DipListScreen(onBack: () -> Unit, onOpenDetail: (Asset) -> Unit = {}) {
    val data by produceState(emptyList<DipEntry>() to emptyMap<String, Asset>()) {
        val base = ServiceLocator.settingsStore.signalsApiUrl.first()
        val scan = runCatching { SignalsApiService().latestScan(base) }.getOrNull()
        val bySym = ServiceLocator.watchlistStore.watchlist.first().associateBy { it.symbol.uppercase() }
        val order = listOf("mega_dip", "below_line", "oversold", "pullback_10", "pullback_5")
        val dips = scan?.results?.mapNotNull { r ->
            r.dip?.let { DipEntry(r.symbol.removeSuffix("-USD"), it, r.pctOffRecentHigh, r.pctOff52wHigh) }
        }?.sortedBy { order.indexOf(it.tier).let { i -> if (i < 0) 99 else i } } ?: emptyList()
        value = dips to bySym
    }
    val (dips, bySym) = data
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Good time to add") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            if (dips.isEmpty()) {
                item {
                    Text(
                        "No dips right now — nothing you track is notably off its highs.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(dips) { d ->
                DipRow(d, onClick = {
                    onOpenDetail(bySym[d.symbol.uppercase()] ?: Asset(d.symbol, AssetType.STOCK, d.symbol))
                })
            }
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
                        Text(ui.result.overview, style = MaterialTheme.typography.bodyMedium)
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
