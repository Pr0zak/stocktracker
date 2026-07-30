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
        var rx = 0f; var ry = 0f; var rw = width; var rh = height
        val row = ArrayList<Pair<TreemapItem, Double>>()   // item to its scaled AREA

        fun worst(candidate: List<Pair<TreemapItem, Double>>, side: Float): Double {
            if (candidate.isEmpty() || side <= 0f) return Double.MAX_VALUE
            val s = candidate.sumOf { it.second }
            if (s <= 0.0) return Double.MAX_VALUE
            val mx = candidate.maxOf { it.second }
            val mn = candidate.minOf { it.second }
            if (mn <= 0.0) return Double.MAX_VALUE
            val sd = side.toDouble()
            return maxOf((sd * sd * mx) / (s * s), (s * s) / (sd * sd * mn))
        }

        fun flush() {
            if (row.isEmpty()) return
            val s = row.sumOf { it.second }
            val horizontal = rw >= rh          // fill along the SHORT side
            var off = 0f
            if (horizontal) {
                val colW = (s / rh).toFloat()
                for ((item, a) in row) {
                    val frac = (a / s).toFloat()
                    out.add(TreemapTile(item.key, item.value, rx, ry + off, colW, rh * frac))
                    off += rh * frac
                }
                rx += colW; rw -= colW
            } else {
                val rowH = (s / rw).toFloat()
                for ((item, a) in row) {
                    val frac = (a / s).toFloat()
                    out.add(TreemapTile(item.key, item.value, rx + off, ry, rw * frac, rowH))
                    off += rw * frac
                }
                ry += rowH; rh -= rowH
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
        return out
    }
}
