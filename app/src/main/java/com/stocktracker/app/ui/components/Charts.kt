package com.stocktracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.stocktracker.app.util.barHigh
import com.stocktracker.app.util.barLow
import com.stocktracker.app.util.highIndexIn
import com.stocktracker.app.util.lowIndexIn
import com.stocktracker.app.util.volumeProfile
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/** An extra line drawn over the price chart (e.g. a moving average), aligned to the point indices. */
data class ChartLineOverlay(
    val label: String,
    val color: Color,
    val values: List<Double?>,
    /**
     * Draw as a dashed line. Shape is a SECOND channel alongside hue, which matters because roughly
     * 8% of men have red-green colour vision deficiency and the app's price line is green — a
     * benchmark drawn in a solid contrasting hue was relying on colour alone to be told apart. It
     * also says something true: a benchmark is a reference, not a gain or a loss, so it should not
     * be spending a semantic colour at all.
     */
    val dashed: Boolean = false,
)

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

/** How the price series is drawn. */
enum class ChartStyle {
    /** The close-to-close line with its gradient fill — every caller's default. */
    AREA,

    /**
     * Open/high/low/close candles.
     *
     * Offered rather than imposed because it is only legible at low bar counts, and because most of
     * this app's chart surfaces plot a synthetic series with no bars behind it at all — a portfolio
     * equity curve and a sandbox NAV have a value per day and no session to open, high or low.
     */
    CANDLE,
}

/**
 * Below this many device-independent pixels per bar a candle body is thinner than its own outline
 * and the plot reads as a smear rather than as sessions.
 *
 * 3dp is chosen, not derived: it is roughly the narrowest body that still resolves as a rectangle on
 * a phone. What matters is not the exact figure but that falling back is VISIBLE — a chart that
 * quietly turns back into a line has answered a different question than the one asked.
 */
