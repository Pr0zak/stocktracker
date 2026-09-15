package com.stocktracker.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the widget card. The drawing itself needs a real Canvas, so what is covered
 * here is the part that can silently be wrong without looking wrong: the sense of the transparency
 * value, and the scaling that keeps a stretched bitmap's corners circular.
 */
class WidgetBackgroundTest {

    @Test
    fun `zero transparency is fully opaque and 100 is fully clear`() {
        assertEquals(255, WidgetBackground.alphaFor(0))
        assertEquals(0, WidgetBackground.alphaFor(100))
    }

    @Test
    fun `transparency runs the right way round`() {
        // The stored number is the transparency the user sees, not the opacity. Inverting this is
        // the one bug in this file that would still produce a plausible-looking widget.
        assertTrue(WidgetBackground.alphaFor(25) > WidgetBackground.alphaFor(75))
        assertEquals(127, WidgetBackground.alphaFor(50))
    }

    @Test
    fun `out of range transparency is clamped, not wrapped`() {
        assertEquals(255, WidgetBackground.alphaFor(-10))
        assertEquals(0, WidgetBackground.alphaFor(140))
    }

    @Test
    fun `argbWith replaces the alpha and preserves the colour`() {
        val opaque = WidgetBackground.argbWith(0xFF1C1B21L, 0)
        assertEquals(0xFF1C1B21.toInt(), opaque)

        val half = WidgetBackground.argbWith(0xFF1C1B21L, 50)
        assertEquals(0x1C1B21, half and 0x00FFFFFF)
        assertEquals(127, (half ushr 24) and 0xFF)

        // An input whose own alpha disagrees must not leak through.
        val fromTranslucentInput = WidgetBackground.argbWith(0x801C1B21L, 0)
        assertEquals(0xFF, (fromTranslucentInput ushr 24) and 0xFF)
    }

    @Test
    fun `small widgets are rendered at their true size`() {
        assertEquals(1f, WidgetBackground.scaleFor(200, 100), 0f)
    }

    @Test
    fun `large widgets are scaled down to the pixel budget`() {
        val w = 1200
        val h = 600
        val k = WidgetBackground.scaleFor(w, h)
        assertTrue("expected a reduction, got $k", k < 1f)

        val area = (w * k) * (h * k)
        assertTrue("scaled area $area exceeds the budget", area <= 90_000f * 1.01f)

        // Uniform in both axes: a non-uniform scale would stretch back with oval corners.
        val aspectBefore = w.toDouble() / h
        val aspectAfter = (w * k).toDouble() / (h * k)
        assertEquals(aspectBefore, aspectAfter, 1e-6)
    }

    @Test
    fun `a degenerate size does not divide by zero`() {
        assertEquals(1f, WidgetBackground.scaleFor(0, 0), 0f)
    }

    @Test
    fun `the default choice is in the palette and is the drawable's colour`() {
        assertTrue(WidgetBackground.COLOR_CHOICES.any { it.second == WidgetBackground.DEFAULT_ARGB })
        // res/drawable/widget_background.xml is #1C1B21. The default path still uses that drawable,
        // so a drift between the two would make the "Charcoal" swatch a no-op that looks selected.
        assertEquals(0xFF1C1B21L, WidgetBackground.DEFAULT_ARGB)
    }

    @Test
    fun `every palette colour is opaque and dark`() {
        for ((name, argb) in WidgetBackground.COLOR_CHOICES) {
            assertEquals("$name must carry a full alpha", 0xFFL, (argb ushr 24) and 0xFFL)
            val r = ((argb ushr 16) and 0xFFL).toDouble()
            val g = ((argb ushr 8) and 0xFFL).toDouble()
            val b = (argb and 0xFFL).toDouble()
            // Rec. 709 luma. The widgets' text and their gain/loss inks are the dark theme's, and
            // this app has already measured those against a light surface: 1.66:1 and 2.63:1. A
            // pale swatch here would reintroduce that, so the palette must stay dark by test.
            val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
            assertTrue("$name is too light for the widget's fixed light text (luma $luma)", luma < 64.0)
        }
    }
}
