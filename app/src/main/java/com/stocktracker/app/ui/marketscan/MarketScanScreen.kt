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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.remote.MarketScanRow
import com.stocktracker.app.util.Formatting

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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScanScreen(onBack: () -> Unit) {
    val vm: MarketScanViewModel = viewModel()
    val ui by vm.state.collectAsStateWithLifecycle()

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
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MARKET_SCAN_SORTS.forEach { s ->
                    FilterChip(
                        selected = activeSort == s.key,
                        onClick = { vm.setSort(s.key) },
                        label = { Text(s.label) },
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
            )?.let {
                Text(
                    "Breadth: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Errors sit ABOVE the rows they failed to replace, never instead of them: the rows are
            // still real, they are just older than this attempt.
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
                    ScanRowCard(row = row, headline = sorted, scannedOver = ui.percentilesOver)
                }
                // "No names matched" is a claim that we looked. It is only made when a load
                // actually landed: with an error on screen, or nothing loaded yet, the failure above
                // is the whole story and a second sentence would contradict it.
                if (ui.rows.isEmpty() && !ui.loading && ui.configured == true && ui.error == null) {
                    item {
                        Text(
                            if (ui.loaded) "No names matched." else "Loading the night's cross-section…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One name: the metric the list is sorted by, expanding to every metric the scan holds for it.
 *
 * [headline] is null when the list is sorted by something we have no renderer for — the row then
 * shows its symbol and price and nothing else, rather than labelling some other metric's number
 * with the sort's name.
 */
@Composable
private fun ScanRowCard(row: MarketScanRow, headline: ScanMetric?, scannedOver: Int?) {
    var expanded by rememberSaveable(row.symbol) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
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
        }
        if (headline != null) {
            MetricLine(metric = headline, row = row, scannedOver = scannedOver)
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
