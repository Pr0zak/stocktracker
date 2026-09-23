package com.stocktracker.app.ui.pick

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.DailyPickFactor
import com.stocktracker.app.data.remote.DailyPickLevels
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.Signal

/** Conviction 0-100 as a ring. The number is always printed inside; the ring is the glance. */
@Composable
fun ConvictionRing(conviction: Int?, modifier: Modifier = Modifier, size: Int = 44) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val c = conviction?.coerceIn(0, 100)
    val color = when {
        c == null -> MaterialTheme.colorScheme.onSurfaceVariant
        c >= 70 -> GainGreen
        c >= 60 -> Signal
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(size.dp)
            .semantics { contentDescription = c?.let { "Conviction $it out of 100" } ?: "Conviction unknown" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size.dp)) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (c != null) {
                drawArc(color, -90f, 360f * c / 100f, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(c?.toString() ?: "—", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
    }
}

/**
 * One reason on the card: stance arrow, the factor's label, a percentile bar when the factor HAS a rank,
 * and the server's reading in words. A factor with no rank draws no bar at all — never an empty one that
 * reads as the 0th percentile.
 */
@Composable
fun FactorRow(
    supports: Boolean,
    factor: DailyPickFactor?,
    fallbackLabel: String,
    text: String?,
    onExplain: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tint = if (supports) GainGreen else LossRed
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val label = factor?.label?.takeIf { it.isNotBlank() } ?: fallbackLabel
    val rank = DailyPickRead.ordinal(factor?.pctile)
    val stanceWord = if (supports) "For" else "Against"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("$stanceWord: $label. ")
                    factor?.display?.let { append("$it. ") }
                    rank?.let { append("$it percentile of the market. ") }
                    text?.let { append(it) }
                }
            },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                if (supports) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                contentDescription = null, tint = tint, modifier = Modifier.size(16.dp),
            )
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(112.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (factor?.pctile != null) {
                PercentileBar(factor.pctile, tint, Modifier.weight(1f))
                Text(rank ?: "", style = MaterialTheme.typography.labelMedium, color = neutral,
                    modifier = Modifier.width(36.dp))
            } else {
                Text(factor?.display ?: "", style = MaterialTheme.typography.bodySmall, color = neutral,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onExplain != null) InfoButton(label, onExplain)
        }
        val detail = listOfNotNull(factor?.display?.takeIf { factor.pctile != null }, text).joinToString(" — ")
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = neutral, modifier = Modifier.padding(start = 22.dp))
        }
    }
}

@Composable
private fun PercentileBar(pctile: Double, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    val f = (pctile / 100.0).coerceIn(0.0, 1.0).toFloat()
    Canvas(modifier.height(8.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
    }
}

@Composable
fun InfoButton(what: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Outlined.Info, contentDescription = "What is $what?",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

/** DP-15: the static explanation for one key, or nothing if the key has none. */
@Composable
fun ExplainDialog(key: String?, title: String?, onDismiss: () -> Unit) {
    val text = key?.let { DailyPickRead.explanations[it] } ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: "What this means") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

/**
 * The plan on one line: stop, the shaded buy zone, the live-price dot and the target, each only where it
 * exists. Labels sit below the marks they name. With fewer than two distinct prices there is nothing to
 * draw and the composable renders nothing — the text line under it still says what is known.
 */
@Composable
fun PlanLadder(levels: DailyPickLevels?, price: Double?, modifier: Modifier = Modifier) {
    val marks = DailyPickRead.ladder(levels, price) ?: return
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val zoneColor = Signal.copy(alpha = 0.35f)
    val desc = buildString {
        append("Plan: ")
        levels?.stop?.let { append("stop ${DailyPickRead.money(it)}, ") }
        if (levels?.entryLow != null || levels?.entryHigh != null) {
            append("buy zone ${DailyPickRead.money(levels.entryLow)} to ${DailyPickRead.money(levels.entryHigh)}, ")
        }
        levels?.target?.let { append("target ${DailyPickRead.money(it)}, ") }
        price?.let { append("price now ${DailyPickRead.money(it)}") } ?: append("current price unavailable")
    }
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = desc }) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val cy = size.height / 2
                drawLine(neutral.copy(alpha = 0.35f), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 2.dp.toPx())
                val lo = marks.firstOrNull { it.kind == DailyPickRead.Mark.Kind.ZONE_LOW }
                val hi = marks.firstOrNull { it.kind == DailyPickRead.Mark.Kind.ZONE_HIGH }
                if (lo != null && hi != null) {
                    drawRoundRect(zoneColor, topLeft = Offset(lo.x * size.width, cy - 7.dp.toPx()),
                        size = Size((hi.x - lo.x) * size.width, 14.dp.toPx()), cornerRadius = CornerRadius(4f, 4f))
                }
                for (m in marks) {
                    val x = m.x * size.width
                    when (m.kind) {
                        DailyPickRead.Mark.Kind.STOP -> drawLine(LossRed, Offset(x, cy - 9.dp.toPx()), Offset(x, cy + 9.dp.toPx()), 3.dp.toPx())
                        DailyPickRead.Mark.Kind.TARGET -> drawLine(GainGreen, Offset(x, cy - 9.dp.toPx()), Offset(x, cy + 9.dp.toPx()), 3.dp.toPx())
                        DailyPickRead.Mark.Kind.PRICE -> {
                            drawCircle(onSurface, radius = 6.dp.toPx(), center = Offset(x, cy))
                            drawCircle(Color.Black.copy(alpha = 0.4f), radius = 6.dp.toPx(), center = Offset(x, cy), style = Stroke(1.5f))
                        }
                        else -> Unit
                    }
                }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            val width = maxWidth
            for (m in marks) {
                val label = when (m.kind) {
                    DailyPickRead.Mark.Kind.STOP -> "stop ${DailyPickRead.money(m.price)}"
                    DailyPickRead.Mark.Kind.TARGET -> "target ${DailyPickRead.money(m.price)}"
                    DailyPickRead.Mark.Kind.PRICE -> "now"
                    else -> null
                } ?: continue
                val color = when (m.kind) {
                    DailyPickRead.Mark.Kind.STOP -> LossRed
                    DailyPickRead.Mark.Kind.TARGET -> GainGreen
                    else -> onSurface
                }
                // Anchor edge labels inward so "target $540" never runs off the right edge.
                val x = width * m.x
                val shift = when {
                    m.x > 0.8f -> x - 84.dp
                    m.x > 0.2f -> x - 28.dp
                    else -> x
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1,
                    modifier = Modifier.offset(x = shift.coerceAtLeast(0.dp)))
            }
        }
        if (levels?.entryLow != null && levels.entryHigh != null) {
            Text(
                "buy zone ${DailyPickRead.money(levels.entryLow)}–${DailyPickRead.money(levels.entryHigh)}",
                style = MaterialTheme.typography.labelSmall, color = Signal,
            )
        }
    }
}

/** A small rounded chip — gate, earnings, macro. Colour carries meaning only alongside the words. */
@Composable
fun PickChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** State holder for the explanation dialog, shared by the card and the sheet. */
class ExplainState {
    var key by mutableStateOf<String?>(null)
    var title by mutableStateOf<String?>(null)
    fun show(k: String, t: String) { key = k; title = t }
    fun dismiss() { key = null; title = null }
}

@Composable
fun rememberExplainState(): ExplainState = remember { ExplainState() }
