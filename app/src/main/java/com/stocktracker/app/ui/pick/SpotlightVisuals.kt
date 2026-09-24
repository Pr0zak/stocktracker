package com.stocktracker.app.ui.pick

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocktracker.app.data.remote.DailyPickFactor
import com.stocktracker.app.data.remote.DailyPickLevels
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.Indigo
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.Signal

/*
 * The "Spotlight" look for the Daily Pick card (chosen 2026-09-23 from four concepts): a soft glow
 * tinted by the day's verdict, a curved confidence dial, factor pills, and a colour-banded plan bar.
 * Every mark here is drawn from the same server numbers the plain card showed — it is a new coat,
 * not new claims.
 */

/** Gold, a touch brighter than [Signal], for the buy zone and cautions on the dark card. */
internal val ZoneGold = Color(0xFFD2A94A)

/**
 * Confidence as a half-dial that sweeps up to its value on first show, violet into green. The number
 * sits inside. Null confidence draws the empty track and a dash — never a dial at zero.
 */
@Composable
fun ConfidenceDial(conviction: Int?, modifier: Modifier = Modifier, width: Int = 112) {
    val c = conviction?.coerceIn(0, 100)
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(c) { sweep.animateTo((c ?: 0) / 100f, tween(1100, easing = FastOutSlowInEasing)) }
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    Box(
        modifier = modifier
            .width(width.dp)
            .height((width * 0.64f).dp)
            .semantics { contentDescription = c?.let { "Confidence $it out of 100" } ?: "Confidence unknown" },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.width(width.dp).height((width * 0.64f).dp)) {
            val stroke = 9.dp.toPx()
            val d = size.width - stroke
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(d, d)
            drawArc(track, 180f, 180f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (c != null && c > 0) {
                drawArc(
                    Brush.horizontalGradient(listOf(Indigo, GainGreen)),
                    180f, 180f * sweep.value, false, topLeft, arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(c?.toString() ?: "—", fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("CONFIDENCE", fontSize = 9.sp, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One factor as a pill: label, a small fill bar when the factor has a rank, and the rank in words.
 * Green for a reason for, gold for a reason against. No rank → no bar, never an empty one.
 */
@Composable
fun FactorPill(factor: DailyPickFactor?, fallbackLabel: String, supports: Boolean, onClick: () -> Unit) {
    val tint = if (supports) GainGreen else ZoneGold
    val label = factor?.label?.takeIf { it.isNotBlank() } ?: fallbackLabel
    val rank = DailyPickRead.rankWords(factor?.pctile)
    val tail = rank ?: factor?.let { shortValue(it) }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.13f))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(if (supports) "For: $label" else "Against: $label", factor?.display).joinToString(". ")
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        factor?.pctile?.let { p ->
            Canvas(Modifier.padding(horizontal = 6.dp).width(26.dp).height(5.dp)) {
                val r = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = r)
                drawRoundRect(tint, size = Size(size.width * (p / 100.0).toFloat().coerceIn(0f, 1f), size.height), cornerRadius = r)
            }
        }
        tail?.let {
            Text(it, color = tint.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1,
                modifier = Modifier.padding(start = if (factor?.pctile == null) 6.dp else 0.dp))
        }
    }
}

/** A factor without a rank, reduced to its number: "+11%" rather than the full sentence. */
private fun shortValue(f: DailyPickFactor): String? {
    val v = f.value ?: return null
    return when (f.unit) {
        "pct" -> String.format(java.util.Locale.US, "%+.0f%%", v)
        "pp" -> String.format(java.util.Locale.US, "%+.0f pts", v)
        "x" -> String.format(java.util.Locale.US, "%.1f×", v)
        else -> null
    }
}

/**
 * The plan as one rounded bar: red for the exit end, gold for the buy zone, green for the target end,
 * and a white dot for the price now. Positions come from [DailyPickRead.ladder]; a level the analyst
 * gave no number for draws no band. Fewer than two prices → nothing is drawn.
 */
@Composable
fun PlanBar(levels: DailyPickLevels?, price: Double?, modifier: Modifier = Modifier, middle: String? = null) {
    val marks = DailyPickRead.ladder(levels, price) ?: return
    val at = marks.associate { it.kind to it.x }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val desc = buildString {
        append("Plan: ")
        levels?.stop?.let { append("exit ${DailyPickRead.money(it)}, ") }
        if (levels?.entryLow != null && levels.entryHigh != null) append("buy zone ${DailyPickRead.money(levels.entryLow)} to ${DailyPickRead.money(levels.entryHigh)}, ")
        levels?.target?.let { append("target ${DailyPickRead.money(it)}, ") }
        append(price?.let { "now ${DailyPickRead.money(it)}" } ?: "price unavailable")
    }
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = desc }) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp)) {
            val w = maxWidth
            Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                val cy = size.height - 12.dp.toPx()
                val h = 6.dp.toPx()
                val r = CornerRadius(h / 2, h / 2)
                drawRoundRect(Color.White.copy(alpha = 0.08f), Offset(0f, cy - h / 2), Size(size.width, h), r)
                at[DailyPickRead.Mark.Kind.STOP]?.let { x ->
                    drawRoundRect(LossRed.copy(alpha = 0.6f), Offset(0f, cy - h / 2), Size(x * size.width, h), r)
                }
                at[DailyPickRead.Mark.Kind.TARGET]?.let { x ->
                    drawRoundRect(GainGreen.copy(alpha = 0.6f), Offset(x * size.width, cy - h / 2), Size((1f - x) * size.width, h), r)
                }
                val lo = at[DailyPickRead.Mark.Kind.ZONE_LOW]
                val hi = at[DailyPickRead.Mark.Kind.ZONE_HIGH]
                if (lo != null && hi != null) {
                    val zh = 11.dp.toPx()
                    drawRoundRect(ZoneGold.copy(alpha = 0.5f), Offset(lo * size.width, cy - zh / 2),
                        Size((hi - lo) * size.width, zh), CornerRadius(zh / 2, zh / 2))
                }
                at[DailyPickRead.Mark.Kind.PRICE]?.let { x ->
                    val c = Offset(x * size.width, cy)
                    drawCircle(onSurface.copy(alpha = 0.25f), radius = 11.dp.toPx(), center = c)
                    drawCircle(surface, radius = 8.dp.toPx(), center = c)
                    drawCircle(onSurface, radius = 6.dp.toPx(), center = c)
                }
            }
            at[DailyPickRead.Mark.Kind.PRICE]?.let { x ->
                Text("now", fontSize = 10.sp, color = onSurface, modifier = Modifier.offset(x = (w * x - 10.dp).coerceIn(0.dp, w - 22.dp)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            levels?.stop?.let { Text("${DailyPickRead.money(it)} exit", fontSize = 11.sp, color = LossRed, fontFamily = FontFamily.Monospace) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                middle?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
            levels?.target?.let { Text("${DailyPickRead.money(it)} target", fontSize = 11.sp, color = GainGreen, fontFamily = FontFamily.Monospace) }
        }
    }
}

/**
 * The no-pick day's small ring: how close the best candidate came, in gold, with the number inside.
 * Null draws the empty track and a dash.
 */
@Composable
fun NearMissRing(conviction: Int?, modifier: Modifier = Modifier, color: Color = ZoneGold, diameter: Int = 46) {
    val c = conviction?.coerceIn(0, 100)
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    Box(
        modifier.size(diameter.dp).semantics { contentDescription = c?.let { "Confidence $it out of 100" } ?: "Confidence unknown" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter.dp)) {
            val stroke = (diameter / 9f).dp.toPx()
            val d = size.width - stroke
            val tl = Offset(stroke / 2, stroke / 2)
            drawArc(track, -90f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
            if (c != null) drawArc(color, -90f, 360f * c / 100f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(c?.toString() ?: "—", fontSize = (diameter * 0.3f).sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}
