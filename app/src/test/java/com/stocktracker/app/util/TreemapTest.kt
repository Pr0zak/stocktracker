package com.stocktracker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The invariant that matters: every tile's AREA is proportional to its value, and the tiles exactly
 * tile the rectangle — no gaps, no overlaps, nothing off-canvas. A treemap that loses area is
 * lying about magnitude, which is the only thing it exists to convey.
 */
class TreemapTest {

    private fun items(vararg v: Double) = v.mapIndexed { i, d -> TreemapItem("S$i", d) }

    @Test
    fun `total area equals the canvas`() {
        val tiles = Treemap.layout(items(100.0, 60.0, 40.0, 25.0, 12.0, 8.0, 3.0), 400f, 250f)
        val covered = tiles.sumOf { it.area.toDouble() }
        assertEquals(400.0 * 250.0, covered, 1.0)
    }

    @Test
    fun `each tile's area is proportional to its value`() {
        val vals = listOf(500.0, 300.0, 120.0, 50.0, 20.0, 10.0)
        val tiles = Treemap.layout(vals.mapIndexed { i, d -> TreemapItem("S$i", d) }, 600f, 400f)
        val total = vals.sum()
        val canvas = 600.0 * 400.0
        for (t in tiles) {
            val expected = (t.value / total) * canvas
            assertTrue(
                "area ${t.area} != expected $expected for value ${t.value}",
                abs(t.area - expected) / expected < 0.02,
            )
        }
    }

    @Test
    fun `no tile escapes the canvas`() {
        val tiles = Treemap.layout(items(90.0, 70.0, 55.0, 30.0, 22.0, 9.0, 4.0, 2.0), 320f, 200f)
        for (t in tiles) {
            assertTrue("negative size on ${t.key}", t.w > 0f && t.h > 0f)
            assertTrue("${t.key} off canvas", t.x >= -0.01f && t.y >= -0.01f)
            assertTrue("${t.key} overflows", t.x + t.w <= 320.01f && t.y + t.h <= 200.01f)
        }
    }

    @Test
    fun `tiles do not overlap`() {
        val tiles = Treemap.layout(items(100.0, 80.0, 60.0, 40.0, 30.0, 20.0, 10.0, 5.0), 500f, 300f)
        for (i in tiles.indices) for (j in i + 1 until tiles.size) {
            val a = tiles[i]; val b = tiles[j]
            val disjoint = a.x + a.w <= b.x + 0.01f || b.x + b.w <= a.x + 0.01f ||
                a.y + a.h <= b.y + 0.01f || b.y + b.h <= a.y + 0.01f
            assertTrue("${a.key} overlaps ${b.key}", disjoint)
        }
    }

    @Test
    fun `bigger values get bigger tiles`() {
        val tiles = Treemap.layout(items(300.0, 100.0, 40.0), 400f, 300f).associateBy { it.key }
        assertTrue(tiles["S0"]!!.area > tiles["S1"]!!.area)
        assertTrue(tiles["S1"]!!.area > tiles["S2"]!!.area)
    }

    @Test
    fun `shapes stay closer to square than a naive strip layout`() {
        // The whole point of squarifying. A single-row strip of 12 items in a 600x400 box gives an
        // aspect ratio around 12; squarified should be far better.
        val tiles = Treemap.layout((1..12).map { TreemapItem("S$it", (13 - it).toDouble()) }, 600f, 400f)
        val worst = tiles.maxOf { maxOf(it.w / it.h, it.h / it.w) }
        assertTrue("worst aspect ratio $worst is strip-like", worst < 6f)
    }

    // ---------------------------------------------------------------- degenerate input

    @Test
    fun `non-positive and non-finite values are dropped, not laid out`() {
        // A zero-value tile has zero area; a negative one would steal area from its neighbours and
        // corrupt every rectangle in the row.
        val tiles = Treemap.layout(
            listOf(TreemapItem("A", 10.0), TreemapItem("Z", 0.0), TreemapItem("N", -5.0),
                   TreemapItem("NAN", Double.NaN), TreemapItem("INF", Double.POSITIVE_INFINITY)),
            200f, 100f,
        )
        assertEquals(listOf("A"), tiles.map { it.key })
        assertEquals(200.0 * 100.0, tiles.sumOf { it.area.toDouble() }, 1.0)
    }

    @Test
    fun `an empty or zero-sized canvas yields nothing rather than crashing`() {
        assertTrue(Treemap.layout(items(1.0), 0f, 100f).isEmpty())
        assertTrue(Treemap.layout(items(1.0), 100f, 0f).isEmpty())
        assertTrue(Treemap.layout(items(1.0), -5f, 100f).isEmpty())
        assertTrue(Treemap.layout(emptyList(), 100f, 100f).isEmpty())
        assertTrue(Treemap.layout(items(0.0, 0.0), 100f, 100f).isEmpty())
    }

    @Test
    fun `a single item fills the whole canvas`() {
        val tiles = Treemap.layout(items(42.0), 300f, 200f)
        assertEquals(1, tiles.size)
        assertEquals(300f, tiles[0].w, 0.01f)
        assertEquals(200f, tiles[0].h, 0.01f)
    }

    @Test
    fun `wildly lopsided values still tile the canvas exactly`() {
        // One name at 99% of the total is the realistic case: a mega-cap next to small caps.
        val tiles = Treemap.layout(items(10_000.0, 5.0, 3.0, 1.0), 400f, 300f)
        assertEquals(400.0 * 300.0, tiles.sumOf { it.area.toDouble() }, 1.0)
        assertTrue(tiles.all { it.w > 0f && it.h > 0f })
    }

    @Test
    fun `layout is deterministic`() {
        val i = items(50.0, 30.0, 30.0, 20.0, 10.0)
        assertEquals(Treemap.layout(i, 300f, 200f), Treemap.layout(i, 300f, 200f))
    }

    @Test
    fun `equal values produce equal areas`() {
        val tiles = Treemap.layout(items(10.0, 10.0, 10.0, 10.0), 400f, 400f)
        val areas = tiles.map { it.area }
        assertTrue("equal values gave unequal areas: $areas",
            areas.maxOrNull()!! - areas.minOrNull()!! < 1f)
    }
}
