package com.stocktracker.app.ui.watchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.GateLeg
import com.stocktracker.app.data.remote.GateResponse
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.Signal

/**
 * A plain-words summary of the five market checks: what each one tests, what it read, and whether it
 * passed. Opened by tapping a market-checks chip. [footer] lets the caller say what the checks change
 * on its own screen (the Daily Pick raises its confidence bar when any check fails).
 */
@Composable
fun MarketChecksDialog(resp: GateResponse, footer: String?, onDismiss: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val summary = GateRead.summary(resp)
    // A record with no legs (older runs) still names what failed, from its `failing` list.
    val legs = resp.legs.ifEmpty {
        resp.failing.orEmpty().map { GateLeg(name = it, key = GateRead.keyForName(it) ?: it, ok = false) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(summary?.headline ?: "Market checks") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Five simple tests of whether the market as a whole is healthy. They don't predict " +
                        "anything; they say whether conditions favour buying.",
                    style = MaterialTheme.typography.bodySmall, color = neutral,
                )
                if (legs.isEmpty()) {
                    Text(summary?.detail ?: "No reading is available.", style = MaterialTheme.typography.bodyMedium)
                }
                for (leg in legs) LegLine(leg)
                if (resp.legs.isEmpty() && legs.isNotEmpty()) {
                    Text("Only the failing check was recorded for this day, so the others aren't listed.",
                        style = MaterialTheme.typography.labelSmall, color = neutral)
                }
                footer?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

@Composable
private fun LegLine(leg: GateLeg) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val (glyph, tint, word) = when (GateRead.mark(leg.ok)) {
        LegMark.PASS -> Triple("✓", GainGreen, "passes")
        LegMark.FAIL -> Triple("✗", Signal, "fails")
        LegMark.UNKNOWN -> Triple("—", neutral, "could not be measured")
    }
    val title = GateRead.legTitle(leg)
    val value = GateRead.plainValue(leg)
    val meaning = GateRead.plain(leg.key)?.meaning
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull("$title: $word", value, meaning).joinToString(". ")
        },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(glyph, color = tint, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tint) }
            meaning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral) }
            if (leg.ok == null && leg.note.isNotBlank()) {
                Text(leg.note, style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
    }
}
