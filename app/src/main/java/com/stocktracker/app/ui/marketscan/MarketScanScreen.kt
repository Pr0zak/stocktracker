package com.stocktracker.app.ui.marketscan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.MarketScanRow
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.util.Formatting
import com.stocktracker.app.util.NumberInput
import kotlinx.coroutines.launch

/**
 * SWT-1/SWT-4 — the nightly whole-market scan, with each metric read against the night it came from.
 *
 * The screen exists to make one thing true: a number on it is never on it alone. Every metric is
 * shown as "the measurement (its rank in that night's cross-section)", and where there is no rank —
 * the pass has not run, the metric was not measurable, the server does not rank it at all — the
 * measurement is shown BY ITSELF. Nothing here renders a missing rank as 0th, as a dash-th, or as a
 * bar of zero width, all three of which say "worst in the market" about a name nobody measured.
 *
 * It is also, deliberately, not a buy list: the sort is a leaderboard on one measurement, the ranks
 * are positions rather than grades, and both the server's own note and [MarketScanUiState.rankFooter]
 * say so on screen rather than in a comment nobody reads.
 *
 * And it is a place you can LEAVE. The point of a market-wide scan is to surface names you do not
 * already track, so every row opens its detail screen and offers a one-tap add — with a row already
 * on the watchlist saying so instead of offering to add it a second time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScanScreen(onBack: () -> Unit, onOpenDetail: (Asset) -> Unit) {
    val vm: MarketScanViewModel = viewModel()
    val ui by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // The watchlist as symbols, so a row can say "on list" instead of offering a duplicate add.
    // `initial = emptyList()` covers the frame or two before DataStore emits: during it a tracked
    // name briefly offers "Add", which costs at most a redundant tap — WatchlistStore.add
    // de-duplicates on Asset.id and cannot produce a second entry.
    val watchlist by ServiceLocator.watchlistStore.watchlist.collectAsState(initial = emptyList())
    val watched = remember(watchlist) { watchedStockSymbols(watchlist) }

    var showFilters by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Market scan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load(); vm.loadBreadth() }, enabled = !ui.loading) {
                        if (ui.loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, "Reload")
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The sort the SERVER applied, not the one we asked for: it may normalise the name, and
            // the ranking on screen belongs to what it actually did.
            val activeSort = ui.appliedSort ?: ui.sort
            val ascending = scanSortAscending(activeSort)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MARKET_SCAN_SORTS.forEach { s ->
                    FilterChip(
                        // Metric only: the direction lives in its own control beside these chips,
                        // and the applied sort may spell the metric as the route's alias. Comparing
                        // the whole string would unlight the chip the instant the toggle flipped.
                        selected = scanSortMetricMatches(activeSort, s.key),
                        onClick = { vm.setSortMetric(s.key) },
                        label = { Text(s.label) },
                    )
                }
            }

            // Direction, depth, filters. Direction is a control rather than a pair of chips because
            // without it the BOTTOM of every metric is unreachable — no most-oversold name, nothing
            // furthest off its 52-week high, no quietest chart.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = { vm.toggleSortDirection() },
                    label = { Text(if (ascending) "Lowest first" else "Highest first") },
                    leadingIcon = {
                        Icon(
                            if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
                LimitPicker(limit = ui.limit, onPick = vm::setLimit)
                Spacer(Modifier.weight(1f))
                val n = ui.filters.activeCount
                AssistChip(
                    onClick = { showFilters = true },
                    label = { Text(if (n > 0) "Filters ($n)" else "Filters") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
            }

            // ON the screen, not behind the sheet. A filtered list with an invisible filter is
            // indistinguishable from a market in which nothing qualifies, which is precisely the
            // defect this app keeps having to correct.
            if (!ui.filters.isEmpty) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ui.filters.chips().forEach { c ->
                        AssistChip(onClick = { showFilters = true }, label = { Text(c) })
                    }
                    // One action back to the whole night.
                    AssistChip(
                        onClick = { vm.clearFilters() },
                        label = { Text("Clear") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Clear all filters",
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                    )
                }
                // What the filter actually did, in numbers: the slice, the population that matched,
                // and the night it was drawn from. Without it a filter is a black box.
                scanMatchLine(ui.rows.size, ui.totalMatching, ui.scanned)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                ui.provenance(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Said once for the whole list rather than on every row, and said in words that cannot
            // be read as a grade. When the night carries no ranks at all — stored before the ranking
            // pass existed, or never backfilled — the screen says THAT instead, because a sentence
            // explaining percentiles above a list with none is a promise the rows do not keep.
            if (ui.rows.isNotEmpty()) {
                val anyRank = remember(ui.rows) {
                    ui.rows.any { row -> RANKED_SCAN_METRICS.any { it.percentile(row) != null } }
                }
                Text(
                    if (anyRank) {
                        ui.rankFooter
                    } else {
                        "No percentiles for this night yet — the ranking pass has not run for it. " +
                            "Raw measurements only."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.note.isNotBlank()) {
                Text(
                    ui.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Breadth, only when the server says it has a scan behind it. A null line means "no
            // reading", and no reading renders as NOTHING — "0% above the 50-day" is the most
            // bearish market call there is and it is not one this screen gets to make by accident.
            MarketScanProvenance.breadthLine(
                available = ui.breadth?.available,
                pctAboveSma50 = ui.breadth?.pctAboveSma50,
                pctAboveSma200 = ui.breadth?.pctAboveSma200,
                rows = ui.breadth?.n,
                highLowDiff = ui.breadth?.highLowDiff,
            )?.let {
                Text(
                    "Breadth: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Errors sit ABOVE the rows they failed to replace, never instead of them: the rows are
            // still real, they are just older than this attempt. A 422 for a filter the route does
            // not accept arrives here verbatim — its `detail` names the offending parameter, which
            // is far more use than a filter this client silently dropped.
            ui.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (ui.configured == false) {
                Text(
                    "Set the Signals service URL in Settings to load the market scan.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            val sorted = scanMetricFor(activeSort)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(ui.rows, key = { it.symbol }) { row ->
                    ScanRowCard(
                        row = row,
                        headline = sorted,
                        scannedOver = ui.percentilesOver,
                        watched = isWatched(watched, row.symbol),
                        onOpen = { onOpenDetail(scanAsset(row)) },
                        onAdd = { scope.launch { ServiceLocator.watchlistStore.add(scanAsset(row)) } },
                    )
                }
                // "No names matched" is a claim that we looked. It is only made when a load
                // actually landed: with an error on screen, or nothing loaded yet, the failure above
                // is the whole story and a second sentence would contradict it.
                if (ui.rows.isEmpty() && !ui.loading && ui.configured == true && ui.error == null) {
                    item {
                        Text(
                            when {
                                !ui.loaded -> "Loading the night's cross-section…"
                                // Which question came back empty matters: the market did not fail to
                                // produce leaders, THIS filter matched nothing, and the chips above
                                // say which filter.
                                !ui.filters.isEmpty -> "No names matched these filters."
                                else -> "No names matched."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showFilters) {
        ScanFilterSheet(
            initial = ui.filters,
            onApply = { vm.setFilters(it); showFilters = false },
            onClear = { vm.clearFilters(); showFilters = false },
            onDismiss = { showFilters = false },
        )
    }
}

/**
 * A scan row as an [Asset].
 *
 * `AssetType.STOCK` unconditionally, and that is a fact about the data rather than a guess: the scan
 * universe is equities. Display name is the symbol because the scan carries no company name, and
 * inventing one would put a label on the detail screen that came from nowhere.
 */
