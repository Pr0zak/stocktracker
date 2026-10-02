package com.stocktracker.app.ui.heatmap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import com.stocktracker.app.ui.components.ChangePill
import com.stocktracker.app.ui.theme.BenchmarkGrey
import com.stocktracker.app.ui.theme.OutlineDark
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.theme.SurfaceContainerDark
import kotlin.math.roundToInt
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
    "signal" -> t.tier().fill
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
private fun inkFor(fill: Color): Color {
    // Whichever ink contrasts more, measured, rather than a fixed luminance cut-off: the old 0.32
    // cut gave mid-amber tiles white ink at 3.9:1 where the dark ink manages 4.8:1.
    val l = fill.luminance()
    val onWhite = 1.05f / (l + 0.05f)
    val onDark = (l + 0.05f) / (SurfaceDark.luminance() + 0.05f)
    return if (onDark > onWhite) SurfaceDark else Color.White
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
        val signals = ui.mode == "signals"
        // Market-map view state. Zoom is one sector filling the map; "only mine" dims the rest.
        var zoom by rememberSaveable { mutableStateOf<String?>(null) }
        var onlyMine by rememberSaveable { mutableStateOf(false) }
        // A zoom names a sector of the CURRENT tiles; after a mode switch or a refresh that drops
        // it, fall back to all sectors rather than drawing an empty map.
        val zoomed = zoom.takeIf { z -> !signals && ui.tiles.any { sectorOf(it) == z } }
        BackHandler(enabled = zoomed != null) { zoom = null }
        val marketTiles = if (zoomed != null) ui.tiles.filter { sectorOf(it) == zoomed } else ui.tiles
        val mineOnMap = ui.tiles.count { it.symbol.uppercase() in ui.mine }
        Column(
            modifier = Modifier.fillMaxSize().padding(pad)
                // Both modes carry a list under the map, so the screen scrolls and the map gets a
                // fixed height.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
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

            if (!signals && ui.tiles.isNotEmpty()) {
                if (zoomed != null) {
                    ZoomCrumb(zoomed, ui.tiles.filter { sectorOf(it) == zoomed }) { zoom = null }
                }
                // Only offered when some of your names are on the map; "Yours (0)" would be a
                // switch that does nothing.
                if (mineOnMap > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.size(12.dp).clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(2.dp, MINE_OUTLINE, RoundedCornerShape(2.dp)),
                        )
                        Text(
                            "Yours ($mineOnMap)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = onlyMine,
                            onClick = { onlyMine = !onlyMine },
                            label = { Text("Only mine") },
                        )
                    }
                }
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
                // point. Market mode stays flat if the sector lookup failed: an ungrouped map is far
                // better than none.
                // A fixed height rather than an aspect ratio: a square map left small tiles too
                // cramped to carry a ticker. The lists below the map take the rest of the scroll.
                // Signals mode groups by dip tier, the way the market map groups by sector, so the
                // deepest dips sit together instead of one sheet of near-identical tiles.
                signals -> GroupedTreemap(
                    ui.tiles,
                    groupOf = { it.tier().name },
                    caption = { key, members ->
                        listOf(AnnotatedString("${DipTier.valueOf(key).label.uppercase()} · ${members.size}"))
                    },
                    onOpen = onOpenDetail,
                    modifier = Modifier.height(SIGNAL_MAP_HEIGHT),
                    canvas = SurfaceContainerDark,
                    gapped = true,
                )

                // One sector, zoomed: its own tiles fill the map, so the small names get a label.
                zoomed != null -> TreemapCanvas(
                    marketTiles, onOpenDetail, Modifier.height(MARKET_MAP_HEIGHT),
                    mine = ui.mine, onlyMine = onlyMine,
                )

                ui.tiles.any { !it.sector.isNullOrBlank() } ->
                    GroupedTreemap(
                        ui.tiles,
                        groupOf = ::sectorOf,
                        caption = { key, members -> sectorCaption(key, members) },
                        onOpen = onOpenDetail,
                        modifier = Modifier.height(MARKET_MAP_HEIGHT),
                        onHeader = { zoom = it },
                        mine = ui.mine,
                        onlyMine = onlyMine,
                    )

                else -> TreemapCanvas(
                    ui.tiles, onOpenDetail, Modifier.height(MARKET_MAP_HEIGHT),
                    mine = ui.mine, onlyMine = onlyMine,
                )
            }
            if (ui.mode == "market" && ui.tiles.isNotEmpty()) MoveKey()
            if (signals && ui.tiles.isNotEmpty()) TierKey(ui.tiles)

            // What the areas and colours MEAN. A heat map without this is decoration.
            Text(
                if (ui.mode == "market") {
                    "Size = company value · colour = today's move · tap a tile to open it" +
                        (if (zoomed == null) " · tap a sector to zoom in" else "") +
                        (ui.advancing?.let { " · $it up / ${ui.declining} down" } ?: "")
                } else {
                    "Area = how far below its 52-week high · brighter = deeper dip · " +
                        "BUY / SELL tag or top stripe = this system's call, not price"
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
            if (signals && ui.tiles.isNotEmpty()) {
                DipLadder(ui.tiles, onOpenDetail)
                Spacer(Modifier.height(16.dp))
            }
            if (!signals && marketTiles.isNotEmpty()) {
                MarketLists(marketTiles, ui.mine, onOpenDetail)
                Spacer(Modifier.height(16.dp))
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
private fun GroupedTreemap(
    tiles: List<HeatmapTile>,
    groupOf: (HeatmapTile) -> String,
    /**
     * Caption candidates, fullest first. The first that fits the block is drawn, so a narrow block
     * drops its move figure instead of ellipsizing it into "+2…", a number with digits missing.
     */
    caption: (key: String, members: List<HeatmapTile>) -> List<AnnotatedString>,
    onOpen: (Asset) -> Unit,
    modifier: Modifier = Modifier,
    canvas: Color = MaterialTheme.colorScheme.surfaceVariant,
    gapped: Boolean = false,
    /** Tapping a block's caption; null leaves captions inert. */
    onHeader: ((String) -> Unit)? = null,
    mine: Set<String> = emptySet(),
    onlyMine: Boolean = false,
) {
    val groups = tiles.groupBy(groupOf)
    val measurer = rememberTextMeasurer()
    val captionStyle = TextStyle(
        fontSize = MIN_LABEL_SP.sp, lineHeight = 13.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.Bold,
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(canvas, RoundedCornerShape(6.dp)),
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
                val room = (block.w - 6f).coerceAtLeast(1f)
                val options = caption(block.key, members)
                val text = options.firstOrNull { measurer.measure(it, captionStyle).size.width <= room }
                    ?: options.last()
                Text(
                    text,
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
                        .width(with(density) { (block.w - 6f).coerceAtLeast(1f).toDp() })
                        .then(
                            if (onHeader != null) Modifier.clickable { onHeader(block.key) }
                            else Modifier,
                        ),
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
                    gapped = gapped,
                    outlined = t.symbol.uppercase() in mine,
                    dimmed = onlyMine && t.symbol.uppercase() !in mine,
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
    gapped: Boolean = false,
    /** One of your names: a white ring, so it can be found among eighty. */
    outlined: Boolean = false,
    /** "Only mine" is on and this is not yours: faded, still tappable and still spoken. */
    dimmed: Boolean = false,
) {
    val density = LocalDensity.current
    // A gapped tile gives up a pixel on every side, so neighbours of the same tier stay separate
    // instead of fusing into one block of colour.
    val inset = if (gapped) with(density) { 1.dp.toPx() } else 0f
    val wDp = with(density) { (wPx - 2 * inset).coerceAtLeast(1f).toDp() }
    val hDp = with(density) { (hPx - 2 * inset).coerceAtLeast(1f).toDp() }
    val shortDp = minOf(wDp, hDp)
    Box(
        modifier = Modifier
            .offset(with(density) { (xPx + inset).toDp() }, with(density) { (yPx + inset).toDp() })
            .size(wDp, hDp)
            .then(if (gapped) Modifier.clip(RoundedCornerShape(4.dp)) else Modifier)
            // Before the fill: alpha only fades what is drawn after it in the chain.
            .then(if (dimmed) Modifier.alpha(0.3f) else Modifier)
            .background(colourFor(t))
            .then(if (outlined) Modifier.border(2.dp, MINE_OUTLINE) else Modifier)
            .clickable(enabled = !t.symbol.endsWith("-USD")) {
                onOpen(Asset(t.symbol, AssetType.STOCK, t.name.ifBlank { t.symbol }, null))
            }
            // PLAT-4: names the tile and speaks its move regardless of whether the tile is big
            // enough to draw either as text — see heatmapTileDescription's KDoc. clearAndSetSemantics
            // rather than a plain contentDescription so a large tile's own child Text (ticker,
            // percent label) doesn't also get merged in and read twice.
            .clearAndSetSemantics { contentDescription = heatmapTileDescription(t, mine = outlined) },
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
        // The system's call, on tiles with room for it ABOVE the label: the label is pushed down
        // by the tag's height, so the two never overlap. Smaller tiles leave the call to the list
        // under the map and to the spoken description.
        val call = if (t.scale == "signal") t.call() else null
        val showsPct = sym != null && pct.isNotEmpty() && fits(pct, pctSp) &&
            hDp.value >= (sym + pctSp) * fs * 1.85f
        // The same height the label gates above demand, so the pushed-down label is never clipped.
        val labelH = sym?.let { if (showsPct) (it + pctSp) * fs * 1.85f else it * fs * 1.55f } ?: 0f
        val tagged = call != null && sym != null && wDp.value >= 52f * fs && hDp.value >= labelH + CALL_TAG_ROOM * fs
        if (sym != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = if (tagged) Modifier.padding(top = (CALL_TAG_ROOM * fs).dp) else Modifier,
            ) {
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
        if (tagged && call != null) {
            CallTag(call, Modifier.align(Alignment.TopEnd).padding(3.dp))
        } else if (call != null) {
            // No room for the word: a stripe along the top edge still marks the call, so a tile
            // too short for the tag does not read as a hold. The list below names it.
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(4.dp)
                    .background(callColour(call)),
            )
        }
    }
}

/** Vertical room a call tag takes at the top of a tile: 13sp line plus its 3dp margin, rounded up. */
private const val CALL_TAG_ROOM = 18f

/**
 * BUY or SELL in the semantic green and coral. A buy/sell call is a verdict about direction, the
 * same reason the options go/no-go borrows them (TrafficGreen); the tier stays amber.
 */
private fun callColour(call: String): Color = if (call == "BUY") GAIN else LOSS

@Composable
private fun CallTag(call: String, modifier: Modifier = Modifier) {
    Text(
        call,
        fontSize = MIN_LABEL_SP.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Bold,
        color = SurfaceDark,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(callColour(call))
            .padding(horizontal = 4.dp),
    )
}

/** Height of the signals map; the list below it takes the rest of the scroll. */
private val SIGNAL_MAP_HEIGHT = 440.dp

/** What each amber step means, for the tiers on screen, drawn with the tiles' own fills. */
@Composable
private fun TierKey(tiles: List<HeatmapTile>) {
    val present = tiles.map { it.tier() }.toSet()
    val tiers = DipTier.entries.filter { it in present }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Colour key: brighter amber is a deeper dip, card grey is near its high"
            },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (tier in tiers) {
            Box(
                modifier = Modifier.weight(1f).height(20.dp).background(tier.fill),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tier.keyLabel,
                    fontSize = MIN_LABEL_SP.sp,
                    color = inkFor(tier.fill),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Every name on the map as a readable row: grouped by tier, deepest first, sorted by how far each
 * is below its 52-week high, with the system's call as a pill. The treemap shows the shape; small
 * tiles there cannot carry a ticker, so this is where every name can be read and tapped.
 */
@Composable
private fun DipLadder(tiles: List<HeatmapTile>, onOpen: (Asset) -> Unit) {
    var showNear by rememberSaveable { mutableStateOf(false) }
    fun off(t: HeatmapTile) = t.pctOff52wHigh?.let { abs(it) }
    val maxOff = tiles.mapNotNull { off(it) }.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
    val byTier = tiles.groupBy { it.tier() }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (tier in DipTier.entries) {
            val rows = byTier[tier].orEmpty().sortedByDescending { off(it) ?: -1.0 }
            if (rows.isEmpty()) continue
            Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 10.dp, bottom = 2.dp)) {
                Text(
                    tier.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${rows.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Names near their high are the least interesting rows here, and often the most
            // numerous: folded until asked for.
            if (tier == DipTier.NONE && !showNear) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showNear = true }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        rows.joinToString(", ") { it.symbol },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Show ▾",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                continue
            }
            for (t in rows) LadderRow(t, tier, off(t), maxOff, onOpen)
        }
    }
}

@Composable
private fun LadderRow(t: HeatmapTile, tier: DipTier, off: Double?, maxOff: Double, onOpen: (Asset) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !t.symbol.endsWith("-USD")) {
                onOpen(Asset(t.symbol, AssetType.STOCK, t.name.ifBlank { t.symbol }, null))
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            t.symbol,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.width(84.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            // No figure, no bar: an empty track, never a zero-length bar that claims "no dip".
            if (off != null) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((off / maxOff).toFloat().coerceIn(0.02f, 1f))
                        .background(tier.bar),
                )
            }
        }
        Text(
            off?.let { "−${it.roundToInt()}%" } ?: "—",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(44.dp),
        )
        Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
            t.call()?.let { Pill(it, callColour(it)) }
        }
    }
}

@Composable
private fun TreemapCanvas(
    tiles: List<HeatmapTile>, onOpen: (Asset) -> Unit, modifier: Modifier = Modifier,
    mine: Set<String> = emptySet(), onlyMine: Boolean = false,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
    ) {
        val density = LocalDensity.current
        // Font-scaled gates, not shrunk text — see GroupedTreemap.
        val fs = density.fontScale.coerceAtLeast(0.5f)
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val laid = Treemap.layout(tiles.map { TreemapItem(it.symbol, it.size) }, wPx, hPx)
        val bySym = tiles.associateBy { it.symbol }
        // The same tile as the grouped map, so both label (and decline to label) identically. This
        // used to be a second copy of the labelling rules that had drifted from the first.
        for (rect in laid) {
            val t = bySym[rect.key] ?: continue
            TileBox(
                t = t, xPx = rect.x, yPx = rect.y, wPx = rect.w, hPx = rect.h, fs = fs, onOpen = onOpen,
                outlined = t.symbol.uppercase() in mine,
                dimmed = onlyMine && t.symbol.uppercase() !in mine,
            )
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
    // The server sends the drawdown negative; written with a real minus, like the price labels.
    "signal" -> pctOff52wHigh?.let { "−${abs(it).roundToInt()}%" } ?: ""
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

/** Height of the market map; the lists below it take the rest of the scroll. */
private val MARKET_MAP_HEIGHT = 480.dp

/** The ring on your own names. White, because every hue on this map already means something. */
private val MINE_OUTLINE = Color.White

private fun sectorOf(t: HeatmapTile): String = t.sector?.takeIf { it.isNotBlank() } ?: "Other"

/**
 * A block's move, weighted by company value the way the block's area is, so the number agrees with
 * what the eye sees: a sector whose biggest name fell reads down even if most small names rose.
 * Null when the block has no usable size, never a 0% that was not measured.
 */
internal fun sectorMove(members: List<HeatmapTile>): Double? {
    val w = members.sumOf { it.size }
    if (w <= 0.0 || !w.isFinite()) return null
    return members.sumOf { it.value * it.size } / w
}

private fun signedPct(v: Double): String =
    (if (v > 0.05) "+" else if (v < -0.05) "−" else "") +
        String.format(java.util.Locale.US, "%.1f", abs(v)) + "%"

private fun moveInk(v: Double): Color = when {
    v > 0.05 -> GAIN
    v < -0.05 -> LOSS
    else -> BenchmarkGrey
}

/** "TECHNOLOGY +1.5% ›": the name, its weighted move in green or coral, and a tap affordance. */
private fun sectorCaption(key: String, members: List<HeatmapTile>): List<AnnotatedString> {
    val name = shortSector(key).uppercase()
    val move = sectorMove(members)
    fun build(withMove: Boolean, chevron: Boolean) = buildAnnotatedString {
        append(name)
        if (withMove && move != null) {
            append("  ")
            withStyle(SpanStyle(color = moveInk(move), fontFamily = FontFamily.Monospace)) { append(signedPct(move)) }
        }
        if (chevron) append("  ›")
    }
    // Fullest first; the name alone is the floor, and it may still ellipsize as names always have.
    return listOf(build(true, true), build(true, false), build(false, true), build(false, false))
}

/** Shown while zoomed: the way back, which sector this is, and its move. */
@Composable
private fun ZoomCrumb(sector: String, members: List<HeatmapTile>, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onBack)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "‹ All sectors  /  ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            sector,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        sectorMove(members)?.let { m ->
            Text(
                signedPct(m),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = moveInk(m),
            )
        }
    }
}

