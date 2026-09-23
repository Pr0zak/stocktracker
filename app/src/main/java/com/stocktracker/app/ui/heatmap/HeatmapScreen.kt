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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.graphics.luminance
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
import com.stocktracker.app.ui.theme.DividerDark
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.HeatGainFar
import com.stocktracker.app.ui.theme.HeatLossFar
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.Signal
import com.stocktracker.app.ui.theme.SurfaceDark

/** Market green / loss red, as used everywhere else in the app. */
private val GAIN = GainGreen
private val LOSS = LossRed

/**
 * Amber, and ONLY for this system's own reads.
 *
 * Green and red mean the market moved; amber means the app has an opinion. Rendering a dip tier on
 * the price scale would make "we flagged this" read as "it went up today", which is the opposite of
 * the truth for a name that is down 40%.
 */
private val SIGNAL = Signal
private val FLAT = DividerDark

/** Magnitude rides in lightness as well as hue, so the map still reads without colour vision. */
private fun ramp(base: Color, t: Float): Color {
    val c = t.coerceIn(0f, 1f)
    return if (c < 0.5f) lerp(lerp(base, Color.Black, 0.66f), base, c / 0.5f)
    else lerp(base, lerp(base, Color.White, 0.34f), (c - 0.5f) / 0.5f)
}

private fun colourFor(t: HeatmapTile): Color = when (t.scale) {
    "signal" -> if (t.value <= 0.0) FLAT else ramp(SIGNAL, 0.30f + (t.value.toFloat() / 4f) * 0.50f)
    else -> colourForMove(t.value)
}

/** The price-move colour for a day's percentage change. Shared with the key under the map. */
private fun colourForMove(p: Double): Color = run {
    run {
        if (abs(p) < 0.05) FLAT
        else {
            // Was abs(p)/4 clamped at 1, so every move at or beyond 4% produced the SAME colour to
            // the byte — a 4% drift and a 40% collapse looked identical. A log curve keeps the
            // common 0-3% range well spread while still separating the extremes, and never fully
            // saturates.
            val mag = (kotlin.math.ln(1.0 + abs(p) / 1.6) / kotlin.math.ln(1.0 + 25.0 / 1.6))
                .toFloat().coerceIn(0f, 1f)
            // Magnitude buys hue as well as lightness — see HeatGainFar in the theme for the
            // measurements. Lightness alone cannot carry sign, because lightness is already spoken
            // for by size of move: it is exactly what let a small gain and a large loss land on the
            // same colour for a protanope.
            val base = if (p > 0) lerp(GAIN, HeatGainFar, mag * 0.75f)
                       else lerp(LOSS, HeatLossFar, mag * 0.55f)
            ramp(base, 0.22f + mag * 0.62f)
        }
    }
}

/**
 * Ink chosen per tile, because a fixed white fails on most of this map.
 *
 * Every label here was `Color.White` regardless of what it sat on. Measured: white on a +2% tile
 * (`#5AD496`) is 1.86:1, and on the biggest risers 1.52:1 — against 4.5:1 for AA body text. The
 * tiles you most want to read were the least readable ones. Picking the ink from the tile's own
 * luminance puts every tile on this map at 4.56:1 or better.
 */
