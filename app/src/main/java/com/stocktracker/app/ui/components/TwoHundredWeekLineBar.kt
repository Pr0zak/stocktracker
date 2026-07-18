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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "How far is price below / above its 200-week line?" — a position bar with the LINE fixed at centre.
 * Below the line sits to the left (amber = long-term cheaper), above sits to the right. Clamped to
 * ±25% so extremes peg to the ends; the exact figure is in the header. Visual sibling of
 * [FiftyTwoWeekRangeBar].
 */
@Composable
fun TwoHundredWeekLineBar(
    pctFromLine: Double,
    belowLine: Boolean,
    modifier: Modifier = Modifier,
) {
    val amber = Color(0xFFD29922)
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val trackColor = neutral.copy(alpha = 0.16f)
    val surface = MaterialTheme.colorScheme.surface
    val markColor = if (belowLine) amber else neutral
    val fraction = ((pctFromLine.coerceIn(-25.0, 25.0) + 25.0) / 50.0).toFloat()

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "vs 200-week line",
                style = MaterialTheme.typography.labelMedium,
                color = neutral,
            )
            Text(
                "%+.1f%% %s".format(pctFromLine, if (belowLine) "below" else "above"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = markColor,
            )
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
            val dot = 14.dp
            val dotX = (maxWidth - dot) * fraction
            // full track
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth().height(8.dp)
                    .background(trackColor, RoundedCornerShape(50)),
            )
            // the 200-week line itself, fixed at centre
            Box(
                Modifier.align(Alignment.Center).width(2.dp).height(14.dp)
                    .background(neutral.copy(alpha = 0.55f)),
            )
            // current price marker
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = dotX).size(dot)
                    .background(surface, RoundedCornerShape(50))
                    .padding(2.5.dp)
                    .background(markColor, RoundedCornerShape(50)),
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("below the line", style = MaterialTheme.typography.labelSmall, color = neutral)
            Text("above", style = MaterialTheme.typography.labelSmall, color = neutral)
        }
    }
}
