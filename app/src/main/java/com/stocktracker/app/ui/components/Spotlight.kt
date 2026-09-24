package com.stocktracker.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.Indigo
import com.stocktracker.app.ui.theme.LossRed
import java.util.Locale
import kotlin.math.abs

/*
 * The "Spotlight" kit: the Daily Pick card's look (v1.12.0), made reusable so every tab can use it.
 * Five pieces — a glow tinted by the day's direction, filled change pills, a loading placeholder,
 * and a count-up for big totals (range bars already live in FiftyTwoWeekRangeBar).
 *
 * The rule every piece keeps: colour is earned by data. A missing or stale number gets no tint, no
 * pill colour and no animation toward a value it does not have.
 */

/**
 * A soft glow: [tint] from the top-right corner and a violet wash from the top-left. Null [tint]
 * draws nothing — a failed, stale or loading block gets no mood it hasn't earned.
 */
fun Modifier.spotlightGlow(tint: Color?): Modifier = if (tint == null) this else drawBehind {
    drawRect(
        Brush.radialGradient(
            listOf(tint.copy(alpha = 0.26f), Color.Transparent),
            center = Offset(size.width * 0.88f, -size.height * 0.08f),
            radius = size.width * 0.95f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            listOf(Indigo.copy(alpha = 0.16f), Color.Transparent),
            center = Offset(-size.width * 0.08f, 0f),
            radius = size.width * 0.7f,
        ),
    )
}

/** The glow tint for a signed move: green up, red down, null when the move is unknown. */
fun directionTint(change: Double?): Color? = when {
    change == null || !change.isFinite() -> null
    change >= 0 -> GainGreen
    else -> LossRed
}

/** A rounded 20dp block on the raised surface, with the glow behind its content. */
@Composable
fun GlowCard(
    tint: Color?,
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .spotlightGlow(tint)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * A filled pill: [text] in [color] on a 14% wash of it. Tappable when [onClick] is given, with a
 * trailing "›" so it looks it.
 */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            if (onClick != null) "$text ›" else text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * A signed percent as a pill: "▲ 0.52%" green or "▼ 1.47%" red. The arrow carries direction, so it
 * never rests on colour alone. Null or non-finite → a neutral "—", never "0.00%".
 */
@Composable
fun ChangePill(pct: Double?, modifier: Modifier = Modifier, suffix: String = "") {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    if (pct == null || !pct.isFinite()) {
        Pill("—", neutral, modifier)
        return
    }
    Pill(changePillText(pct) + suffix, if (pct >= 0) GainGreen else LossRed, modifier)
}

/** "▲ 0.52%" / "▼ 1.47%". Exposed for tests and for callers that build their own label. */
fun changePillText(pct: Double): String =
    (if (pct >= 0) "▲ " else "▼ ") + String.format(Locale.US, "%.2f%%", abs(pct))

/**
 * A shimmering placeholder block, for a number that has not loaded yet. Used instead of drawing a
 * zero: "$0.00" reads as a fact, this reads as "coming".
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
    val hi = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val t = rememberInfiniteTransition(label = "skeleton")
    val x by t.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "skeletonX",
    )
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = "Loading" }
            .drawBehind {
                drawRect(base)
                val w = size.width
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, hi, Color.Transparent),
                        start = Offset(w * (x - 0.5f), 0f),
                        end = Offset(w * (x + 0.5f), 0f),
                    ),
                )
            },
    )
}

/**
 * [value] counted up from zero the first time it is shown (about 0.6 s), then set directly on
 * later changes so a refresh never replays the count. Skipped entirely when the phone's animator
 * scale is 0 (the system "remove animations" setting). [format] turns the number into text.
 */
@Composable
fun CountUpText(
    value: Double,
    format: (Double) -> String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val context = LocalContext.current
    val animate = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) > 0f
        }.getOrDefault(true)
    }
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) progress.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
    }
    // The final text is always the exact value; only the in-between frames are interpolated.
    val shown = if (progress.value >= 1f) value else value * progress.value
    Text(
        format(shown), style = style, color = color,
        modifier = modifier.semantics { contentDescription = format(value) },
    )
}
