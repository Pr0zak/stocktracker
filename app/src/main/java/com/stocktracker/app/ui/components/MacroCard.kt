package com.stocktracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.MacroCatalyst
import com.stocktracker.app.data.remote.MacroState
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed

private val AMBER = Color(0xFFB0872B)

/** Catalysts drawn when the card is open. The backend merges an ongoing story into one entry, so a
 *  healthy read is ~5; this only bounds a pathological one. */
private const val MAX_CATALYSTS = 6

/**
 * The market-wide exogenous backdrop — wars, sanctions, energy and shipping disruption, central-bank
 * moves, tariffs — as graded by the backend's macro job.
 *
 * The honesty rule this card exists to hold: an ABSENT read and a CALM read must never look alike. A
 * failed news pull rendering as a quiet backdrop would suppress risk exactly when risk is real, so
 * [MacroState.available] is checked before anything reassuring is drawn, `degraded` is called out,
 * and the read's age is always on screen rather than implied.
 */
@Composable
fun MacroCard(state: MacroState?, modifier: Modifier = Modifier) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var open by rememberSaveable { mutableStateOf(false) }

    // No read at all — say exactly that, and say what it does NOT mean.
    if (state == null || !state.available || state.riskLevel.isNullOrBlank()) {
        Column(
            modifier = modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Macro backdrop", style = MaterialTheme.typography.labelLarge, color = neutral)
            Text(
                "Couldn't load the news read. That's a connection problem — it is not a sign that " +
                    "markets are calm.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    val level = state.riskLevel.lowercase()
    val levelColor = when (level) {
        "high" -> LossRed
        "elevated" -> AMBER
        else -> GainGreen
    }
    val cats = state.catalysts

    Column(
        modifier = modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Macro backdrop", style = MaterialTheme.typography.labelLarge, color = neutral)
                MacroPill(level.uppercase(), levelColor)
            }
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "Collapse macro backdrop" else "Expand macro backdrop",
                tint = neutral,
            )
        }

        // The headline sits above the fold either way — one line, always the same line, so opening
        // the card adds detail instead of replacing what you were just reading.
        state.headline?.takeIf { it.isNotBlank() }?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
            )
        }

        if (!open) {
            // Collapsed: just the names of what's driving it, on one line.
            cats.take(3).joinToString(" · ") { it.title }.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, style = MaterialTheme.typography.labelSmall, color = neutral,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            if (state.bullets.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                state.bullets.forEach { BulletLine(it) }
            }
            if (cats.isEmpty()) {
                Text(
                    "No market-moving events in the latest read.",
                    style = MaterialTheme.typography.bodySmall, color = neutral,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                // Capped so a run that somehow produces a long tail can't turn the card back into the
                // scroll it replaced. The overflow is stated rather than silently dropped.
                cats.take(MAX_CATALYSTS).forEach { CatalystRow(it) }
                if (cats.size > MAX_CATALYSTS) {
                    Text(
                        "+${cats.size - MAX_CATALYSTS} lower-severity catalyst(s) not shown",
                        style = MaterialTheme.typography.labelSmall, color = neutral,
                    )
                }
            }
        }

        // Age is ALWAYS on screen, open or closed — a macro read that has quietly stopped updating is
        // the failure mode that matters, and it is invisible unless the age is stated.
        val age = state.ageSeconds
        val ageText = when {
            age == null -> "age unknown"
            age < 3600 -> "${age / 60} min ago"
            age < 86_400 -> "${age / 3600}h ago"
            else -> "${age / 86_400}d ago"
        }
        Text(
            buildString {
                append(ageText)
                if (state.stale) append(" · stale")
                if (state.degraded) append(" · last refresh failed")
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (state.stale || state.degraded) AMBER else neutral,
        )
    }
}

/** One fact per line with a marker — the shape the eye can skim, which a paragraph is not. */
@Composable
private fun BulletLine(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "•", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CatalystRow(c: MacroCatalyst) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val dirColor = when (c.direction) {
        "risk_off" -> LossRed
        "risk_on" -> GainGreen
        else -> AMBER
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                c.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${c.severity}", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, color = dirColor,
            )
        }
        LinearProgressIndicator(
            progress = { (c.severity / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
            color = dirColor,
        )
        Text(
            listOfNotNull(
                c.category.takeIf { it.isNotBlank() },
                c.direction.replace('_', '-').takeIf { it.isNotBlank() },
                c.horizon.takeIf { it.isNotBlank() },
                c.affected.take(3).joinToString(", ").takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = neutral,
        )
        if (c.why.isNotBlank()) {
            Text(c.why, style = MaterialTheme.typography.labelSmall, color = neutral)
        }
    }
}

@Composable
private fun MacroPill(text: String, color: Color) = Box(
    Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
        .padding(horizontal = 9.dp, vertical = 2.dp),
) { Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color) }
