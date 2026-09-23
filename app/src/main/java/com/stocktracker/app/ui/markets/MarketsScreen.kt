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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.stocktracker.app.data.remote.MarketBreadth
import com.stocktracker.app.data.remote.SignalsApiService
import kotlinx.coroutines.flow.first
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stocktracker.app.data.MarketContextStore
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.BackendStatusBanner
import com.stocktracker.app.ui.theme.Signal
import com.stocktracker.app.ui.detail.ageAgo
import com.stocktracker.app.ui.watchlist.DipRadarState
import com.stocktracker.app.util.readingAgeLabel

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
    // The same reading the watchlist strip and the dip radar are looking at, from the one store
    // that holds it. A hub whose rows only describe what is behind each door in the abstract asks
    // the user to open all five to find out whether anything happened today.
    val ctx = ServiceLocator.marketContext
    val market by ctx.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        ctx.refreshScan()
        ctx.refreshVix()
    }
    // The market scan's OWN date. The store above holds the watchlist scan, a different nightly
    // job — its age was being shown under "Market scan" (15h, when the market scan was 1h old).
    // Null = not answered yet; Result.failure = could not read it.
    val marketScan by produceState<Result<MarketBreadth?>?>(initialValue = null) {
        val base = ServiceLocator.settingsStore.signalsApiUrl.first()
        value = if (base.isBlank()) Result.success(null)
        else runCatching { SignalsApiService().marketBreadth(base) }
    }

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
                status = marketScanStatus(market, marketScan),
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
                status = dipStatus(market),
                onClick = onOpenDips,
            )
            // Likewise: the VIX gauge only existed if the strip was open, the setting was on, AND
            // the fetch had succeeded. Three conditions for a screen, and no stable way back to it.
            Door(
                icon = Icons.Filled.Speed,
                title = "Market fear · VIX",
                subtitle = "The volatility index against its own bands, with the numbers on the scale.",
                status = vixStatus(market),
                onClick = onOpenVix,
            )

            // Market now is deliberately NOT here. It reads the session through YOUR watchlist's
            // names, so it belongs beside them — it just stops being a sparkle glyph and becomes a
            // named item in the Watchlist's overflow.
            Text(
                "Context, not advice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
        }
    }
}

/**
 * The live line under a row, or null where there is nothing true to put there.
 *
 * Three of these five rows lead to something this screen already holds a reading for, so they say
 * it. The heat map and the catalyst calendar do not: nothing here has fetched either, and inventing
 * a summary — or worse, a reassuring one — for a door whose contents nobody has looked at is the
 * defect this app spends most of its comments on. Those two keep their description and no number.
 */
private data class DoorStatus(val text: String, val warn: Boolean = false)

/**
 * "Measured the Mon Sep 22 close" — which session the market scan last read, from the scan's own
 * breadth route. Falls back to [scanStatus]'s service-state messages when there is no Signals URL.
 */
private fun marketScanStatus(m: MarketContextStore.State, r: Result<MarketBreadth?>?): DoorStatus? {
    if (r == null) return null
    val b = r.getOrNull()
    if (r.isFailure) return DoorStatus("Couldn't read when the scan last ran", warn = true)
    if (b == null) return scanStatus(m)
    if (!b.available) return DoorStatus("No market scan stored yet", warn = true)
    val d = b.asOf?.takeIf { it.length >= 8 }?.let {
        runCatching {
            java.time.LocalDate.of(it.substring(0, 4).toInt(), it.substring(4, 6).toInt(), it.substring(6, 8).toInt())
                .format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", java.util.Locale.US))
        }.getOrNull()
    } ?: return DoorStatus("Scan date unknown", warn = true)
    return DoorStatus("Measured the $d close")
}

private fun scanStatus(m: MarketContextStore.State): DoorStatus? = when (m.dipRadar) {
    // FRESHNESS ONLY, deliberately. The obvious thing to put here is a count, and the count this
    // screen holds — dipCounts.scanned — is the dip radar's coverage of YOUR WATCHLIST, not the
    // market scan's universe, which is thousands of names fetched by a different endpoint on the
    // scan screen itself. The first cut said "14 names, scanned 7h ago" under a row called Market
    // scan, which is a wrong number worn confidently. The age is a fact this screen actually has.
    is DipRadarState.Ready -> m.scan?.generatedAt
        ?.let { ageAgo(it.toString()) }
        ?.let { DoorStatus("Scanned $it") }
    is DipRadarState.Unreachable -> DoorStatus("Scan service unreachable", warn = true)
    is DipRadarState.NotConfigured -> DoorStatus("No Signals service URL set", warn = true)
    is DipRadarState.NoScan -> DoorStatus("No scan has run yet", warn = true)
    DipRadarState.Loading -> null
}

private fun dipStatus(m: MarketContextStore.State): DoorStatus? = when (val s = m.dipRadar) {
    // "Nothing qualified" is a claim about the market, and it is only ours to make when we are
    // holding a scan that actually ran — see DipRadar's own note on why this is the one message
    // that must never be emitted by default.
    is DipRadarState.Ready -> {
        // The denominator matters: "2 off their highs" alone leaves the reader to guess whether the
        // radar looked at five names or five hundred. scanned is the radar's own partition total.
        val of = s.counts.scanned?.let { " of $it" }.orEmpty()
        when (val n = s.dips.size) {
            0 -> DoorStatus("Nothing qualified" + (s.counts.scanned?.let { " out of $it names" } ?: " in the last scan"))
            1 -> DoorStatus("1$of off its highs")
            else -> DoorStatus("$n$of off their highs")
        }
    }
    is DipRadarState.Unreachable -> DoorStatus("Couldn't reach the scan — not a calm market", warn = true)
    is DipRadarState.NotConfigured -> DoorStatus("No Signals service URL set", warn = true)
    is DipRadarState.NoScan -> DoorStatus("No scan has run yet", warn = true)
    DipRadarState.Loading -> null
}

private fun vixStatus(m: MarketContextStore.State): DoorStatus? {
    val v = m.vix ?: return if (m.vixFailed) DoorStatus("Couldn't load the VIX", warn = true) else null
    val line = "${String.format("%.2f", v.value)} · ${v.zone.label.lowercase()}"
    // A held reading whose last refresh failed, or one merely old — restored from disk on a cold
    // start, or unrefreshed a long while (DATA-9) — says so rather than passing for the current one.
    val age = readingAgeLabel(m.vixFetchedAtMs, System.currentTimeMillis(), failed = m.vixFailed)
    return if (age != null) DoorStatus("$line — $age", warn = true) else DoorStatus(line)
}

@Composable
private fun Door(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    status: DoorStatus? = null,
) {
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
            status?.let {
                Text(
                    it.text,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (it.warn) Signal else MaterialTheme.colorScheme.onSurface,
                )
            }
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