private fun scanAsset(row: MarketScanRow): Asset =
    Asset(row.symbol, AssetType.STOCK, row.symbol, null)

/** How many rows to ask for, offering only depths the route will actually return. */
@Composable
private fun LimitPicker(limit: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text("Top $limit") },
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MARKET_SCAN_LIMITS.forEach { n ->
                DropdownMenuItem(
                    text = { Text("Top $n") },
                    onClick = { open = false; onPick(n) },
                )
            }
        }
    }
}

/**
 * The filter sheet: the server's whole vocabulary, and nothing else.
 *
 * It edits a DRAFT and applies on a button. Filtering live per keystroke would fire one whole-market
 * request per character typed, and each intermediate bound ("2", "25", "250") is a real query whose
 * answer would flash on screen as though someone had asked for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanFilterSheet(
    initial: MarketScanFilters,
    onApply: (MarketScanFilters) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var bools by remember(initial) { mutableStateOf(initial.bools) }
    val minText = remember(initial) {
        mutableStateMapOf<String, String>().apply {
            SCAN_FILTER_METRICS.forEach { m -> initial.bounds[m.key]?.min?.let { put(m.key, trimNumber(it)) } }
        }
    }
    val maxText = remember(initial) {
        mutableStateMapOf<String, String>().apply {
            SCAN_FILTER_METRICS.forEach { m -> initial.bounds[m.key]?.max?.let { put(m.key, trimNumber(it)) } }
        }
    }

    // A field that is non-blank and unreadable is a MISTAKE, not a cleared filter. Applying it would
    // drop it silently and hand back an unfiltered list under chips that do not mention it.
    val invalid = SCAN_FILTER_METRICS.any { m ->
        NumberInput.isInvalid(minText[m.key].orEmpty()) || NumberInput.isInvalid(maxText[m.key].orEmpty())
    }

    fun draft(): MarketScanFilters {
        var f = MarketScanFilters(bools = bools)
        SCAN_FILTER_METRICS.forEach { m ->
            f = f.withBound(
                m.key,
                NumberInput.parseOrNull(minText[m.key].orEmpty()),
                NumberInput.parseOrNull(maxText[m.key].orEmpty()),
            )
        }
        return f
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Filters", style = MaterialTheme.typography.titleMedium)
            Text(
                "Filters run over the whole night's ~3,100 names, not over the rows on screen. " +
                    "Percentiles stay ranked against the full scan either way.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SCAN_BOOL_FILTERS.forEach { f ->
                    val v = bools[f.key]
                    FilterChip(
                        selected = v != null,
                        // Three states, because the column has three: null means "not measurable"
                        // for a name with too little history, so "above" and "below" do not
                        // partition the market and a two-state control could only ask half the
                        // question. Taps cycle off -> true -> false -> off.
                        onClick = {
                            bools = when (v) {
                                null -> bools + (f.key to true)
                                true -> bools + (f.key to false)
                                else -> bools - f.key
                            }
                        },
                        label = { Text(f.label(v ?: true)) },
                    )
                }
            }

            SCAN_FILTER_METRICS.forEach { m ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        m.label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    BoundField("Min", minText[m.key].orEmpty()) { minText[m.key] = it }
                    BoundField("Max", maxText[m.key].orEmpty()) { maxText[m.key] = it }
                }
            }

            if (invalid) {
                Text(
                    "One of those bounds isn't a number. Fix or clear it — applying it would drop " +
                        "the filter without saying so.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onClear) { Text("Clear all") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onApply(draft()) }, enabled = !invalid) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun BoundField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        isError = NumberInput.isInvalid(value),
        // Decimal rather than Number: several of these metrics are signed percentages and all of
        // them can want a fractional bound. [NumberInput] then handles the comma decimal separator
        // this IME produces across most of Europe.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.width(96.dp),
    )
}

/** "30" not "30.0"; "1.5" stays "1.5". Matches how the bound goes on the wire. */
private fun trimNumber(v: Double): String =
    if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

