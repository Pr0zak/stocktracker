package com.stocktracker.app.ui.heatmap

import com.stocktracker.app.data.remote.HeatmapTile
import kotlin.math.abs

/**
 * What a screen-reader user hears for one tile, independent of whether the tile is big enough to
 * draw any text at all.
 *
 * Below roughly 40dp the tile draws nothing but colour — this file's own legend line says "colour =
 * today's move" (market mode) or "colour = this system's dip tier" (signals mode), and a reader
 * cannot see colour. That makes a small tile a genuinely unlabelled control: tappable, but silent
 * about what it is or what it means. A [android.view.View.setContentDescription]-equivalent here
 * fixes both problems in one move — the tile is named, AND the move that colour alone was carrying
 * is spoken instead.
 *
 * PLAT-4.
 */
fun heatmapTileDescription(t: HeatmapTile, mine: Boolean = false): String {
    val move = when (t.scale) {
        // abs: the server sends the drawdown negative, and "-64% off" reads as a double negative.
        "signal" -> (t.pctOff52wHigh?.let { "${abs(it).toInt()}% off its 52-week high" } ?: "flagged by signals") +
            // The tier and call are spoken only when the scan supplied them; the map shows both.
            (t.dip?.let { ", ${t.tier().label.lowercase()}" } ?: "") +
            (t.call()?.let { ", system says ${it.lowercase()}" } ?: "")
        else -> {
            val v = t.value
            val word = when {
                v > 0.05 -> "up"
                v < -0.05 -> "down"
                else -> "flat"
            }
            "$word ${"%.1f".format(abs(v))}%"
        }
    }
    return "${t.symbol}, $move" + if (mine) ", on your list" else ""
}
