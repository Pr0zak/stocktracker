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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed

/**
 * "Where does today's price sit in its 52-week range?" — a track filled from the yearly low to the
 * current price, with a marker dot and endpoint labels. Mirrors the session-timeline bar's style.
 */
@Composable
fun FiftyTwoWeekRangeBar(
    low: Double,
    high: Double,
    current: Double,
    up: Boolean,
    valueFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    val span = (high - low).takeIf { it > 0.0 } ?: return
    val fraction = ((current - low) / span).coerceIn(0.0, 1.0).toFloat()
    val markColor = if (up) GainGreen else LossRed
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    val surface = MaterialTheme.colorScheme.surface

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "52-week range",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${valueFormatter(current)} now",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
            val dot = 14.dp
            val dotX = (maxWidth - dot) * fraction
            // track
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth().height(8.dp)
                    .background(trackColor, RoundedCornerShape(50)),
            )
            // filled portion (low → current)
            Box(
                Modifier.align(Alignment.CenterStart).width(dotX + dot / 2).height(8.dp)
                    .background(
                        Brush.horizontalGradient(listOf(markColor.copy(alpha = 0.45f), markColor)),
                        RoundedCornerShape(50),
                    ),
            )
            // current marker
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = dotX).size(dot)
                    .background(surface, RoundedCornerShape(50))
                    .padding(2.5.dp)
                    .background(markColor, RoundedCornerShape(50)),
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(valueFormatter(low), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueFormatter(high), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
