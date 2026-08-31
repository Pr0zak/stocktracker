package com.stocktracker.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path

/** Renders a sparkline to a Bitmap for use inside Glance widgets (which can't draw canvases directly). */
object SparklineRenderer {

    /**
     * @param previousClose Yesterday's close — the level the widget's % change is measured FROM.
     *   Drawn as a faint dashed baseline when supplied, and folded into the vertical scale so it
     *   cannot be clamped to an edge and silently misplaced.
     *
     *   Without it the line and the number beside it can flatly contradict each other. The series
     *   is intraday, scaled to its own min/max, so it starts at the first print of the day; the
     *   percentage above it is measured from the previous close. A day that opens below yesterday's
     *   close and recovers therefore draws a line climbing left-to-right, in red, next to a
     *   negative number. The in-app sparkline fixed exactly this once already — see the
     *   `previousClose` KDoc on `Sparkline` in ui/components/Charts.kt, which records the live BTC
     *   case. Null means the reference is unknown: draw no line rather than substitute `values[0]`,
     *   which would assert a baseline the data does not support.
     */
    fun render(
        values: List<Double>,
        widthPx: Int = 320,
        heightPx: Int = 96,
        colorArgb: Int,
        previousClose: Double? = null,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (values.size < 2) return bmp

        val baseline = previousClose?.takeIf { it.isFinite() }
        val (min, max) = verticalBounds(values, baseline)
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0
        val pad = heightPx * 0.12f
        val usableH = heightPx - pad * 2

        fun x(i: Int) = i.toFloat() / (values.size - 1) * widthPx
        fun y(v: Double) = pad + (1f - ((v - min) / range).toFloat()) * usableH

        val linePath = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) lineTo(x(i), y(values[i]))
        }

        // Soft gradient-style fill under the line (flat alpha fill; Glance scales the bitmap).
        val fillPath = Path(linePath).apply {
            lineTo(widthPx.toFloat(), heightPx.toFloat())
            lineTo(0f, heightPx.toFloat())
            close()
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = (colorArgb and 0x00FFFFFF) or (0x33 shl 24) // ~20% alpha
        }
        canvas.drawPath(fillPath, fillPaint)

        // Drawn after the fill so the accent tint doesn't wash it out, and before the price line so
        // the line stays on top where the two cross. Dash lengths scale with widthPx because the
        // bitmap is fitted into the row's width, not blitted 1:1.
        baseline?.let { pc ->
            val dash = widthPx * 0.0125f
            val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = heightPx * 0.02f
                color = BASELINE_ARGB
                pathEffect = DashPathEffect(floatArrayOf(dash, dash), 0f)
            }
            val yBase = y(pc)
            canvas.drawLine(0f, yBase, widthPx.toFloat(), yBase, basePaint)
        }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = heightPx * 0.05f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = colorArgb
        }
        canvas.drawPath(linePath, linePaint)
        return bmp
    }

    /**
     * The vertical extent the sparkline is drawn against. Split out because it is the one piece of
     * this renderer a JVM test can reach — `Bitmap` and `Canvas` cannot be instantiated without
     * Robolectric, which this module does not have.
     *
     * The baseline participates in the scale. Left out of it, a previous close outside the day's
     * range would be clamped to an edge and silently misplaced by the very line meant to explain
     * the number beside it.
     */
    internal fun verticalBounds(values: List<Double>, baseline: Double?): Pair<Double, Double> =
        minOf(values.min(), baseline ?: values.min()) to
            maxOf(values.max(), baseline ?: values.max())

    /**
     * The widget's own muted grey at ~45% alpha. Hardcoded because this object has no Context and
     * the widget background is a fixed dark (`#1C1B21`, no values-night variant), so there is no
     * light-theme case to resolve against.
     */
    private const val BASELINE_ARGB = 0x73CAC4D3.toInt()
}
