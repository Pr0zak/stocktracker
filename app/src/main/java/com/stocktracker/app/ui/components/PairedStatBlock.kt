package com.stocktracker.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.model.PairedStat
import com.stocktracker.app.ui.theme.TrafficAmber

/**
 * A paired statistic, rendered (SWT-9).
 *
 * ONE COMPOSABLE FOR EVERY SITE, on purpose. The rule the pairing exists to enforce — both halves,
 * each with its sample size, or neither — is only worth anything if it holds everywhere, and it
 * cannot hold everywhere while each screen decides for itself which halves are interesting today.
 * Everything printed here is read off [PairedStat]; nothing is recomputed and nothing is optional.
 *
 * BOTH ROWS ALWAYS DRAW when the pair has anything at all. An absent half prints its reason in the
 * same slot the number would have used, because the failure mode is not a wrong number, it is a
 * confident number with nothing beside it and no hint that anything is missing.
 */
@Composable
fun PairedStatBlock(
    stat: PairedStat,
    modifier: Modifier = Modifier,
    /** Optional colour for a MEASURED value (a sign, a good/bad read). Absences stay neutral. */
    tint: (PairedStat.Side) -> Color? = { null },
) {
    if (stat.isEmpty) return
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stat.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        PairedStatSideRow(stat, stat.backtest, tint(stat.backtest), neutral)
        PairedStatSideRow(stat, stat.forward, tint(stat.forward), neutral)

        // The caveat sits WITH the number, never in a footnote — a divergence explained three cards
        // down is a divergence nobody reads.
        stat.divergenceNote?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = TrafficAmber)
        }
        if (stat.divergenceNote == null) {
            stat.smallSampleNote?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
    }
}

/** One half: its evidence label, then either the reading or the reason there isn't one. */
@Composable
private fun PairedStatSideRow(
    stat: PairedStat,
    side: PairedStat.Side,
    valueColor: Color?,
    neutral: Color,
) {
    val reading = stat.reading(side)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            side.evidence.shortLabel,
            style = MaterialTheme.typography.labelMedium,
            color = neutral,
        )
        Column(Modifier.weight(1f)) {
            when (reading) {
                is PairedStat.Reading.Absent -> Text(
                    // NOT a dash on its own. "—" in a column of numbers reads as zero to anyone
                    // skimming; the reason is the only thing that cannot be misread.
                    "no record — ${reading.why}",
                    style = MaterialTheme.typography.bodySmall,
                    color = neutral,
                )
                is PairedStat.Reading.Counts -> {
                    Text(
                        reading.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        // DELIBERATELY UNTINTED. Colouring "3 of 4" green by the rate it was told not
                        // to print would leak the suppressed percentage back as a verdict in another
                        // channel; under the floor there is no verdict to render.
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        // Says WHY there is no percentage here, so its absence reads as a decision.
                        "${side.subject} · ${side.evidence.sentence} · under ${PairedStat.FLOOR} " +
                            "trades, so the count and not a rate",
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                }
                is PairedStat.Reading.Measured -> {
                    Text(
                        "${reading.text} · ${side.sample?.let { stat.sampleText(it) }.orEmpty()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${side.subject} · ${side.evidence.sentence}",
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                }
            }
        }
    }
}
