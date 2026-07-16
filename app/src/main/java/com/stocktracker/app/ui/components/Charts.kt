package com.stocktracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** An extra line drawn over the price chart (e.g. a moving average), aligned to the point indices. */
data class ChartLineOverlay(val label: String, val color: Color, val values: List<Double?>)

/** A dated vertical marker on the chart (e.g. an ex-dividend date), snapped to the nearest point. */
data class ChartMarker(val epochMs: Long, val color: Color, val label: String)

/**
 * A separate oscillator pane drawn below the price chart (e.g. RSI, MACD), sharing the x-axis and
 * zoom window. [lines] are value-series aligned to the point indices; [histogram] draws bars around
 * zero (MACD); [guides] are horizontal reference levels; [fixedRange] pins the y-scale (RSI = 0..100).
 */
data class ChartSubPane(
    val label: String,
    val lines: List<ChartLineOverlay>,
    val histogram: List<Double?>? = null,
    val guides: List<Double> = emptyList(),
    val fixedRange: ClosedFloatingPointRange<Double>? = null,
)

/** The [start, end] point indices currently visible given the zoom window (full range when not zoomed). */
private fun visibleRange(n: Int, winStart: Float, winSize: Float, zoomable: Boolean): IntRange {
    if (!zoomable || n < 4 || winSize >= 1f) return 0..(n - 1)
    val s = floor(winStart * (n - 1)).toInt().coerceIn(0, n - 2)
    val e = ceil((winStart + winSize) * (n - 1)).toInt().coerceIn(s + 1, n - 1)
    return s..e
}

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
    showReadout: Boolean = true,
    showAxis: Boolean = false,
    zoomable: Boolean = false,
    costLine: Double? = null,
    overlays: List<ChartLineOverlay> = emptyList(),
    subPanes: List<ChartSubPane> = emptyList(),
    markers: List<ChartMarker> = emptyList(),
    onScrubChange: (PricePoint?) -> Unit = {},
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

    var selected by remember(points) { mutableStateOf<Int?>(null) } // global index into points
    // Report the scrubbed point up so callers (e.g. the detail header) can react.
    LaunchedEffect(selected, points) { onScrubChange(selected?.let { points.getOrNull(it) }) }

    // Pinch-zoom window as fractions of the series [0,1]; full range when winSize == 1.
    val n = points.size
    val winStart = remember(points) { mutableFloatStateOf(0f) }
    val winSize = remember(points) { mutableFloatStateOf(1f) }
    val visRange = visibleRange(n, winStart.floatValue, winSize.floatValue, zoomable)
    val startIdx = visRange.first
    val endIdx = visRange.last

    Column(modifier) {
        // Scrub readout — fixed height so the chart doesn't jump when it appears.
        // Hidden when a caller owns the readout (showReadout = false).
        if (showReadout) {
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
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(points, zoomable) {
                    if (points.size < 2) return@pointerInput

                    // Map a touch x to a global point index within the current visible window.
                    fun idxAt(px: Float): Int {
                        val vr = visibleRange(n, winStart.floatValue, winSize.floatValue, zoomable)
                        val visN = vr.last - vr.first + 1
                        val local = ((px / size.width) * (visN - 1)).roundToInt().coerceIn(0, visN - 1)
                        return vr.first + local
                    }

                    if (!zoomable) {
                        // Horizontal drags scrub; vertical drags fall through to the parent scroll.
                        detectHorizontalDragGestures(
                            onDragStart = { selected = idxAt(it.x) },
                            onDragEnd = { selected = null },
                            onDragCancel = { selected = null },
                        ) { change, _ -> selected = idxAt(change.position.x); change.consume() }
                        return@pointerInput
                    }

                    // Zoomable: 1-finger horizontal drag scrubs; 2-finger pinch zooms + pans.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var mode = 0 // 0 = undecided, 1 = scrub, 2 = zoom/pan
                        var accX = 0f // accumulated 1-finger movement from the down, for slop
                        var accY = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            if (pressed >= 2) {
                                mode = 2
                                selected = null
                                val w = size.width.toFloat()
                                if (w > 0f && n > 1) {
                                    val zoom = event.calculateZoom().coerceIn(0.5f, 2f)
                                    val pan = event.calculatePan()
                                    val centroidX = event.calculateCentroid(useCurrent = true).x
                                    val minSize = (4f / (n - 1)).coerceIn(0.02f, 1f)
                                    val oldSize = winSize.floatValue
                                    val newSize = (oldSize / zoom).coerceIn(minSize, 1f)
                                    val focal = winStart.floatValue + (centroidX / w) * oldSize
                                    var newStart = focal - (centroidX / w) * newSize - (pan.x / w) * newSize
                                    newStart = newStart.coerceIn(0f, (1f - newSize).coerceAtLeast(0f))
                                    winSize.floatValue = newSize
                                    winStart.floatValue = newStart
                                }
                                event.changes.forEach { it.consume() }
                            } else if (pressed == 1 && mode != 2) {
                                val ch = event.changes.first { it.pressed }
                                if (mode == 0) {
                                    // Slop is on CUMULATIVE movement from the down, so slow drags count.
                                    accX += ch.position.x - ch.previousPosition.x
                                    accY += ch.position.y - ch.previousPosition.y
                                    if (abs(accX) > viewConfiguration.touchSlop && abs(accX) >= abs(accY)) mode = 1
                                    else if (abs(accY) > viewConfiguration.touchSlop && abs(accY) > abs(accX)) break // vertical → parent scroll
                                }
                                if (mode == 1) { selected = idxAt(ch.position.x); ch.consume() }
                            }
                        }
                        selected = null
                    }
                },
        ) {
            if (points.size < 2) return@Canvas
            val visN = endIdx - startIdx + 1
            if (visN < 2) return@Canvas

            // The x-axis lives in its own Canvas below (so it sits under any sub-panes), not in the plot.
            val plotBottom = size.height

            // y-range over the VISIBLE points (zoom rescales vertically too), plus the cost line.
            var dataMin = Double.MAX_VALUE
            var dataMax = -Double.MAX_VALUE
            for (k in startIdx..endIdx) {
                val p = points[k].price
                if (p < dataMin) dataMin = p
                if (p > dataMax) dataMax = p
            }
            // Fold visible overlay (MA) values in too, so zooming never pushes a line out of the plot.
            overlays.forEach { ov ->
                for (k in startIdx..endIdx) {
                    val v = ov.values.getOrNull(k) ?: continue
                    if (v < dataMin) dataMin = v
                    if (v > dataMax) dataMax = v
                }
            }
            val min = if (costLine != null) minOf(dataMin, costLine) else dataMin
            val max = if (costLine != null) maxOf(dataMax, costLine) else dataMax
            val range = (max - min).takeIf { it > 0.0 } ?: 1.0
            val stepX = size.width / (visN - 1)
            fun xg(i: Int) = (i - startIdx) * stepX
            fun y(v: Double) = (1f - ((v - min) / range).toFloat()) * plotBottom

            // Volume bars along the bottom of the plot.
            if (showVolume) {
                var maxVol = 0.0
                for (k in startIdx..endIdx) maxVol = maxOf(maxVol, points[k].volume ?: 0.0)
                if (maxVol > 0.0) {
                    val volArea = plotBottom * 0.28f
                    val bw = (stepX * 0.6f).coerceIn(1f, 6.dp.toPx())
                    for (k in startIdx..endIdx) {
                        val v = points[k].volume ?: 0.0
                        if (v > 0.0) {
                            val h = (v / maxVol).toFloat() * volArea
                            drawRect(volColor, topLeft = Offset(xg(k) - bw / 2, plotBottom - h), size = Size(bw, h))
                        }
                    }
                }
            }

            // Extended-hours shaded spans (within the visible window).
            var i = startIdx
            while (i <= endIdx) {
                if (points[i].extended) {
                    var j = i
                    while (j <= endIdx && points[j].extended) j++
                    val sx = xg((i - 1).coerceAtLeast(startIdx))
                    val ex = xg(j.coerceAtMost(endIdx))
                    drawRect(bandColor, topLeft = Offset(sx, 0f), size = Size((ex - sx).coerceAtLeast(1f), plotBottom))
                    i = j
                } else {
                    i++
                }
            }

            // Gradient fill under the line.
            val fill = Path().apply {
                moveTo(xg(startIdx), y(points[startIdx].price))
                for (k in (startIdx + 1)..endIdx) lineTo(xg(k), y(points[k].price))
                lineTo(size.width, plotBottom)
                lineTo(0f, plotBottom)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent), 0f, plotBottom))

            // Price line: solid, extended segments dashed + dimmed.
            val dash = PathEffect.dashPathEffect(floatArrayOf(9f, 9f))
            for (k in (startIdx + 1)..endIdx) {
                val a = points[k - 1]
                val b = points[k]
                val ext = a.extended || b.extended
                drawLine(
                    color = if (ext) color.copy(alpha = 0.55f) else color,
                    start = Offset(xg(k - 1), y(a.price)),
                    end = Offset(xg(k), y(b.price)),
                    strokeWidth = (if (ext) 2f else 3f).dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = if (ext) dash else null,
                )
            }

            // Overlay lines (e.g. moving averages), aligned to the point indices; nulls break the line.
            overlays.forEach { ov ->
                var started = false
                var px = 0f
                var py = 0f
                for (k in startIdx..endIdx) {
                    val v = ov.values.getOrNull(k)
                    if (v == null) { started = false; continue }
                    val cx = xg(k)
                    val cy = y(v)
                    if (started) drawLine(ov.color, Offset(px, py), Offset(cx, cy), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
                    px = cx; py = cy; started = true
                }
            }

            // Cost-basis reference line — dashed line at the user's average cost / total invested.
            if (costLine != null) {
                val cy = y(costLine)
                drawLine(
                    color = muted.copy(alpha = 0.7f),
                    start = Offset(0f, cy),
                    end = Offset(size.width, cy),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                val costLabel = textMeasurer.measure(
                    "Cost " + valueFormatter(costLine),
                    TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = muted),
                )
                val tw = costLabel.size.width.toFloat()
                val th = costLabel.size.height.toFloat()
                val lx = (size.width - tw - 3f).coerceAtLeast(0f)
                var ly = cy - th - 3f
                if (ly < 0f) ly = cy + 3f
                drawRoundRect(
                    color = surface.copy(alpha = 0.78f),
                    topLeft = Offset(lx - 3f, ly - 1f),
                    size = Size(tw + 6f, th + 2f),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                drawText(costLabel, topLeft = Offset(lx, ly))
            }

            // High / low markers over the visible extremes.
            if (showHighLow && range > 0.0) {
                var maxIdx = startIdx
                var minIdx = startIdx
                for (k in startIdx..endIdx) {
                    if (points[k].price > points[maxIdx].price) maxIdx = k
                    if (points[k].price < points[minIdx].price) minIdx = k
                }
                val pad = 5.dp.toPx()

                fun marker(idx: Int, dotColor: Color, above: Boolean) {
                    val cx = xg(idx)
                    val cy = y(points[idx].price)
                    val layout = textMeasurer.measure(valueFormatter(points[idx].price), labelStyle)
                    val tw = layout.size.width.toFloat()
                    val th = layout.size.height.toFloat()
                    val lx = (cx - tw / 2f).coerceIn(0f, (size.width - tw).coerceAtLeast(0f))
                    var ly = if (above) cy - th - pad else cy + pad
                    if (ly < 0f) ly = cy + pad
                    if (ly > plotBottom - th) ly = cy - th - pad
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

            // Dated markers (e.g. ex-dividend dates) — vertical line + a small tag at the bottom.
            if (markers.isNotEmpty()) {
                val loEpoch = points[startIdx].epochMs
                val hiEpoch = points[endIdx].epochMs
                markers.forEach { mk ->
                    if (mk.epochMs < loEpoch || mk.epochMs > hiEpoch) return@forEach
                    var best = startIdx
                    var bestD = Long.MAX_VALUE
                    for (k in startIdx..endIdx) {
                        val d = abs(points[k].epochMs - mk.epochMs)
                        if (d < bestD) { bestD = d; best = k }
                    }
                    val cx = xg(best)
                    drawLine(mk.color.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, plotBottom), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                    val tag = textMeasurer.measure(mk.label, TextStyle(fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = mk.color))
                    val tw = tag.size.width.toFloat()
                    val th = tag.size.height.toFloat()
                    val tx = (cx - tw / 2f).coerceIn(0f, (size.width - tw).coerceAtLeast(0f))
                    val ty = plotBottom - th - 2f
                    drawRoundRect(surface.copy(alpha = 0.82f), topLeft = Offset(tx - 2f, ty - 1f), size = Size(tw + 4f, th + 2f), cornerRadius = CornerRadius(3f, 3f))
                    drawText(tag, topLeft = Offset(tx, ty))
                }
            }

            // Overlay legend (top-left) — skip unlabeled lines (e.g. Bollinger bands).
            run {
                var lx = 2f
                overlays.forEach { ov ->
                    if (ov.label.isBlank()) return@forEach
                    val layout = textMeasurer.measure(ov.label, TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = ov.color))
                    val midY = 1f + layout.size.height / 2f
                    drawLine(ov.color, Offset(lx, midY), Offset(lx + 10f, midY), strokeWidth = 2f)
                    drawText(layout, topLeft = Offset(lx + 13f, 1f))
                    lx += 13f + layout.size.width + 12f
                }
            }

            // Scrub crosshair + dot (only when the point is in view).
            selected?.let { sel ->
                if (sel in startIdx..endIdx) {
                    val cx = xg(sel)
                    val cy = y(points[sel].price)
                    drawLine(muted.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, plotBottom), strokeWidth = 1.dp.toPx())
                    drawCircle(color, radius = 4.5.dp.toPx(), center = Offset(cx, cy))
                    drawCircle(onSurface, radius = 4.5.dp.toPx(), center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
                }
            }
        }

        // Oscillator sub-panes (RSI, MACD) — share the visible window + scrub crosshair.
        subPanes.forEach { sp ->
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(top = 4.dp),
            ) {
                if (points.size < 2) return@Canvas
                val vN = endIdx - startIdx + 1
                if (vN < 2) return@Canvas
                val stepX = size.width / (vN - 1)
                fun xg(k: Int) = (k - startIdx) * stepX

                var lo: Double
                var hi: Double
                if (sp.fixedRange != null) {
                    lo = sp.fixedRange.start; hi = sp.fixedRange.endInclusive
                } else {
                    lo = Double.MAX_VALUE; hi = -Double.MAX_VALUE
                    sp.lines.forEach { ln -> for (k in startIdx..endIdx) { val v = ln.values.getOrNull(k) ?: continue; if (v < lo) lo = v; if (v > hi) hi = v } }
                    sp.histogram?.let { h -> for (k in startIdx..endIdx) { val v = h.getOrNull(k) ?: continue; if (v < lo) lo = v; if (v > hi) hi = v } }
                    sp.guides.forEach { g -> if (g < lo) lo = g; if (g > hi) hi = g }
                    if (lo > hi) { lo = 0.0; hi = 1.0 }
                }
                val rng = (hi - lo).takeIf { it > 0.0 } ?: 1.0
                fun y(v: Double) = (1f - ((v - lo) / rng).toFloat()) * size.height

                sp.guides.forEach { g ->
                    val gy = y(g)
                    drawLine(muted.copy(alpha = 0.3f), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                    val gl = textMeasurer.measure(g.toInt().toString(), TextStyle(fontSize = 8.sp, color = muted))
                    drawText(gl, topLeft = Offset(size.width - gl.size.width - 2f, (gy - gl.size.height / 2f).coerceIn(0f, size.height - gl.size.height)))
                }

                sp.histogram?.let { h ->
                    val zeroY = y(0.0.coerceIn(lo, hi))
                    val bw = (stepX * 0.6f).coerceIn(1f, 5.dp.toPx())
                    for (k in startIdx..endIdx) {
                        val v = h.getOrNull(k) ?: continue
                        val vy = y(v)
                        val top = minOf(vy, zeroY)
                        val bot = maxOf(vy, zeroY)
                        drawRect(
                            (if (v >= 0) GainGreen else LossRed).copy(alpha = 0.5f),
                            topLeft = Offset(xg(k) - bw / 2, top),
                            size = Size(bw, (bot - top).coerceAtLeast(1f)),
                        )
                    }
                }

                sp.lines.forEach { ln ->
                    var started = false
                    var px = 0f
                    var py = 0f
                    for (k in startIdx..endIdx) {
                        val v = ln.values.getOrNull(k)
                        if (v == null) { started = false; continue }
                        val cx = xg(k)
                        val cy = y(v)
                        if (started) drawLine(ln.color, Offset(px, py), Offset(cx, cy), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
                        px = cx; py = cy; started = true
                    }
                }

                val lbl = textMeasurer.measure(sp.label, TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = muted))
                drawText(lbl, topLeft = Offset(2f, 1f))

                selected?.let { sel ->
                    if (sel in startIdx..endIdx) {
                        drawLine(muted.copy(alpha = 0.4f), Offset(xg(sel), 0f), Offset(xg(sel), size.height), strokeWidth = 1f)
                    }
                }
            }
        }

        // Shared x-axis date/time labels below the plot + any sub-panes.
        if (showAxis) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .padding(top = 2.dp),
            ) {
                if (points.size < 2) return@Canvas
                val vN = endIdx - startIdx + 1
                if (vN < 2) return@Canvas
                val stepX = size.width / (vN - 1)
                val ticks = 4
                val axisStyle = TextStyle(fontSize = 9.sp, color = muted)
                for (t in 0 until ticks) {
                    val gi = startIdx + ((endIdx - startIdx) * t) / (ticks - 1)
                    val label = timeFormatter(points[gi].epochMs)
                    if (label.isBlank()) continue
                    val layout = textMeasurer.measure(label, axisStyle)
                    val tw = layout.size.width.toFloat()
                    val lx = ((gi - startIdx) * stepX - tw / 2f).coerceIn(0f, (size.width - tw).coerceAtLeast(0f))
                    drawText(layout, topLeft = Offset(lx, 0f))
                }
            }
        }
    }
}
