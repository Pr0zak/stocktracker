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

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            ) {
                val h = size.height
                val r = h / 2f
                val track = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, size.width, h, CornerRadius(r, r)))
                }
                // Zone segments, clipped to the rounded pill so the ends round cleanly.
                clipPath(track) {
                    for (i in 0 until BOUNDS.size - 1) {
                        val x0 = (BOUNDS[i] / GAUGE_MAX).toFloat() * size.width
                        val x1 = (BOUNDS[i + 1] / GAUGE_MAX).toFloat() * size.width
                        drawRect(
                            color = VixZone.entries[i].color(),
                            topLeft = Offset(x0, 0f),
                            size = Size(x1 - x0, h),
                        )
                    }
                }
                // Marker knob at the current reading (kept inside the bar).
                val frac = (vix.value / GAUGE_MAX).coerceIn(0.0, 1.0).toFloat()
                val cx = (frac * size.width).coerceIn(r, size.width - r)
                drawCircle(Color.White, radius = r * 1.05f, center = Offset(cx, h / 2f))
                drawCircle(zoneColor, radius = r * 1.05f, center = Offset(cx, h / 2f), style = Stroke(2.dp.toPx()))
            }
        }
    }

    if (onClick != null) {
        Card(onClick = onClick, shape = cardShape, colors = cardColors, modifier = cardModifier) { body() }
    } else {
        Card(shape = cardShape, colors = cardColors, modifier = cardModifier) { body() }
    }
}
