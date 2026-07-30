package com.stocktracker.app.util

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: the remaining-rectangle collapse in [Treemap.layout].
 *
 * Once one value dominated the total by roughly 1e8, the first row consumed the whole canvas in
 * FLOAT arithmetic (`rh -= rowH` landing on exactly 0f), and every later flush divided by that zero
 * remainder — so tail tiles came back with Infinity or negative sides, positioned outside the
 * canvas, and were fed straight into Compose's `Modifier.offset(...).size(...)`.
 *
 * Found by an adversarial review, not by the original 12 tests: the "wildly lopsided" case there
 * used a 1e4 ratio, which passes. Fixed by keeping the geometry bookkeeping in Double and clamping
 * the remaining rectangle; a final filter drops anything degenerate regardless.
 */
class TreemapDegenerateTest {

    @Test
    fun `a dominant value must not produce infinite or negative rectangles`() {
        val w = 1017f
        val h = 1412.5f
        val tiles = Treemap.layout(
            listOf(TreemapItem("BIG", 1e8), TreemapItem("A", 1.0), TreemapItem("B", 1.0)), w, h,
        ) + Treemap.layout(
            listOf(TreemapItem("BIG", 1e8)) + (1..19).map { TreemapItem("S$it", 1.0) }, w, h,
        )
        tiles.filter { !it.w.isFinite() || !it.h.isFinite() || it.w <= 0f || it.h <= 0f }
            .forEach { println("bad rectangle: $it") }

        assertTrue(
            "every tile must have finite, positive sides",
            tiles.all { it.w.isFinite() && it.h.isFinite() && it.w > 0f && it.h > 0f },
        )
        assertTrue(
            "no tile may sit outside the canvas",
            tiles.all { it.x >= -0.01f && it.y >= -0.01f && it.x + it.w <= w + 0.05f && it.y + it.h <= h + 0.05f },
        )
    }
}
