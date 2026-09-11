package com.stocktracker.app.ui.markets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.stocktracker.app.ui.components.BackendStatusBanner

/**
 * The Markets hub — the one structural move in this overhaul.
 *
 * Five features used to be reachable through exactly one unlabelled glyph each, all of them parked
 * in the Watchlist's app bar: an `AutoAwesome` sparkle for the AI market brief, a `GridView` for the
 * heat map, a `Leaderboard` for a nightly scan of roughly three thousand names, a `CalendarMonth`
 * for catalysts. The dip radar and the VIX detail were worse than unlabelled — they were reachable
 * only by expanding a collapsed strip, and the dip list could not be opened at all on a day with no
 * dips, which is exactly the day its reject audit is worth reading.
 *
 * Meanwhile a permanent tab was spent on a gallery of mock prices whose entire job is a one-time
 * widget pin. That tab is what this screen takes.
 *
 * Rows say what they are. That is the whole idea, and it is not a clever one — it is the
 * conventional one this app had skipped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketsScreen(
    onOpenScan: () -> Unit = {},
    onOpenHeatmap: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenDips: () -> Unit = {},
    onOpenVix: () -> Unit = {},
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Markets") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackendStatusBanner()

            Door(
                icon = Icons.Filled.Leaderboard,
                title = "Market scan",
                subtitle = "Where every name sits against the whole market, from the nightly scan. " +
                    "A rank, not a grade.",
                onClick = onOpenScan,
            )
            Door(
                icon = Icons.Filled.GridView,
                title = "Heat map",
                subtitle = "The day's moves by sector, sized by market cap.",
                onClick = onOpenHeatmap,
            )
            Door(
                icon = Icons.Filled.CalendarMonth,
                title = "Catalyst calendar",
                subtitle = "Earnings, option expiries and short-interest dates for what you follow.",
                onClick = onOpenCalendar,
            )
            // Reachable on a zero-dip day, which is the state it could not be opened from at all:
            // the "See all" link only existed once the strip was expanded AND a dip had fired. The
            // reject audit — the names the radar considered and passed over — is most worth reading
            // precisely when nothing fired.
            Door(
                icon = Icons.Filled.TrendingDown,
                title = "Dip radar",
                subtitle = "What is off its highs, and what the radar looked at and rejected.",
                onClick = onOpenDips,
            )
            // Likewise: the VIX gauge only existed if the strip was open, the setting was on, AND
            // the fetch had succeeded. Three conditions for a screen, and no stable way back to it.
            Door(
                icon = Icons.Filled.Speed,
                title = "Market fear · VIX",
                subtitle = "The volatility index against its own bands, with the numbers on the scale.",
                onClick = onOpenVix,
            )

            // Market now is deliberately NOT here. It reads the session through YOUR watchlist's
            // names, so it belongs beside them — it just stops being a sparkle glyph and becomes a
            // named item in the Watchlist's overflow.
            Text(
                "Context, not advice. Every row here had exactly one way in before, and none of " +
                    "them was labelled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun Door(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .heightIn(min = 72.dp)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
