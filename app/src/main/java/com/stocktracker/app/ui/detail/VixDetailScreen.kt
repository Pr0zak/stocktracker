package com.stocktracker.app.ui.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.model.VixQuote
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.FearGauge
import com.stocktracker.app.ui.components.PriceChart
import com.stocktracker.app.util.formatChartTimestamp
import java.util.Locale

private val VIX_RANGES = listOf(
    ChartRange.WEEK, ChartRange.MONTH, ChartRange.QUARTER, ChartRange.YEAR, ChartRange.THREE_YEAR,
)

/**
 * A fetched window, tagged with the range it actually IS.
 *
 * The screen used to hold the points alone. On a range change the new fetch started and the old
 * points stayed on screen underneath the newly-selected chip — the spinner only appeared when
 * there was nothing to hold over — so tapping "1Y" showed you three months of VIX labelled 1Y,
 * until the network came back. Same defect the crypto chart had, on a different screen: a window
 * has to be as wide as its label says.
 *
 * [failed] is separate from an empty list on purpose. "We asked and there is nothing here" and
 * "we could not ask" are different sentences, and only one of them is worth a retry button.
 */
private data class VixWindow(
    val range: ChartRange? = null,
    val points: List<PricePoint> = emptyList(),
    val failed: Boolean = false,
)

/** Tapping the dashboard fear gauge lands here: current VIX + its history chart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VixDetailScreen(onBack: () -> Unit) {
    val repo = ServiceLocator.repository
    val vixAsset = remember { Asset(symbol = "^VIX", type = AssetType.STOCK, displayName = "Volatility Index") }

    var range by remember { mutableStateOf(ChartRange.QUARTER) }
    var attempt by remember { mutableIntStateOf(0) }

    val vix by produceState<Result<VixQuote?>?>(initialValue = null, attempt) {
        value = null
        value = runCatching { repo.vix() }
    }
    // Re-fetch history whenever the range changes — and clear the old window first, so the chart on
    // screen is never a different span from the chip that is lit.
    val window by produceState(initialValue = VixWindow(), range, attempt) {
        value = VixWindow()
        val fetched = runCatching { repo.history(vixAsset, range) }
        value = VixWindow(range, fetched.getOrDefault(emptyList()), fetched.isFailure)
    }
    val chart = window.points
    val loading = window.range != range

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VIX · Volatility Index") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // An absent gauge used to be indistinguishable from a calm one: the fetch failed, the
            // composable rendered nothing, and the screen looked like it had simply decided not to
            // mention fear today.
            val vixQuote = vix?.getOrNull()
            when {
                vixQuote != null -> FearGauge(vixQuote, modifier = Modifier.padding(top = 8.dp))
                // Both branches of "no gauge" land here: the call threw, or it came back with
                // nothing. Neither is a reading, and neither should look like one.
                vix != null -> Text(
                    "Couldn't load the current VIX. The history below is unaffected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            val up = chart.size >= 2 && chart.last().price >= chart.first().price
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loading -> CircularProgressIndicator()
                    window.failed -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Couldn't load VIX history for ${range.label}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { attempt++ }) { Text("Retry") }
                    }
                    chart.size >= 2 -> PriceChart(
                        points = chart,
                        up = up,
                        modifier = Modifier.fillMaxSize(),
                        showHighLow = true,
                        valueFormatter = { String.format(Locale.US, "%.2f", it) },
                        timeFormatter = { formatChartTimestamp(it, range) },
                    )
                    else -> Text(
                        "No VIX history for this range",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VIX_RANGES.forEach { r ->
                    FilterChip(
                        selected = range == r,
                        onClick = { range = r },
                        label = { Text(r.label) },
                    )
                }
            }

            Text(
                "The VIX measures expected 30-day volatility of the S&P 500 — Wall Street's \"fear gauge.\" " +
                    "It tends to spike when markets fall, so a rising VIX signals rising fear.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