/**
 * The names behind the map, readable: yours first, then the day's biggest moves. About a quarter of
 * the market map's tiles are too small to carry a ticker, and the day's biggest faller is often one
 * of them.
 */
@Composable
private fun MarketLists(tiles: List<HeatmapTile>, mine: Set<String>, onOpen: (Asset) -> Unit) {
    var showAllMine by rememberSaveable { mutableStateOf(false) }
    val yours = tiles.filter { it.symbol.uppercase() in mine }.sortedByDescending { it.value }
    val risers = tiles.filter { it.value > 0.05 }.sortedByDescending { it.value }.take(MOVERS)
    val fallers = tiles.filter { it.value < -0.05 }.sortedBy { it.value }.take(MOVERS)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (yours.isNotEmpty()) {
            ListHeader("Your names today", yours.size)
            val shown = if (showAllMine) yours else yours.take(YOURS_PREVIEW)
            for (t in shown) MarketRow(t, yoursTag = false, onOpen)
            if (yours.size > YOURS_PREVIEW) {
                Text(
                    if (showAllMine) "Show fewer ▴" else "Show all ${yours.size} ▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showAllMine = !showAllMine }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
        // A day with nothing up says so, rather than leaving a header over nothing.
        ListHeader("Biggest risers", risers.size)
        if (risers.isEmpty()) EmptyListLine("Nothing on the map is up.")
        for (t in risers) MarketRow(t, yoursTag = t.symbol.uppercase() in mine, onOpen)
        ListHeader("Biggest fallers", fallers.size)
        if (fallers.isEmpty()) EmptyListLine("Nothing on the map is down.")
        for (t in fallers) MarketRow(t, yoursTag = t.symbol.uppercase() in mine, onOpen)
    }
}

private const val YOURS_PREVIEW = 6
private const val MOVERS = 3

@Composable
private fun ListHeader(label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 10.dp, bottom = 2.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyListLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}

@Composable
private fun MarketRow(t: HeatmapTile, yoursTag: Boolean, onOpen: (Asset) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !t.symbol.endsWith("-USD")) {
                onOpen(Asset(t.symbol, AssetType.STOCK, t.name.ifBlank { t.symbol }, null))
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            t.symbol,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.width(64.dp),
        )
        Text(
            shortSector(sectorOf(t)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (yoursTag) {
            Text(
                "YOURS",
                fontSize = MIN_LABEL_SP.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .border(1.dp, OutlineDark, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
            )
        }
        ChangePill(t.value)
    }
}