private fun inkFor(fill: Color): Color =
    if (fill.luminance() > 0.32f) SurfaceDark else Color.White

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

                // Grouped when the tiles carry a classification — which is the market map's whole
                // point. Signals mode has no sector on its tiles and stays flat, and so does market
                // mode if the sector lookup failed: an ungrouped map is far better than none.
                // The map takes the height the screen has rather than a fixed aspect ratio, which
                // left a quarter of the screen empty below it on a tall phone while its small tiles
                // were too cramped to carry a ticker.
                ui.tiles.any { !it.sector.isNullOrBlank() } ->
                    SectorTreemap(ui.tiles, onOpenDetail, Modifier.weight(1f))

                else -> TreemapCanvas(ui.tiles, onOpenDetail, Modifier.weight(1f))
            }
            if (ui.mode == "market" && ui.tiles.isNotEmpty()) MoveKey()

            // What the areas and colours MEAN. A heat map without this is decoration.
            Text(
                if (ui.mode == "market") {
                    "Size = company value · colour = today's move · tap a tile to open it" +
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

/** Height reserved for a sector's caption strip. Small enough not to eat the tiles it labels. */
/** Nothing on this map is drawn below this, at any font scale. Material's own floor. */
private const val MIN_LABEL_SP = 11f

// 11sp text needs about 13-14sp of line; at 15dp the captions were clipped through their middle.
private val SECTOR_HEADER = 18.dp

/**
 * Short sector names for the block captions. The full Yahoo names ("Communication Services",
 * "Consumer Cyclical") were cut to "CONSUMER CYCLIC…" on every block narrower than half the map.
 */
private fun shortSector(name: String): String = when (name) {
    "Communication Services" -> "Comm. Services"
    "Consumer Cyclical" -> "Cons. Cyclical"
    "Consumer Defensive" -> "Cons. Defensive"
    "Financial Services" -> "Financials"
    "Basic Materials" -> "Materials"
    else -> name
}

/**
 * The market map, drawn as SECTOR BLOCKS rather than one flat sheet of rectangles.
 *
 * A flat treemap sorted by market cap put JNJ between ASML and INTC and offered no way to read "tech
 * is red, energy is green" — which is the entire question a market map exists to answer, and the
 * reason the finviz-style map is grouped. Two levels now: an outer squarified layout of sectors sized
 * by their combined market cap, and an inner squarified layout of the names inside each.
 *
 * Unclassified names collect in an "Other" block instead of disappearing.
 */
@Composable
private fun SectorTreemap(tiles: List<HeatmapTile>, onOpen: (Asset) -> Unit, modifier: Modifier = Modifier) {
    val groups = tiles.groupBy { it.sector?.takeIf { s -> s.isNotBlank() } ?: "Other" }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
    ) {
        val density = LocalDensity.current
        // The fit gate measures dp; Text sizes in SP, which grows with the user's font-size
        // setting. This used to be reconciled by dividing the font size back down by fontScale —
        // which made the map fit, and in doing so cancelled the setting outright: at 200% scale the
        // labels came out exactly the same physical size as at 100%. That is the one thing a
        // font-size setting may never do.
        //
        // So scale the GATES instead. A bigger font means a tile has to be bigger to earn a label,
        // and a tile that no longer qualifies is drawn unlabelled — which is this file's own stated
        // rule, the same one that refuses to truncate GOOGL into GOOG. Nothing is ever shrunk below
        // the 11sp floor to make it fit.
        val fs = density.fontScale.coerceAtLeast(0.5f)
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        // Scaled with the font setting, like every other gate here: a bigger caption needs a taller strip.
        val headerPx = with(density) { SECTOR_HEADER.toPx() } * fs

        val blocks = Treemap.layout(
            groups.map { (name, ts) -> TreemapItem(name, ts.sumOf { it.size }) }, wPx, hPx,
        )

        for (block in blocks) {
            val members = groups[block.key] ?: continue
            // The caption only gets its own strip when the block can spare it; in a sliver the
            // tiles matter more than the label, and a header that eats its own block is worse than
            // no header. Below the threshold the block is drawn unlabelled rather than squashed.
            val labelled = block.h > headerPx * 3f && block.w > headerPx * 4f
            val innerTop = if (labelled) headerPx else 0f
            val innerH = (block.h - innerTop).coerceAtLeast(1f)

            // No per-block background: the canvas already provides one, and painting `surface` over
            // it just drew near-white gutters in the light theme. The caption sits on that canvas,
            // so it has to use a theme colour — Color.White here was invisible in light mode, which
            // is exactly how it shipped to the screenshot before this was caught.
            if (labelled) {
                Text(
                    shortSector(block.key).uppercase(),
                    fontSize = MIN_LABEL_SP.sp,
                    lineHeight = 13.sp,
                    letterSpacing = 0.4.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .offset(
                            with(density) { (block.x + 3f).toDp() },
                            with(density) { (block.y + 2f).toDp() },
                        )
                        .width(with(density) { (block.w - 6f).coerceAtLeast(1f).toDp() }),
                )
            }

            val inner = Treemap.layout(
                members.map { TreemapItem(it.symbol, it.size) },
                (block.w - 2f).coerceAtLeast(1f), (innerH - 2f).coerceAtLeast(1f),
            )
            val bySym = members.associateBy { it.symbol }
            for (rect in inner) {
                val t = bySym[rect.key] ?: continue
                TileBox(
                    t = t,
                    xPx = block.x + 1f + rect.x,
                    yPx = block.y + innerTop + 1f + rect.y,
                    wPx = rect.w,
                    hPx = rect.h,
                    fs = fs,
                    onOpen = onOpen,
                )
            }
        }
    }
}

/**
 * Which way, for a tile with no room to say it in digits.
 *
 * The signed percentage is only drawn on tiles above roughly 2,000 square dp. Everything smaller
 * carried its direction in hue alone — which is precisely the reader this ramp cannot serve. An
 * arrow is one glyph, needs no colour, and cannot be mistaken for part of a ticker.
 *
 * Empty on the signal scale: amber there means "this system flagged it", not "it went up", and an
 * arrow would assert a direction the number does not carry.
 */
private fun HeatmapTile.direction(): String = when {
    scale == "signal" -> ""
    value > 0.05 -> "\u25B2"
    value < -0.05 -> "\u25BC"
    else -> ""
}

/** One stock rectangle. Shared by the grouped and flat layouts so labelling degrades identically. */
@Composable
private fun TileBox(
    t: HeatmapTile, xPx: Float, yPx: Float, wPx: Float, hPx: Float, fs: Float,
    onOpen: (Asset) -> Unit,
) {
    val density = LocalDensity.current
    val wDp = with(density) { wPx.toDp() }
    val hDp = with(density) { hPx.toDp() }
    val shortDp = with(density) { minOf(wPx, hPx).toDp() }
    val areaDp = wDp.value * hDp.value
    Box(
        modifier = Modifier
            .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
            .size(wDp, hDp)
            .background(colourFor(t))
            .clickable(enabled = !t.symbol.endsWith("-USD")) {
                onOpen(Asset(t.symbol, AssetType.STOCK, t.name.ifBlank { t.symbol }, null))
            }
            // PLAT-4: names the tile and speaks its move regardless of whether the tile is big
            // enough to draw either as text — see heatmapTileDescription's KDoc. clearAndSetSemantics
            // rather than a plain contentDescription so a large tile's own child Text (ticker,
            // percent label) doesn't also get merged in and read twice.
            .clearAndSetSemantics { contentDescription = heatmapTileDescription(t) },
        contentAlignment = Alignment.Center,
    ) {
        // A ticker that does not fit is not drawn, and "fit" has to be arithmetic rather than a
        // hope: Text clips at maxLines = 1, and a clipped ticker is not an abbreviation, it is a
        // rename — GOOGL cut to GOOG is a different real security, and it is on this very map.
        // The gates above no longer shrink text to force a fit, so this is the guard that replaces
        // that behaviour. Monospace advance is about 0.6em; the margin is for the edge glyph.
        fun fits(text: String, sp: Float) =
            // sp, not dp: at a 2x font setting a 22sp glyph is twice as wide in dp as the
            // number suggests, which is exactly the discrepancy the old fontScale divisor
            // was papering over. Multiply it back in or this guard permits the clip it exists
            // to prevent — which is how GOOGL rendered as GOOG at 200%.
            wDp.value >= text.length * sp * fs * 0.62f + 6f
        // A monospace line runs about 1.5x its size; 1.35 let the bottom of the text clip on
        // short tiles (AMGN, and XOM's percentage).
        fun tall(sp: Float) = hDp.value >= sp * fs * 1.55f
        val symSp = (shortDp.value * 0.30f).coerceIn(MIN_LABEL_SP, 20f)
        val ink = inkFor(colourFor(t))
        val dir = t.direction()
        val pct = t.label()
        val pctSp = (shortDp.value * 0.17f).coerceIn(MIN_LABEL_SP, 13f)
        // Three cases, decided by arithmetic so nothing is ever clipped:
        //  1. room for the ticker AND its percentage: both, the number carrying the direction;
        //  2. room for the ticker only: the ticker, with the arrow beside it when that fits too;
        //  3. no room for the ticker: colour only. The old fallback drew a bare "▲" or "▼" with no
        //     name — about twenty of them on the market map — a direction attached to nothing. The
        //     tile still opens on tap and still speaks its name and move to a screen reader.
        val sym = when {
            fits(t.symbol, symSp) && tall(symSp) -> symSp
            fits(t.symbol, MIN_LABEL_SP) && tall(MIN_LABEL_SP) -> MIN_LABEL_SP
            else -> null
        }
        if (sym != null) {
            val showsPct = pct.isNotEmpty() && fits(pct, pctSp) && hDp.value >= (sym + pctSp) * fs * 1.85f
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TileSymbol(
                    t.symbol,
                    dir.takeIf { !showsPct && it.isNotEmpty() && fits("$it  ${t.symbol}", sym) },
                    sym, ink,
                )
                if (showsPct) {
                    Text(
                        pct,
                        fontSize = pctSp.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ink.copy(alpha = 0.9f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TreemapCanvas(tiles: List<HeatmapTile>, onOpen: (Asset) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
    ) {
        val density = LocalDensity.current
        // Font-scaled gates, not shrunk text — see SectorTreemap.
        val fs = density.fontScale.coerceAtLeast(0.5f)
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val laid = Treemap.layout(tiles.map { TreemapItem(it.symbol, it.size) }, wPx, hPx)
        val bySym = tiles.associateBy { it.symbol }
        // The same tile as the grouped map, so both label (and decline to label) identically. This
        // used to be a second copy of the labelling rules that had drifted from the first.
        for (rect in laid) {
            val t = bySym[rect.key] ?: continue
            TileBox(t = t, xPx = rect.x, yPx = rect.y, wPx = rect.w, hPx = rect.h, fs = fs, onOpen = onOpen)
        }
    }
}

/**
 * The colour key under the market map: what a shade means, from a 5% fall to a 5% rise, drawn with
 * the map's own colour function so the key cannot disagree with the tiles.
 */
@Composable
private fun MoveKey() {
    val steps = listOf(-5.0, -3.0, -1.0, 0.0, 1.0, 3.0, 5.0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Colour key: darker red is a bigger fall, brighter green a bigger rise, grey is flat"
            },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (p in steps) {
            val fill = if (p == 0.0) FLAT else colourForMove(p)
            Box(
                modifier = Modifier.weight(1f).height(20.dp).background(fill),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (p == 0.0) "0" else (if (p > 0) "+" else "−") + "${kotlin.math.abs(p).toInt()}%",
                    fontSize = MIN_LABEL_SP.sp,
                    fontFamily = FontFamily.Monospace,
                    color = inkFor(fill),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun HeatmapTile.label(): String = when (scale) {
    "signal" -> pctOff52wHigh?.let { "${it.toInt()}%" } ?: ""
    // A real minus sign, matching the colour key and the rest of the app.
    else -> (if (value > 0) "+" else if (value < 0) "−" else "") + String.format(java.util.Locale.US, "%.1f", kotlin.math.abs(value)) + "%"
}

/**
 * A ticker, optionally preceded by its direction arrow.
 *
 * The arrow is deliberately NOT in the monospace family the ticker uses: Android's monospace face
 * does not carry the geometric-shapes block on every device, and a missing glyph would render as a
 * tofu box sitting where a direction ought to be. The default family has it, and being a shade
 * smaller keeps it reading as a mark rather than a letter of the symbol.
 */
@Composable
private fun TileSymbol(symbol: String, dir: String?, sp: Float, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!dir.isNullOrEmpty()) {
            Text(
                dir,
                fontSize = (sp * 0.8f).sp,
                color = ink,
                maxLines = 1,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
        Text(
            symbol,
            fontSize = sp.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = ink,
            maxLines = 1,
        )
    }
}