/**
 * One name: the metric the list is sorted by, expanding to every metric the scan holds for it.
 *
 * TWO tap targets, and they must not swallow each other. The row BODY opens the name's detail
 * screen — the whole point of a market-wide scan is names you do not already track, and a screen
 * with no way out of it is a leaderboard you can only read. The chevron on the right expands the
 * rest of the metrics in place. Each has its own hit area and its own icon: a right-pointing
 * chevron after the price for "this goes somewhere", an up/down chevron in its own button for
 * "this opens here".
 *
 * [headline] is null when the list is sorted by something we have no renderer for — the row then
 * shows its symbol and price and nothing else, rather than labelling some other metric's number
 * with the sort's name.
 */
@Composable
private fun ScanRowCard(
    row: MarketScanRow,
    headline: ScanMetric?,
    scannedOver: Int?,
    watched: Boolean,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
) {
    var expanded by rememberSaveable(row.symbol) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        row.price?.let { Formatting.price(it) } ?: MetricRank.NA,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open ${row.symbol}",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (headline != null) {
                    MetricLine(metric = headline, row = row, scannedOver = scannedOver)
                }
            }
            // Already tracked names say so and offer nothing: a second "Add" on a name that is
            // already on the list is a control whose only possible outcome is nothing happening.
            if (watched) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "On list",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                IconButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = "Add ${row.symbol} to watchlist")
                }
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) {
                        "Hide ${row.symbol}'s other metrics"
                    } else {
                        "Show ${row.symbol}'s other metrics"
                    },
                )
            }
        }
        if (expanded) {
            RANKED_SCAN_METRICS.filter { it.key != headline?.key }.forEach { m ->
                MetricLine(metric = m, row = row, scannedOver = scannedOver)
            }
            // A row's own account of what it could not measure. Null (the producer never said) and
            // empty (it checked and everything was measurable) are different, so only a non-empty
            // list is rendered — an absent list must not read as a clean bill of health.
            row.unmeasured?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    "Not measured on this name: ${it.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Relative volume · 1.40× (96th percentile of 3,101 scanned)" plus a bar, when there is a rank. */
@Composable
private fun MetricLine(metric: ScanMetric, row: MarketScanRow, scannedOver: Int?) {
    val pctile = metric.percentile(row)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            metric.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        // The raw number and its rank, from the one helper that knows the null rules. When the rank
        // is absent this is just the number — no "0th", no placeholder ordinal.
        Text(
            metric.line(row, scannedOver),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        PercentileBar(MetricRank.fraction(pctile))
    }
}

/**
 * A rank as a bar, or NOTHING.
 *
 * The empty case is a fixed-width blank rather than a zero-width bar or an empty track: an unfilled
 * track next to a number reads as "measured, and at the bottom", which is the same false claim as
 * "0th percentile" made in pixels. The space is held so the rows above and below still line up.
 */
@Composable
private fun PercentileBar(fraction: Float?) {
    val w = 44.dp
    if (fraction == null) {
        Spacer(Modifier.width(w))
        return
    }
    Box(
        modifier = Modifier
            .width(w)
            .height(6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
    }
}
