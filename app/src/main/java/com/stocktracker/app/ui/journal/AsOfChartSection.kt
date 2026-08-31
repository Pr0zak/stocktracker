package com.stocktracker.app.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.PriceChart
import com.stocktracker.app.util.Formatting

/**
 * "As of <verdict date>" — the chart as it stood when the verdict was given, with a stepper to
 * reveal what happened next.
 *
 * Deliberately NOT named replay: `JournalReplay` and `POST /journal/replay` already mean the
 * backend's mechanical plan replay, which measures what the plan WOULD have done. This shows what
 * you were LOOKING AT. Two meanings under one word on one screen is how a picture gets read as a
 * measurement.
 */
@Composable
fun AsOfChartSection(symbol: String, verdictDateIso: String) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

    // THREE_YEAR because it is the longest DAILY range. ALL is weekly, and a weekly bar cannot show
    // the day a verdict was given; the shorter ranges are intraday, and PricePoint documents that
    // the same trading day yields different extremes per requested range — so the cursor stays on
    // one range's series rather than mixing two.
    val bars by produceState(initialValue = null as List<PricePoint>?, symbol) {
        value = runCatching {
            ServiceLocator.repository.history(
                Asset(symbol = symbol.uppercase(), type = AssetType.STOCK, displayName = symbol.uppercase()),
                ChartRange.THREE_YEAR,
            )
        }.getOrElse { emptyList() }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "As of $verdictDateIso",
            style = MaterialTheme.typography.labelLarge,
            color = neutral,
        )

        val loaded = bars
        when {
            loaded == null ->
                Row(Modifier.fillMaxWidth().height(160.dp)) { CircularProgressIndicator() }

            loaded.isEmpty() -> Text(
                "Price history could not be loaded, so there is nothing to show as of that date.",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )

            else -> {
                val anchor = asOfIndex(loaded, verdictDateIso)
                if (anchor == null) {
                    // Distinct from "no data": we have bars, they just start after the verdict.
                    Text(
                        "This verdict predates the price history available for ${symbol.uppercase()}, " +
                            "so the chart it was given against cannot be reconstructed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                } else {
                    var cursor by remember(symbol, verdictDateIso, loaded.size) { mutableIntStateOf(anchor) }
                    var scrubbed by remember { mutableStateOf<PricePoint?>(null) }
                    val limit = stepLimit(loaded, anchor)
                    // TRUNCATED, not clamped inside the renderer: a shorter list cannot leak a future
                    // bar into the y-scale, the volume maximum, the high/low markers, the x-axis
                    // ticks or the scrub.
                    val drawn = barsThrough(loaded, cursor)

                    PriceChart(
                        points = drawn,
                        up = (drawn.lastOrNull()?.price ?: 0.0) >= (drawn.firstOrNull()?.price ?: 0.0),
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        showHighLow = true,
                        showReadout = false,
                        showAxis = true,
                        onScrubChange = { scrubbed = it },
                        valueFormatter = { Formatting.price(it, "USD", false) },
                        timeFormatter = { ms ->
                            java.time.Instant.ofEpochMilli(ms)
                                .atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
                        },
                    )

                    val shown = scrubbed ?: drawn.lastOrNull()
                    val ahead = cursor - anchor
                    Text(
                        buildString {
                            append(shown?.let { Formatting.price(it.price, "USD", false) } ?: "—")
                            append(" · ")
                            append(
                                when {
                                    ahead == 0 -> "the day of the verdict"
                                    ahead == 1 -> "1 session later"
                                    else -> "$ahead sessions later"
                                },
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { cursor = anchor },
                            enabled = cursor > anchor,
                        ) { Text("Back to the verdict") }
                        TextButton(
                            onClick = { cursor = (cursor + 5).coerceAtMost(limit) },
                            enabled = cursor < limit,
                        ) { Text("+5 sessions") }
                        TextButton(
                            onClick = { cursor = limit },
                            enabled = cursor < limit,
                        ) { Text("+${limit - anchor}") }
                    }
                    if (cursor >= limit && limit < loaded.lastIndex) {
                        Text(
                            "Stops ${limit - anchor} sessions out — past that this is just the " +
                                "current chart, which the ticker screen already shows.",
                            style = MaterialTheme.typography.labelSmall,
                            color = neutral,
                        )
                    }
                }
            }
        }
    }
}
