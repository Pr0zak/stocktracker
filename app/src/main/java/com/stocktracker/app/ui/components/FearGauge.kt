package com.stocktracker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.model.VixQuote
import com.stocktracker.app.data.model.VixZone
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import java.util.Locale
import kotlin.math.abs

// Green → red risk palette. Note the inversion vs a normal ticker: a HIGH reading is the "bad" end.
private fun VixZone.color(): Color = when (this) {
    VixZone.CALM -> Color(0xFF4ADE80)
    VixZone.NORMAL -> Color(0xFFFACC15)
    VixZone.ELEVATED -> Color(0xFFF59E0B)
    VixZone.HIGH -> Color(0xFFFB923C)
    VixZone.EXTREME -> Color(0xFFF87171)
}

// Gauge spans 0..45; readings above cap at the right edge (Extreme).
private const val GAUGE_MAX = 45.0
private val BOUNDS = doubleArrayOf(0.0, 15.0, 20.0, 30.0, 40.0, GAUGE_MAX)

/**
 * Compact dashboard "market fear" gauge for the VIX. Coloring is inverted from a normal ticker —
 * high volatility (fear) is red, a falling VIX (calming) is green — so the card reads as sentiment.
 */
@Composable
fun FearGauge(vix: VixQuote, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val zone = vix.zone
    val zoneColor = zone.color()

    val cardModifier = modifier.fillMaxWidth()
    val cardShape = RoundedCornerShape(20.dp)
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Market Fear · VIX",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        String.format(Locale.US, "%.2f", vix.value),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    val arrow = if (vix.change <= 0.0) "▼" else "▲"
                    Text(
                        "$arrow ${String.format(Locale.US, "%.2f", abs(vix.change))} " +
                            "(${String.format(Locale.US, "%.2f", abs(vix.changePercent))}%)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        // Inverted: a lower VIX (calmer) is the "good" green; higher is the "bad" red.
                        color = if (vix.calmer) GainGreen else LossRed,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                Text(
                    zone.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = zoneColor,
                    modifier = Modifier
                        .background(zoneColor.copy(alpha = 0.18f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }

            // Bar + numeric scale.
            //
            // The bands and the "Calm/Elevated/…" pill already carry the zone without relying on
            // hue, but the READING had no reference: a knob three-fifths along a five-colour ramp
            // only says "20" if you can tell amber from orange. Boundary ticks with their values
            // make the position legible numerically, band separators make the zones countable, and
            // the pointer adds shape — so nothing here depends on distinguishing adjacent hues,
            // which is exactly what red-green colour vision deficiency takes away.
            val measurer = rememberTextMeasurer()
            val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
            val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(26.dp),
            ) {
                val h = 10.dp.toPx()
                val r = h / 2f
                val track = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, size.width, h, CornerRadius(r, r)))
                }
                clipPath(track) {
                    for (i in 0 until BOUNDS.size - 1) {
                        val x0 = (BOUNDS[i] / GAUGE_MAX).toFloat() * size.width
                        val x1 = (BOUNDS[i + 1] / GAUGE_MAX).toFloat() * size.width
                        drawRect(
                            color = VixZone.entries[i].color(),
                            topLeft = Offset(x0, 0f),
                            size = Size(x1 - x0, h),
                        )
                        // Hairline between bands so they stay countable when the hues blur together.
                        if (i > 0) {
                            drawLine(
                                surfaceColor, Offset(x0, 0f), Offset(x0, h),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                        }
                    }
                }

                // Boundary values under the bar — 15 / 20 / 30 / 40, the zone edges themselves.
                for (i in 1 until BOUNDS.size - 1) {
                    val bx = (BOUNDS[i] / GAUGE_MAX).toFloat() * size.width
                    val lab = measurer.measure(
                        BOUNDS[i].toInt().toString(),
                        TextStyle(fontSize = 8.sp, color = tickColor),
                    )
                    drawText(
                        lab,
                        topLeft = Offset(
                            (bx - lab.size.width / 2f).coerceIn(0f, size.width - lab.size.width),
                            h + 5.dp.toPx(),
                        ),
                    )
                }

                // Pointer: a triangle above the knob. Shape, not just position on a colour ramp.
                val frac = (vix.value / GAUGE_MAX).coerceIn(0.0, 1.0).toFloat()
                val cx = (frac * size.width).coerceIn(r, size.width - r)
                drawCircle(Color.White, radius = r * 1.05f, center = Offset(cx, h / 2f))
                drawCircle(zoneColor, radius = r * 1.05f, center = Offset(cx, h / 2f), style = Stroke(2.dp.toPx()))
                val tip = 3.5.dp.toPx()
                drawPath(
                    Path().apply {
                        moveTo(cx, h + 1.dp.toPx())
                        lineTo(cx - tip, h + 1.dp.toPx() + tip)
                        lineTo(cx + tip, h + 1.dp.toPx() + tip)
                        close()
                    },
                    color = tickColor,
                )
            }
        }
    }

    if (onClick != null) {
        Card(onClick = onClick, shape = cardShape, colors = cardColors, modifier = cardModifier) { body() }
    } else {
        Card(shape = cardShape, colors = cardColors, modifier = cardModifier) { body() }
    }
}
