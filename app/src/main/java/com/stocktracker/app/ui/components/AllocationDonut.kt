package com.stocktracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** Distinct slice colours, cycled by position rank (largest first). Shared so the Portfolio and the
 *  Sandbox colour the same holding consistently. */
val DONUT_COLORS = listOf(
    Color(0xFF7C6BD6), Color(0xFF4666CF), Color(0xFF0F8A7E), Color(0xFFD29922),
    Color(0xFFB0543D), Color(0xFFC2477E), Color(0xFF2E9E57), Color(0xFF8A6BB0),
)

/** A thin allocation donut — one arc per position, swept by its share of the book. [slices] are
 *  (colour, fraction-of-total) pairs; fractions should sum to <= 1. */
@Composable
fun AllocationDonut(slices: List<Pair<Color, Float>>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.18f
        val d = size.minDimension - stroke
        val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        val arc = Size(d, d)
        var start = -90f
        slices.forEach { (color, frac) ->
            val sweep = frac * 360f
            drawArc(
                color = color,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = tl,
                size = arc,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            start += sweep
        }
    }
}
