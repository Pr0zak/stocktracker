package com.stocktracker.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/** Renders a sparkline to a Bitmap for use inside Glance widgets (which can't draw canvases directly). */
object SparklineRenderer {

    fun render(
        values: List<Double>,
        widthPx: Int = 320,
        heightPx: Int = 96,
        colorArgb: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (values.size < 2) return bmp

        val min = values.min()
        val max = values.max()
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
}
