package com.stocktracker.app.ui.heatmap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.HeatmapTile
import com.stocktracker.app.util.Treemap
import com.stocktracker.app.util.TreemapItem
import kotlin.math.abs

/** Market green / loss red, as used everywhere else in the app. */
private val GAIN = Color(0xFF2E9E57)
private val LOSS = Color(0xFFB0543D)

/**
 * Amber, and ONLY for this system's own reads.
 *
 * Green and red mean the market moved; amber means the app has an opinion. Rendering a dip tier on
 * the price scale would make "we flagged this" read as "it went up today", which is the opposite of
 * the truth for a name that is down 40%.
 */
private val SIGNAL = Color(0xFFB0872B)
private val FLAT = Color(0xFF39424E)

/** Magnitude rides in lightness as well as hue, so the map still reads without colour vision. */
private fun ramp(base: Color, t: Float): Color {
    val c = t.coerceIn(0f, 1f)
    return if (c < 0.5f) lerp(lerp(base, Color.Black, 0.66f), base, c / 0.5f)
    else lerp(base, lerp(base, Color.White, 0.34f), (c - 0.5f) / 0.5f)
}

private fun colourFor(t: HeatmapTile): Color = when (t.scale) {
    "signal" -> if (t.value <= 0.0) FLAT else ramp(SIGNAL, 0.30f + (t.value.toFloat() / 4f) * 0.50f)
    else -> {
        val p = t.value
        if (abs(p) < 0.05) FLAT
        else {
            // Was abs(p)/4 clamped at 1, so every move at or beyond 4% produced the SAME colour to
            // the byte — a 4% drift and a 40% collapse looked identical. A log curve keeps the
            // common 0-3% range well spread while still separating the extremes, and never fully
            // saturates.
            val mag = kotlin.math.ln(1.0 + abs(p) / 1.6) / kotlin.math.ln(1.0 + 25.0 / 1.6)
            ramp(if (p > 0) GAIN else LOSS, 0.22f + mag.toFloat().coerceIn(0f, 1f) * 0.62f)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatmapScreen(onOpenDetail: (Asset) -> Unit, onBack: () -> Unit) {
    val vm: HeatmapViewModel = viewModel()
    val ui by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Heat map") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load(refresh = true) }) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ui.mode == "market",
                    onClick = { vm.setMode("market") },
                    label = { Text("Market") },
                )
                FilterChip(
                    selected = ui.mode == "signals",
                    onClick = { vm.setMode("signals") },
                    label = { Text("My signals") },
                )
            }

            ui.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = SIGNAL)
            }

            when {
                ui.loading && ui.tiles.isEmpty() -> Box(
                    Modifier.fillMaxWidth().aspectRatio(1f), Alignment.Center,
                ) { CircularProgressIndicator() }

                ui.tiles.isEmpty() -> Text(
                    "Nothing to draw yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> TreemapCanvas(ui.tiles, onOpenDetail)
            }

            // What the areas and colours MEAN. A heat map without this is decoration.
            Text(
                if (ui.mode == "market") {
                    "Area = market cap · colour = today's move" +
                        (ui.advancing?.let { " · $it up / ${ui.declining} down" } ?: "")
                } else {
                    "Area = how far below its 52-week high · colour = this system's dip tier, " +
                        "not price"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Absent data, named. A name we could not price is not a name that did not move.
            if (ui.skipped.isNotEmpty()) {
                Text(
                    "No 52-week range yet: ${ui.skipped.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Signals come from the NIGHTLY scan — always hours old. Without this a day-old read
            // renders exactly like a live one.
            if (ui.mode == "signals") {
                ui.asOf?.let { epoch ->
                    val mins = ((System.currentTimeMillis() / 1000.0) - epoch) / 60.0
                    Text(
                        "From the scan " + when {
                            mins < 90 -> "${mins.toInt()} min ago"
                            mins < 60 * 36 -> "${(mins / 60).toInt()}h ago"
                            else -> "${(mins / 1440).toInt()}d ago"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mins > 60 * 30) SIGNAL else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (ui.unpriced.isNotEmpty()) {
                Text(
                    "Couldn't price: ${ui.unpriced.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall, color = SIGNAL,
                )
            }
            if (ui.universeStale == true) {
                Text(
                    "The symbol list is out of date and due a refresh",
                    style = MaterialTheme.typography.labelSmall, color = SIGNAL,
                )
            }
            ui.cachedAgeSeconds?.let { age ->
                Text(
                    "Priced " + if (age < 90) "just now" else "${age / 60} min ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TreemapCanvas(tiles: List<HeatmapTile>, onOpen: (Asset) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
    ) {
        val density = LocalDensity.current
        // The fit gate below measures dp, but Text sizes in SP, which grows with the user's system
        // font-size setting. At 1.3x accessibility scale every label came out 30% larger than the
        // gate had allowed for and clipped. Divide it back out so what is drawn matches what was
        // measured; the map stays legible instead of turning to shards at large font sizes.
        val fs = density.fontScale.coerceAtLeast(0.5f)
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val laid = Treemap.layout(tiles.map { TreemapItem(it.symbol, it.size) }, wPx, hPx)
        val bySym = tiles.associateBy { it.symbol }

        for (rect in laid) {
            val t = bySym[rect.key] ?: continue
            val wDp = with(density) { rect.w.toDp() }
            val hDp = with(density) { rect.h.toDp() }
            val shortDp = with(density) { rect.shortSide.toDp() }
            val areaDp = wDp.value * hDp.value

            Box(
                modifier = Modifier
                    .offset(with(density) { rect.x.toDp() }, with(density) { rect.y.toDp() })
                    .size(wDp, hDp)
                    .background(colourFor(t))
                    // Crypto has no stock detail screen, so those tiles cannot open. They used to
                    // look identical to tappable ones — a dead tap reads as a broken app. Marked
                    // instead of silently inert.
                    .clickable(enabled = !t.symbol.endsWith("-USD")) {
                        onOpen(Asset(t.symbol, AssetType.STOCK, t.name.ifBlank { t.symbol }, null))
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Content degrades with area: a label either FITS or is not drawn. Truncating a
                // ticker mid-word turns a readable map into noise.
                if (shortDp.value >= 22f && wDp.value >= 40f) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            t.symbol,
                            fontSize = ((shortDp.value * 0.30f).coerceIn(9f, 22f) / fs).sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                        )
                        if (areaDp > 2600f) {
                            Text(
                                t.label(),
                                fontSize = ((shortDp.value * 0.17f).coerceIn(8f, 13f) / fs).sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1,
                            )
                        }
                        if (areaDp > 12000f && t.name.isNotBlank()) {
                            Text(
                                t.name,
                                fontSize = (9f / fs).sp,
                                color = Color.White.copy(alpha = 0.66f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 3.dp),
                            )
                        }
                    }
                } else if (shortDp.value >= 11f && t.symbol.length <= 4) {
                    // Only tickers that fit WHOLE. take(4) turned GOOGL into "GOOG" — a different
                    // real security, which is on this very map. Truncating a ticker does not
                    // abbreviate it, it renames it; the invariant above says a label either fits or
                    // is not drawn, and this branch was breaking it.
                    Text(
                        t.symbol,
                        fontSize = ((shortDp.value * 0.42f).coerceIn(7f, 11f) / fs).sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
                // Below ~11dp a tile carries colour only — a label there would be unreadable and a
                // truncated one is worse than none.
            }
        }
    }
}

/** The figure shown on the tile: the move for price, the drawdown for signals. */
private fun HeatmapTile.label(): String = when (scale) {
    "signal" -> pctOff52wHigh?.let { "${it.toInt()}%" } ?: ""
    else -> (if (value > 0) "+" else "") + String.format("%.1f", value) + "%"
}
