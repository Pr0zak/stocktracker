package com.stocktracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import kotlin.math.roundToInt

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
 * Detail/portfolio area chart. Supports:
 * - drag-to-scrub: touch and drag shows the value + time at that point (crosshair + dot).
 * - optional volume bars along the bottom.
 * - extended-hours segments (dashed + shaded band).
 */
@Composable
fun PriceChart(
    points: List<PricePoint>,
    up: Boolean,
    modifier: Modifier = Modifier,
    showVolume: Boolean = false,
    showHighLow: Boolean = false,
    valueFormatter: (Double) -> String = { it.toString() },
    timeFormatter: (Long) -> String = { "" },
) {
    val color = if (up) GainGreen else LossRed
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bandColor = muted.copy(alpha = 0.12f)
    val volColor = muted.copy(alpha = 0.28f)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = onSurface)

    var selected by remember(points) { mutableStateOf<Int?>(null) }

    Column(modifier) {
        // Scrub readout — fixed height so the chart doesn't jump when it appears.
        Box(Modifier.fillMaxWidth().height(20.dp)) {
            val i = selected
            if (i != null && i in points.indices) {
                Text(
                    "${valueFormatter(points[i].price)}   ${timeFormatter(points[i].epochMs)}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = onSurface,
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(points) {
                    if (points.size < 2) return@pointerInput
                    fun idx(px: Float): Int =
                        ((px / size.width.toFloat()) * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
                    // Horizontal drags scrub; vertical drags fall through to the parent scroll
                    // (detectHorizontalDragGestures only claims the gesture past horizontal slop).
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> selected = idx(offset.x) },
                        onDragEnd = { selected = null },
                        onDragCancel = { selected = null },
                    ) { change, _ ->
                        selected = idx(change.position.x)
                        change.consume()
                    }
                },
        ) {
            if (points.size < 2) return@Canvas
            val min = points.minOf { it.price }
            val max = points.maxOf { it.price }
            val range = (max - min).takeIf { it > 0.0 } ?: 1.0
            val stepX = size.width / (points.size - 1)
            fun x(i: Int) = i * stepX
            fun y(v: Double) = (1f - ((v - min) / range).toFloat()) * size.height

            // Volume bars along the bottom.
            if (showVolume) {
                val maxVol = points.maxOf { it.volume ?: 0.0 }
                if (maxVol > 0.0) {
                    val volArea = size.height * 0.28f
                    val bw = (stepX * 0.6f).coerceIn(1f, 6.dp.toPx())
                    points.forEachIndexed { i, p ->
                        val v = p.volume ?: 0.0
                        if (v > 0.0) {
                            val h = (v / maxVol).toFloat() * volArea
                            drawRect(volColor, topLeft = Offset(x(i) - bw / 2, size.height - h), size = Size(bw, h))
                        }
                    }
                }
            }

            // Extended-hours shaded spans.
            var i = 0
            while (i < points.size) {
                if (points[i].extended) {
                    var j = i
                    while (j < points.size && points[j].extended) j++
                    val startX = x((i - 1).coerceAtLeast(0))
                    val endX = x(j.coerceAtMost(points.size - 1))
                    drawRect(bandColor, topLeft = Offset(startX, 0f), size = Size((endX - startX).coerceAtLeast(1f), size.height))
                    i = j
                } else {
                    i++
                }
            }

            // Gradient fill under the line.
            val fill = Path().apply {
                moveTo(x(0), y(points[0].price))
                for (k in 1 until points.size) lineTo(x(k), y(points[k].price))
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent), 0f, size.height))

            // Line: regular solid, extended dashed + dimmed.
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

            // High / low markers — dot + value label at the extreme points.
            if (showHighLow && range > 0.0 && points.size >= 2) {
                val maxIdx = points.indices.maxByOrNull { points[it].price } ?: 0
                val minIdx = points.indices.minByOrNull { points[it].price } ?: 0
                val pad = 5.dp.toPx()

                fun marker(idx: Int, dotColor: Color, above: Boolean) {
                    val cx = x(idx)
                    val cy = y(points[idx].price)
                    val layout = textMeasurer.measure(valueFormatter(points[idx].price), labelStyle)
                    val tw = layout.size.width.toFloat()
                    val th = layout.size.height.toFloat()
                    val lx = (cx - tw / 2f).coerceIn(0f, (size.width - tw).coerceAtLeast(0f))
                    // Preferred side, flipped if it would clip the top/bottom edge.
                    var ly = if (above) cy - th - pad else cy + pad
                    if (ly < 0f) ly = cy + pad
                    if (ly > size.height - th) ly = cy - th - pad
                    drawRoundRect(
                        color = surface.copy(alpha = 0.78f),
                        topLeft = Offset(lx - 3f, ly - 1f),
                        size = Size(tw + 6f, th + 2f),
                        cornerRadius = CornerRadius(4f, 4f),
                    )
                    drawText(layout, topLeft = Offset(lx, ly))
                    drawCircle(dotColor, radius = 3.dp.toPx(), center = Offset(cx, cy))
                    drawCircle(surface, radius = 3.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.2.dp.toPx()))
                }

                marker(maxIdx, GainGreen, above = true)
                marker(minIdx, LossRed, above = false)
            }

            // Scrub crosshair + dot.
            selected?.let { sel ->
                if (sel in points.indices) {
                    val cx = x(sel)
                    val cy = y(points[sel].price)
                    drawLine(muted.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.dp.toPx())
                    drawCircle(color, radius = 4.5.dp.toPx(), center = Offset(cx, cy))
                    drawCircle(onSurface, radius = 4.5.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}
