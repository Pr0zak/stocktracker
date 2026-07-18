package com.stocktracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A compact labelled meter — label + value on top, a filled track below, with an optional threshold
 * tick marking the "good" line. Turns a bare number (RSI, ROE, margin, D/E, hit-rate) into a glance.
 * `fraction` is the fill 0..1; `thresholdFraction` (if given) drops a tick at that point.
 */
@Composable
fun ThresholdMeter(
    label: String,
    valueText: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    thresholdFraction: Float? = null,
) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    val f = fraction.coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
        }
        Spacer(Modifier.height(3.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp)) {
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth().height(6.dp)
                    .background(track, RoundedCornerShape(50)),
            )
            if (f > 0f) {
                Box(
                    Modifier.align(Alignment.CenterStart).fillMaxWidth(f).height(6.dp)
                        .background(color, RoundedCornerShape(50)),
                )
            }
            thresholdFraction?.let { tf ->
                Box(
                    Modifier.align(Alignment.CenterStart).offset(x = maxWidth * tf.coerceIn(0f, 1f))
                        .width(2.dp).height(10.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)),
                )
            }
        }
    }
}
