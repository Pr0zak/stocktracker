package com.stocktracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed

/** Compact line-only sparkline for watchlist rows. */
@Composable
fun Sparkline(
    values: List<Double>,
    up: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (up) GainGreen else LossRed
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = (1f - ((v - min) / range).toFloat()) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * Detail area chart. Extended-hours (pre/post-market) points are drawn dashed + muted with a shaded
 * background band, so they're visually distinct from the regular session.
 */
@Composable
fun PriceChart(
    points: List<PricePoint>,
    up: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (up) GainGreen else LossRed
    val bandColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)

    Canvas(modifier.fillMaxSize()) {
        if (points.size < 2) return@Canvas
        val min = points.minOf { it.price }
        val max = points.maxOf { it.price }
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (points.size - 1)
        fun x(i: Int) = i * stepX
        fun y(v: Double) = (1f - ((v - min) / range).toFloat()) * size.height

        // 1) Shade contiguous extended-hours spans.
        var i = 0
        while (i < points.size) {
            if (points[i].extended) {
                var j = i
                while (j < points.size && points[j].extended) j++
                val startX = x((i - 1).coerceAtLeast(0))
                val endX = x((j).coerceAtMost(points.size - 1))
                drawRect(
                    color = bandColor,
                    topLeft = Offset(startX, 0f),
                    size = Size((endX - startX).coerceAtLeast(1f), size.height),
                )
                i = j
            } else {
                i++
            }
        }

        // 2) Gradient fill under the whole line.
        val fill = Path().apply {
            moveTo(x(0), y(points[0].price))
            for (k in 1 until points.size) lineTo(x(k), y(points[k].price))
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.30f), Color.Transparent),
                startY = 0f,
                endY = size.height,
            ),
        )

        // 3) Line, segment by segment: regular solid, extended dashed + dimmed.
        val dash = PathEffect.dashPathEffect(floatArrayOf(9f, 9f))
        for (k in 1 until points.size) {
            val a = points[k - 1]
            val b = points[k]
            val ext = a.extended || b.extended
            drawLine(
                color = if (ext) color.copy(alpha = 0.55f) else color,
                start = Offset(x(k - 1), y(a.price)),
                end = Offset(x(k), y(b.price)),
                strokeWidth = (if (ext) 2f else 3f).dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (ext) dash else null,
            )
        }
    }
}
