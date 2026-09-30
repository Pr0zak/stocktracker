package com.stocktracker.app.ui.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.stocktracker.app.data.remote.TrendResponse
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.Signal

/** ABOUT-2 — the detail screen's tabs, in order. */
internal enum class DetailTab(val label: String) {
    ABOUT("About"), MONEY("Your money"), SIGNALS("Signals"), HISTORY("History"),
}

/**
 * The Signals tab's one-line answer: which way the readings lean, and in plain words which of them
 * point up or down. Reads the same [SnapFactor]s as the rows below it, so the two cannot disagree.
 */
@Composable
internal fun SignalsLead(factors: List<SnapFactor>) {
    if (factors.isEmpty()) return
    val lead = SignalsWording.lead(factors)
    GlowCard(tint = lead.color.takeIf { it != Color.Unspecified && lead.pill != "NEUTRAL" }) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(lead.title, style = MaterialTheme.typography.titleMedium, modifier = androidx.compose.ui.Modifier.weight(1f))
            Pill(lead.pill, if (lead.pill == "NEUTRAL") MaterialTheme.colorScheme.onSurfaceVariant else lead.color)
        }
        Text(lead.sentence, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal object SignalsWording {
    data class Lead(val title: String, val pill: String, val color: Color, val sentence: String)

    private val PLAIN = mapOf(
        "Momentum" to "momentum",
        "Value" to "price vs value",
        "Quality" to "business quality",
        "Smart money" to "insiders",
        "Short pressure" to "bets against it",
    )

    fun lead(factors: List<SnapFactor>): Lead {
        val up = factors.filter { it.bucket > 0 }.map { PLAIN[it.name] ?: it.name.lowercase() }
        val down = factors.filter { it.bucket < 0 }.map { PLAIN[it.name] ?: it.name.lowercase() }
        val (title, pill, color) = when {
            up.isNotEmpty() && down.isNotEmpty() -> Triple("Pulling both ways", "MIXED", Signal)
            up.isNotEmpty() -> Triple("Leaning up", "BULLISH", GainGreen)
            down.isNotEmpty() -> Triple("Leaning down", "BEARISH", LossRed)
            else -> Triple("Steady", "NEUTRAL", Color.Unspecified)
        }
        val sentence = buildString {
            append(if (up.isEmpty()) "Nothing points up." else join(up).replaceFirstChar { it.uppercase() } + (if (up.size == 1) " points" else " point") + " up.")
            append(" ")
            append(if (down.isEmpty()) "Nothing points down." else join(down).replaceFirstChar { it.uppercase() } + (if (down.size == 1) " points" else " point") + " down.")
        }
        return Lead(title, pill, color, sentence)
    }

    private fun join(xs: List<String>): String = when (xs.size) {
        1 -> xs[0]
        2 -> "${xs[0]} and ${xs[1]}"
        else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last()
    }
}

/** The Snapshot's value words, in the plain language the tab uses. */
internal fun plainValueWord(read: String): String = when (read) {
    "Extended" -> "Stretched"
    "Fair value" -> "Fair"
    else -> read
}

/** "Sep" → "September"; anything unrecognised passes through. */
internal fun monthName(short: String): String = runCatching {
    java.time.Month.entries.first { it.name.startsWith(short.uppercase().take(3)) }
        .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
}.getOrDefault(short)

/** "Above it and pulling away", "Below it and still falling". */
internal fun trendPlain(t: TrendResponse): String? {
    val side = when (t.belowLine) { true -> "Below it"; false -> "Above it"; null -> null } ?: return null
    val dir = when (t.direction) {
        "recovering" -> "climbing back"
        "deepening" -> "still falling"
        "approaching" -> "closing in"
        "moving_away" -> "pulling away"
        else -> null
    }
    return if (dir != null) "$side and $dir" else side
}
