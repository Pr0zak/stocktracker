package com.stocktracker.app.util

/**
 * Squarified treemap — Bruls, Huizing & van Wijk (2000).
 *
 * Values are laid into rows; a tile joins the current row only while doing so keeps that row's
 * *worst* aspect ratio closer to square. Every tile's AREA ends up proportional to its value, and
 * the algorithm spends its remaining freedom on keeping shapes readable rather than tidy.
 *
 * Pure and viewport-agnostic so it can be tested exhaustively — the layout has to run on the device
 * because the server has no idea how wide the screen is.
 */
data class TreemapItem(val key: String, val value: Double)

data class TreemapTile(
    val key: String,
    val value: Double,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    val area: Float get() = w * h
    /** Shortest side — what decides whether a label can fit at all. */
    val shortSide: Float get() = minOf(w, h)
}

object Treemap {

    /**
     * Lay [items] into [width] x [height].
     *
     * Non-positive values are dropped rather than laid out: a zero-value tile has zero area, and a
     * negative one would steal area from its neighbours and corrupt every rectangle in the row.
     * Callers that want such a name on screen must give it a floor value before calling.
     */
    fun layout(items: List<TreemapItem>, width: Float, height: Float): List<TreemapTile> {
        if (width <= 0f || height <= 0f) return emptyList()
        val clean = items.filter { it.value > 0.0 && it.value.isFinite() }
            .sortedByDescending { it.value }
        if (clean.isEmpty()) return emptyList()

        val total = clean.sumOf { it.value }
        if (total <= 0.0) return emptyList()
        val scale = (width.toDouble() * height.toDouble()) / total

        val out = ArrayList<TreemapTile>(clean.size)
        // Geometry in DOUBLE, converted to Float only when a tile is emitted. In Float, one value
        // dominating the total by ~1e8 made the first row's height round to the FULL canvas height,
        // so `rh -= rowH` landed on exactly 0f and every later flush divided by zero — emitting
        // Infinity and negative sides straight into Compose's offset()/size(). Reproduced at
        // 1017 x 1412.5 with values 1e8 : 1 : 1.
        var rx = 0.0; var ry = 0.0; var rw = width.toDouble(); var rh = height.toDouble()
        val row = ArrayList<Pair<TreemapItem, Double>>()   // item to its scaled AREA

        fun worst(candidate: List<Pair<TreemapItem, Double>>, side: Double): Double {
            if (candidate.isEmpty() || side <= 0.0) return Double.MAX_VALUE
            val s = candidate.sumOf { it.second }
            if (s <= 0.0) return Double.MAX_VALUE
            val mx = candidate.maxOf { it.second }
            val mn = candidate.minOf { it.second }
            if (mn <= 0.0) return Double.MAX_VALUE
            val sd = side
            return maxOf((sd * sd * mx) / (s * s), (s * s) / (sd * sd * mn))
        }

        fun flush() {
            if (row.isEmpty()) return
            val s = row.sumOf { it.second }
            // A remaining rectangle with no room left cannot hold anything. Emitting from it
            // produced the Infinity/negative rectangles; dropping the tail is the honest outcome —
            // those tiles have essentially zero area anyway.
            if (s <= 0.0 || rw <= 1e-9 || rh <= 1e-9) { row.clear(); return }
            val horizontal = rw >= rh          // fill along the SHORT side
            var off = 0.0
            if (horizontal) {
                val colW = minOf(s / rh, rw)   // never wider than what is left
                for ((item, a) in row) {
                    val frac = a / s
                    out.add(TreemapTile(item.key, item.value,
                        rx.toFloat(), (ry + off).toFloat(), colW.toFloat(), (rh * frac).toFloat()))
                    off += rh * frac
                }
                rx += colW; rw = (rw - colW).coerceAtLeast(0.0)
            } else {
                val rowH = minOf(s / rw, rh)   // never taller than what is left
                for ((item, a) in row) {
                    val frac = a / s
                    out.add(TreemapTile(item.key, item.value,
                        (rx + off).toFloat(), ry.toFloat(), (rw * frac).toFloat(), rowH.toFloat()))
                    off += rw * frac
                }
                ry += rowH; rh = (rh - rowH).coerceAtLeast(0.0)
            }
            row.clear()
        }

        for (item in clean) {
            val a = item.value * scale
            val side = minOf(rw, rh)
            if (row.isNotEmpty() && worst(row, side) < worst(row + (item to a), side)) {
                flush()
            }
            row.add(item to a)
        }
        flush()
        // Belt and braces: nothing non-finite or non-positive may reach Compose, whatever the
        // arithmetic did. A degenerate rectangle is worse than an absent tile.
        return out.filter { it.w.isFinite() && it.h.isFinite() && it.w > 0f && it.h > 0f }
    }
}
