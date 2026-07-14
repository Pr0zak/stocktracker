package com.stocktracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.util.MarketClock
import com.stocktracker.app.util.MarketPhase
import com.stocktracker.app.util.MarketState

/** Option C — horizontal trading-session timeline (pre / regular / after) with a live "now" marker. */
@Composable
fun SessionTimelineBar(state: MarketState, modifier: Modifier = Modifier) {
    val preEnd = MarketClock.preEndFraction
    val regEnd = MarketClock.regEndFraction
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val statusColor = when (state.phase) {
        MarketPhase.REGULAR -> GainGreen
        MarketPhase.PRE, MarketPhase.AFTER -> accent
        MarketPhase.CLOSED -> muted
    }
    val inactiveTrack = muted.copy(alpha = 0.25f)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Market Session", style = MaterialTheme.typography.labelLarge, color = muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.size(6.dp))
                    Text(state.label, style = MaterialTheme.typography.labelLarge, color = statusColor, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Track with three weighted segments + the "now" marker.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val fullWidth = maxWidth
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                ) {
                    Box(Modifier.weight(preEnd).fillMaxHeight().background(if (state.phase == MarketPhase.PRE) accent else inactiveTrack))
                    Box(Modifier.weight(regEnd - preEnd).fillMaxHeight().background(if (state.phase == MarketPhase.REGULAR) accent else inactiveTrack))
                    Box(Modifier.weight(1f - regEnd).fillMaxHeight().background(if (state.phase == MarketPhase.AFTER) accent else inactiveTrack))
                }
                state.markerFraction?.let { f ->
                    Box(
                        modifier = Modifier
                            .offset(x = fullWidth * f - 7.dp, y = (-3).dp)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(accent)
                            .border(2.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Tick times positioned near the segment boundaries.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val w = maxWidth
                Tick("4:00a", 0.dp, muted)
                Tick("9:30a", (w * preEnd - 16.dp), muted)
                Tick("4:00p", (w * regEnd - 16.dp), muted)
                Box(Modifier.fillMaxWidth()) {
                    Text("8:00p", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                SegLabel("PRE-MARKET", preEnd, TextAlign.Start, active = state.phase == MarketPhase.PRE, accent, muted)
                SegLabel("REGULAR", regEnd - preEnd, TextAlign.Center, active = state.phase == MarketPhase.REGULAR, accent, muted)
                SegLabel("AFTER-HOURS", 1f - regEnd, TextAlign.End, active = state.phase == MarketPhase.AFTER, accent, muted)
            }
        }
    }
}

@Composable
private fun Tick(label: String, x: androidx.compose.ui.unit.Dp, color: Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.offset(x = if (x < 0.dp) 0.dp else x),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SegLabel(
    text: String,
    weight: Float,
    align: TextAlign,
    active: Boolean,
    accent: Color,
    muted: Color,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (active) accent else muted,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        textAlign = align,
        modifier = Modifier.weight(weight),
    )
}