private const val MIN_CANDLE_STEP_DP = 3f

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
    /**
     * Yesterday's close — the level the row's % change is measured FROM. Drawn as a faint dashed
     * baseline when supplied.
     *
     * Without it the line and the number can flatly contradict each other. The series is
     * `ChartRange.DAY`, scaled to its own min/max, so it starts at the first intraday print; the
     * percentage beside it is measured from the previous close. A day that opens below yesterday's
     * close and recovers therefore draws a line climbing left-to-right, in red, next to a negative
     * number. Observed live on BTC: a rising red sparkline beside "▼ −213.83 (−0.10%)". Both were
     * correct and the row looked broken. One dashed line at the baseline makes it legible — the
     * price rose all day and still finished under it.
     */
    previousClose: Double? = null,
) {
    val color = if (up) GainGreen else LossRed
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        // The baseline participates in the vertical scale. Left out of it, a close outside the
        // day's range would be clamped to an edge and silently misplaced by the very drawing meant
        // to explain the number.
        val lo = minOf(values.min(), previousClose ?: values.min())
        val hi = maxOf(values.max(), previousClose ?: values.max())
        val range = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        fun yOf(v: Double) = (1f - ((v - lo) / range).toFloat()) * size.height

        previousClose?.let { pc ->
            val y = yOf(pc)
            drawLine(
                color = baselineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(3.dp.toPx(), 3.dp.toPx()), 0f,
                ),
            )
        }

        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            if (i == 0) path.moveTo(0f, yOf(v)) else path.lineTo(i * stepX, yOf(v))
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
    sma200wLine: Double? = null,
    overlays: List<ChartLineOverlay> = emptyList(),
    subPanes: List<ChartSubPane> = emptyList(),
    markers: List<ChartMarker> = emptyList(),
    /** Shade the region between the running peak and the line wherever the series is below its own
     *  all-time high. Turns drawdown from a number the reader has to hold in their head into the
     *  shape of the curve. Only meaningful for a cumulative series (equity), never for a price. */
    shadeDrawdown: Boolean = false,
    /**
     * Defaults to [ChartStyle.AREA] deliberately. Four of this composable's five callers pass a
     * synthetic close-only series — a portfolio equity curve, two sandbox NAV curves and the VIX
     * history — where a candle would have to invent three of its four numbers.
     */
    style: ChartStyle = ChartStyle.AREA,
    /**
     * Map price logarithmically, so equal vertical distances are equal PERCENTAGE moves.
     *
     * On a linear axis a 40% drawdown at \$30 draws shorter than a 10% one at \$300, which is what
     * makes ALL and 3Y misread — and the %/\$ toggle does not substitute for it, because
     * `asPercentChange()` is an affine transform and this function autoscales, so percent mode is
     * pixel-identical to dollar mode with the numbers relabelled.
     *
     * Honoured only when the composed bounds are strictly positive; see the LOG note in the plot.
     */
    logScale: Boolean = false,
    /**
     * Draw a volume-at-price histogram in the right-hand third of the plot, with the point of
     * control and the value-area bounds as levels.
     *
     * Answers what the volume band along the bottom cannot: not WHEN volume happened, but at what
     * price. Absent whenever the visible window cannot support one — see [volumeProfile], which
     * refuses rather than degenerating.
     */
    showVolumeProfile: Boolean = false,
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
                // A separate detector from the slop-tuned gesture loop below, deliberately: that loop
                // arbitrates scrub against pinch on five call sites and is the last place to add a
                // third mode. detectTapGestures only claims a tap.
                .pointerInput(zoomable) {
                    if (!zoomable) return@pointerInput
                    detectTapGestures(onDoubleTap = {
                        winStart.floatValue = 0f
                        winSize.floatValue = 1f
                        selected = null
                    })
                }
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
                // Bar extremes count toward the scale ONLY when they will be drawn, or the plot
                // reserves headroom for a wick that never appears. Candles always draw them, and the
                // volume profile is binned across low..high, so both join the condition: without it
                // the top and bottom buckets clip flat against the plot edge.
                if (showHighLow || style == ChartStyle.CANDLE || showVolumeProfile) {
                    if (points[k].barLow() < dataMin) dataMin = points[k].barLow()
                    if (points[k].barHigh() > dataMax) dataMax = points[k].barHigh()
                }
            }
            // Fold visible overlay (MA) values in too, so zooming never pushes a line out of the plot.
            overlays.forEach { ov ->
                for (k in startIdx..endIdx) {
                    val v = ov.values.getOrNull(k) ?: continue
                    if (v < dataMin) dataMin = v
                    if (v > dataMax) dataMax = v
                }
            }
            val min = minOf(dataMin, costLine ?: dataMin, sma200wLine ?: dataMin)
            val max = maxOf(dataMax, costLine ?: dataMax, sma200wLine ?: dataMax)
            val range = (max - min).takeIf { it > 0.0 } ?: 1.0
            val stepX = size.width / (visN - 1)
            fun xg(i: Int) = (i - startIdx) * stepX

            // The positivity test is on the COMPOSED bounds, not on the price series. `min` above
            // already folds in the cost line, the 200-week line and every overlay — and Bollinger's
            // lower band (mid - 2*sd) goes at or below zero on a volatile sub-$5 ticker, while the
            // AI-analyst levels are arbitrary numbers from a backend. Testing the prices alone would
            // pass and then take ln of a negative.
            //
            // Re-evaluated here rather than hoisted, because `min` recomputes on every zoom: a
            // window that excludes the band's negative stretch is legitimately log-able and the next
            // pinch may not be.
            // Everything that has to TELL the reader something is collected here and stacked at the
            // bottom-left at the end. Three of them can co-occur — a zoomed 3Y chart in candle mode
            // with a Bollinger band below zero — and each drawing itself into the same corner would
            // overprint the others into an unreadable pile.
            val plotNotes = ArrayList<String>(3)
            val logOk = logScaleUsable(min, max, logScale)
            val lnMin = if (logOk) ln(min) else 0.0
            val lnSpan = if (logOk) (ln(max) - lnMin).takeIf { it > 0.0 } ?: 1.0 else 1.0
            fun y(v: Double) =
                if (logOk && v > 0.0) (1f - ((ln(v) - lnMin) / lnSpan).toFloat()) * plotBottom
                else (1f - ((v - min) / range).toFloat()) * plotBottom

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

            // Underwater shading: the area between the running peak and the curve, wherever the
            // series sits below its own high. Drawn BEFORE the line and its gradient so it reads as
            // background rather than as another series.
            if (shadeDrawdown && endIdx > startIdx) {
                // Running peak over the WHOLE series, not the visible window: a drawdown is measured
                // from the all-time high, and restarting the peak at the left edge of a zoomed view
                // would show a shallower one than the account actually suffered.
                val peaks = DoubleArray(points.size)
                var run = points[0].price
                for (k in points.indices) {
                    if (points[k].price > run) run = points[k].price
                    peaks[k] = run
                }
                val ddColor = LossRed.copy(alpha = 0.14f)
                var k = startIdx
                while (k <= endIdx) {
                    if (points[k].price < peaks[k]) {
                        var j = k
                        while (j <= endIdx && points[j].price < peaks[j]) j++
                        val seg = Path().apply {
                            moveTo(xg(k), y(peaks[k]))
                            for (m in k..(j - 1).coerceAtMost(endIdx)) lineTo(xg(m), y(peaks[m]))
                            for (m in (j - 1).coerceAtMost(endIdx) downTo k) lineTo(xg(m), y(points[m].price))
                            close()
                        }
                        drawPath(seg, ddColor)
                        k = j
                    } else {
                        k++
                    }
                }
            }

            // Volume at price, in the right-hand third of the plot. Drawn UNDER the price line and
            // the candles: it is context for them, not a series in its own right.
            if (showVolumeProfile) {
                val vp = volumeProfile(points, startIdx, endIdx)
                if (vp == null) {
                    plotNotes += "no volume profile — these bars carry no range or no volume"
                } else {
                    val maxRow = vp.rows.maxOf { it.total }
                    val gutter = size.width * 0.30f
                    if (maxRow > 0.0) {
                        vp.rows.forEach { r ->
                            val yTop = y(r.hi)
                            val yBot = y(r.lo)
                            val h = (yBot - yTop).coerceAtLeast(1f)
                            val w = (r.total / maxRow).toFloat() * gutter
                            if (w <= 0f) return@forEach
                            val inVa = r.mid in vp.valueAreaLow..vp.valueAreaHigh
                            // Inside the value area reads solid; outside it fades. The split is the
                            // information — a shelf is where the band is wide AND dark.
                            val up = GainGreen.copy(alpha = if (inVa) 0.34f else 0.14f)
                            val dn = LossRed.copy(alpha = if (inVa) 0.34f else 0.14f)
                            val upW = if (r.total > 0.0) w * (r.up / r.total).toFloat() else 0f
                            drawRect(up, topLeft = Offset(size.width - upW, yTop), size = Size(upW, h))
                            drawRect(
                                dn,
                                topLeft = Offset(size.width - w, yTop),
                                size = Size((w - upW).coerceAtLeast(0f), h),
                            )
                        }
                    }
                    // The three levels, drawn across the full plot so they can be read against price.
                    val pocY = y(vp.poc)
                    drawLine(
                        muted.copy(alpha = 0.75f), Offset(0f, pocY), Offset(size.width, pocY),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                    )
                    val pocLbl = textMeasurer.measure(
                        "POC " + valueFormatter(vp.poc),
                        TextStyle(fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = muted),
                    )
                    drawText(pocLbl, topLeft = Offset(2f, (pocY - pocLbl.size.height - 1f).coerceAtLeast(0f)))
                    listOf(vp.valueAreaHigh, vp.valueAreaLow).forEach { lvl ->
                        val ly = y(lvl)
                        drawLine(
                            muted.copy(alpha = 0.28f), Offset(0f, ly), Offset(size.width, ly),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)),
                        )
                    }
                    // The method is disclosed, not buried: these numbers do not reconcile with a
                    // TradingView chart, which profiles from lower-timeframe intrabar data.
                    plotNotes += "volume profile · ${vp.method}"
                }
            }

            // Candles, when asked for AND when the bars are wide enough to read as bodies. The
            // fallback is announced in the plot rather than taken silently: a chart that quietly
            // turns back into a line has answered a different question than the one asked, which is
            // the same reason the dip radar names why it found nothing instead of showing an empty
            // list.
            val candleStepPx = MIN_CANDLE_STEP_DP.dp.toPx()
            val drawCandles = style == ChartStyle.CANDLE && stepX >= candleStepPx
            if (style == ChartStyle.CANDLE && !drawCandles) {
                plotNotes += "$visN bars — too many to draw as candles; showing the close line"
            }
            if (drawCandles) {
                val bodyW = (stepX * 0.7f).coerceIn(1f, 10.dp.toPx())
                val wickW = 1.dp.toPx()
                for (k in startIdx..endIdx) {
                    val pt = points[k]
                    val o = pt.open
                    val h = pt.high
                    val l = pt.low
                    // A bar missing any of O/H/L is SKIPPED, not filled from its close. Substituting
                    // the close would draw a doji — a session that opened and closed at the same
                    // price after fighting to a standstill — which is a specific and confident claim
                    // about a bar whose source told us nothing. history() drops a bar only on a null
                    // CLOSE, so a rendered bar really can carry a null open.
                    if (o == null || h == null || l == null) continue
                    val c = pt.price
                    val cx = xg(k)
                    val rising = c >= o
                    val bodyColor = if (rising) GainGreen else LossRed
                    drawLine(
                        bodyColor, Offset(cx, y(h)), Offset(cx, y(l)),
                        strokeWidth = wickW, cap = StrokeCap.Butt,
                    )
                    val top = minOf(y(o), y(c))
                    val bot = maxOf(y(o), y(c))
                    drawRect(
                        bodyColor,
                        topLeft = Offset(cx - bodyW / 2f, top),
                        // A true doji has zero body height and would otherwise vanish entirely.
                        size = Size(bodyW, (bot - top).coerceAtLeast(wickW)),
                    )
                }
            }

            // Gradient fill under the line. Suppressed under candles: it is a close-line construct,
            // and shading the area under a series of discrete sessions asserts a path between them
            // that the bars exist precisely to deny.
            val fill = Path().apply {
                moveTo(xg(startIdx), y(points[startIdx].price))
                for (k in (startIdx + 1)..endIdx) lineTo(xg(k), y(points[k].price))
                lineTo(size.width, plotBottom)
                lineTo(0f, plotBottom)
                close()
            }
            if (!drawCandles) {
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent), 0f, plotBottom))
            }

            // Price line: solid, extended segments dashed + dimmed. Also a close-line construct, and
            // the dashed extended-hours segments have no candle equivalent — a pre-market bar is a
            // bar like any other, and its session is carried by `extended` in the scrub readout.
            val dash = PathEffect.dashPathEffect(floatArrayOf(9f, 9f))
            for (k in (startIdx + 1)..endIdx) {
                if (drawCandles) break
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
                    if (started) drawLine(
                        ov.color, Offset(px, py), Offset(cx, cy),
                        strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = if (ov.dashed) {
                            PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
                        } else {
                            null
                        },
                    )
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

            // 200-week line — amber dashed reference so you can see price cross it on long ranges.
            if (sma200wLine != null) {
                val ly200 = y(sma200wLine)
                val amber = Color(0xFFD29922)
                drawLine(
                    color = amber.copy(alpha = 0.85f),
                    start = Offset(0f, ly200),
                    end = Offset(size.width, ly200),
                    strokeWidth = 1.4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)),
                )
                val lbl = textMeasurer.measure(
                    "200-wk " + valueFormatter(sma200wLine),
                    TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = amber),
                )
                val tw2 = lbl.size.width.toFloat()
                val th2 = lbl.size.height.toFloat()
                var ly2 = ly200 + 3f
                if (ly2 + th2 > plotBottom) ly2 = ly200 - th2 - 3f
                drawRoundRect(
                    color = surface.copy(alpha = 0.78f),
                    topLeft = Offset(1f, ly2 - 1f),
                    size = Size(tw2 + 6f, th2 + 2f),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                drawText(lbl, topLeft = Offset(4f, ly2))
            }

            // High / low markers over the visible extremes.
            if (showHighLow && range > 0.0) {
                // Bar extremes, not closes — see highIndexIn / PricePoint.high for why the two
                // disagree and why only one of them nests across ranges.
                val maxIdx = highIndexIn(points, startIdx, endIdx)
                val minIdx = lowIndexIn(points, startIdx, endIdx)
                val pad = 5.dp.toPx()

                fun marker(idx: Int, dotColor: Color, above: Boolean) {
                    val value = if (above) points[idx].barHigh() else points[idx].barLow()
                    val cx = xg(idx)
                    val cy = y(value)
                    val layout = textMeasurer.measure(valueFormatter(value), labelStyle)
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

                if (maxIdx >= 0) marker(maxIdx, GainGreen, above = true)
                if (minIdx >= 0) marker(minIdx, LossRed, above = false)
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

            // Price labels. Until now the plot carried NO price on its axis at all — every number on
            // it was attached to an opt-in feature (the high/low chips, the cost chip, the 200-week
            // chip, the AI levels in the legend), so a chart with none of those enabled could not be
            // read for a level at any height.
            //
            // Drawn as inset chips rather than in a right-hand gutter: the x-axis Canvas and every
            // sub-pane compute their own stepX from their own full width, so narrowing only the price
            // plot would silently desync the date labels and the sub-pane crosshairs from the bars
            // they annotate.
            //
            // Tick VALUES are spaced evenly through the value range (geometrically when log), which
            // is why there is no coordinate-to-price inverse anywhere in this file and no
            // nice-number generator: a 1-2-5x10^n generator yields at most one tick inside a
            // $180-$220 window, and an inverse mapping is precisely the machinery the scrub path
            // deliberately avoids by reading points[i].price straight from the data.
            if (showAxis) {
                val tickStyle = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = muted)
                val nTicks = 4
                // Levels that already carry their own labelled chip; a tick landing on one would
                // print the same number twice, or worse, a slightly different one.
                val claimed = listOfNotNull(costLine, sma200wLine).map { y(it) }
                axisTickValues(min, max, logOk, nTicks).forEach { v ->
                    val ty = y(v)
                    if (claimed.any { abs(it - ty) < 10.dp.toPx() }) return@forEach
                    val lay = textMeasurer.measure(valueFormatter(v), tickStyle)
                    val ly = (ty - lay.size.height / 2f).coerceIn(0f, plotBottom - lay.size.height)
                    drawLine(
                        muted.copy(alpha = 0.12f),
                        Offset(0f, ty), Offset(size.width - lay.size.width - 6f, ty),
                        strokeWidth = 1f,
                    )
                    drawText(lay, topLeft = Offset(size.width - lay.size.width - 2f, ly))
                }
            }

            // A refused log scale says why. Silently drawing a linear axis under a control labelled
            // LOG is the same defect as any other confident wrong answer on this screen.
            if (logScale && !logOk) {
                plotNotes += "log scale needs positive values — a drawn level reaches ${valueFormatter(min)}"
            }
            // Zoom is otherwise a one-way door: nothing on screen says how to get back out of it.
            if (zoomable && winSize.floatValue < 1f) plotNotes += "double-tap to reset zoom"

            run {
                // Each note gets a surface-coloured plate behind it. The notes share the bottom-left
                // corner with the y-axis low label, the scrub readout and the dividend markers, and
                // drawn bare they were struck through by all three — the "too many bars to draw as
                // candles" line, which exists precisely to explain why the chart is not doing what
                // was asked, was the least legible thing on the plot. A plate is used rather than a
                // different corner because every corner of a price chart is occupied by something,
                // and only this one is occupied by things a reader can afford to have covered.
                val noteStyle = TextStyle(fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = muted)
                var ny = plotBottom - 3f
                plotNotes.asReversed().forEach { text ->
                    val lay = textMeasurer.measure(text, noteStyle)
                    ny -= lay.size.height + 2f
                    if (ny < 0f) return@forEach
                    drawRoundRect(
                        color = surface.copy(alpha = 0.88f),
                        topLeft = Offset(0f, ny - 1f),
                        size = Size(lay.size.width + 6f, lay.size.height + 2f),
                        cornerRadius = CornerRadius(3f, 3f),
                    )
                    drawText(lay, topLeft = Offset(3f, ny))
                }
            }

            // Overlay legend (top-left) — skip unlabeled lines (e.g. Bollinger bands).
            run {
                var lx = 2f
                overlays.forEach { ov ->
                    if (ov.label.isBlank()) return@forEach
                    val layout = textMeasurer.measure(ov.label, TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = ov.color))
                    val midY = 1f + layout.size.height / 2f
                    // The legend swatch carries the dash too — otherwise the key claims a solid
                    // line for something drawn dashed, which is the one place a legend must not lie.
                    drawLine(
                        ov.color, Offset(lx, midY), Offset(lx + 10f, midY), strokeWidth = 2f,
                        pathEffect = if (ov.dashed) PathEffect.dashPathEffect(floatArrayOf(3f, 2f)) else null,
                    )
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

                // Values for the scrubbed bar, or the newest visible bar at rest.
                //
                // Until now this pane drew a crosshair with no number attached: with RSI and MACD
                // enabled you could put a finger on a bar and still not learn what RSI was, because
                // the only numeric anchors were the 30/70 guide labels — a ±5 read by eye. "What was
                // RSI on the day I bought" was unanswerable in the app despite the pane being right
                // there.
                //
                // Drawn into the Canvas rather than composed: the caller sizes this chart with fixed
                // arithmetic over the pane count (see chartHeight in DetailScreen), and a composed
                // Text would add height that arithmetic does not know about. Laid out after the pane
                // label on the same line rather than at the right edge, where the guide labels live.
                val readIdx = selected?.takeIf { it in startIdx..endIdx } ?: endIdx
                var cursorX = 2f + lbl.size.width + 8f
                sp.lines.forEach { ln ->
                    if (ln.label.isBlank()) return@forEach
                    // A warm-up bar has no value. It prints as an em dash — never 0, and never the
                    // last non-null value carried forward, either of which would read as a
                    // measurement taken on a bar where none exists.
                    val v = ln.values.getOrNull(readIdx)
                    val text = ln.label + " " + formatPaneValue(v)
                    val layout = textMeasurer.measure(
                        text,
                        TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = ln.color),
                    )
                    // Clip rather than overlap: a narrow pane drops the trailing series instead of
                    // painting two numbers on top of each other.
                    if (cursorX + layout.size.width > size.width - 2f) return@forEach
                    drawText(layout, topLeft = Offset(cursorX, 1f))
                    cursorX += layout.size.width + 8f
                }

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

/**
 * Format an oscillator value for a sub-pane readout.
 *
 * Null is an em dash, never 0.0 and never the previous bar's value: an indicator inside its warm-up
 * has no reading, and printing a number there would present the absence of a measurement as a
 * measurement. Precision adapts because the panes are not on one scale — RSI and the stochastic run
 * 0..100 where a MACD line on a $20 stock lives in hundredths.
 */
internal fun formatPaneValue(v: Double?): String {
    if (v == null || v.isNaN() || v.isInfinite()) return "—"
    val a = kotlin.math.abs(v)
    return when {
        a >= 100.0 -> String.format(java.util.Locale.US, "%.0f", v)
        a >= 10.0 -> String.format(java.util.Locale.US, "%.1f", v)
        a >= 1.0 -> String.format(java.util.Locale.US, "%.2f", v)
        else -> String.format(java.util.Locale.US, "%.3f", v)
    }
}

/**
 * Whether a logarithmic price axis can be drawn over these bounds.
 *
 * [min] and [max] must be the COMPOSED bounds — the ones that already fold in the cost line, the
 * 200-week line and every overlay — not the price series. Bollinger's lower band is `mid - 2*sd` and
 * goes at or below zero on a volatile sub-$5 ticker, and the AI-analyst levels are arbitrary numbers
 * from a backend. Testing the prices alone would pass and then take the log of a negative.
 *
 * Refusal is a reason to say so, never a reason to quietly draw a linear axis under a LOG control.
 */
internal fun logScaleUsable(min: Double, max: Double, requested: Boolean): Boolean =
    requested && min > 0.0 && max > min

/**
 * The values to label the price axis with: [n] of them, evenly spaced through the range — and
 * evenly spaced in the LOG of the range when [log], so that equal spacing on screen is equal
 * spacing in the labels.
 *
 * Deliberately not a 1-2-5x10^n "nice number" generator. Such a generator yields at most one tick
 * inside a $180-$220 window, which is the shape of most single-stock charts here. Deliberately not
 * derived from an inverse of the y-mapping either: no coordinate-to-price inverse exists anywhere in
 * this file, and the scrub path avoids needing one by reading points[i].price straight from the data.
 */
internal fun axisTickValues(min: Double, max: Double, log: Boolean, n: Int): List<Double> {
    if (n < 2 || !(max > min)) return emptyList()
    if (log && min <= 0.0) return emptyList()
    val lnMin = if (log) ln(min) else 0.0
    val lnSpan = if (log) ln(max) - lnMin else 0.0
    return (0 until n).map { t ->
        val f = t.toDouble() / (n - 1)
        if (log) exp(lnMin + lnSpan * f) else min + (max - min) * f
    }
}
