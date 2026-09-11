package com.stocktracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.stocktracker.app.ui.theme.EtfAccent
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.Signal
import com.stocktracker.app.ui.theme.CategoricalRamp

/** Distinct slice colours, cycled by position rank (largest first). Shared so the Portfolio and the
 *  Sandbox colour the same holding consistently. */
// Was a grab-bag of literals mixed with the class accents. After the tokens landed, the accents in
// it resolved to GainGreen and LossRed, so a holding's slice could be drawn in the ink that means
// "this went down" — a category colour asserting a verdict. The ramp spends none of those.
val DONUT_COLORS = CategoricalRamp

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
