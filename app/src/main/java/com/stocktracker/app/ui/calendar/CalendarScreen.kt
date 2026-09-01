package com.stocktracker.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.CalendarEvent
import com.stocktracker.app.data.remote.CalendarResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private sealed interface CalState {
    data object Loading : CalState
    data class Ready(val resp: CalendarResponse) : CalState
    data class Error(val message: String) : CalState
}

/**
 * Catalyst calendar: every upcoming date that matters for the watchlist in one timeline —
 * short-interest settlements/publications, OPEX, and earnings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(onBack: () -> Unit, symbol: String? = null) {
    // Keyed on `reload` as well as `symbol` so the retry below can actually re-run the fetch — the
    // screen is entirely backend-dependent and previously had no way to try again at all.
    var reload by remember { mutableIntStateOf(0) }
    val state by produceState<CalState>(CalState.Loading, symbol, reload) {
        value = CalState.Loading
        val base = ServiceLocator.settingsStore.signalsApiUrl.first()
        value = if (base.isBlank()) {
            CalState.Error("Set your Signals service URL in Settings → AI analyst to see the calendar.")
        } else {
            runCatching { SignalsApiService().calendar(base, symbol) }.getOrNull()
                ?.let { CalState.Ready(it) }
                ?: CalState.Error("Couldn't load the calendar from the signals service.")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (symbol != null) "$symbol calendar" else "Catalyst calendar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is CalState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            is CalState.Error -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                // This screen loads entirely from the backend, so it gets the same offline banner as
                // everywhere else — and a real retry, which it never had.
                com.stocktracker.app.ui.components.BackendStatusBanner()
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.OutlinedButton(onClick = { reload++ }) { Text("Try again") }
            }
            is CalState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (s.resp.events.isEmpty()) {
                    item { Text("No upcoming events found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(s.resp.events) { e -> EventRow(e) }
                // Two ways this list can be less than the whole truth, and both used to be invisible.
                if (s.resp.isTruncated) {
                    item { TruncationNote(s.resp) }
                }
                if (s.resp.earningsUnchecked.isNotEmpty()) {
                    item { UncheckedNote(s.resp.earningsUnchecked) }
                }
                item {
                    Text(
                        "SI and FTD data publish with a lag — shown for awareness, not timing. Not advice.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The server caps how many rows it returns. Saying so — with the count and the date the list stops
 * at — is the difference between "nothing else is coming up" and "we stopped showing you here".
 */
@Composable
private fun TruncationNote(resp: CalendarResponse) {
    val total = resp.eventsTotal
    val through = resp.truncatedAfter?.let { runCatching { longDate(it) }.getOrDefault(it) }
    Text(
        buildString {
            append("Showing ${resp.events.size}")
            if (total != null) append(" of $total")
            append(" events")
            if (through != null) append(", through $through")
            append(".")
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Symbols whose earnings lookup failed. Absent from the list above is not the same as nothing due. */
@Composable
private fun UncheckedNote(symbols: List<String>) {
    Text(
        "Couldn't check earnings dates for ${symbols.joinToString(", ")} — " +
            "they may have events not shown here.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun longDate(iso: String): String =
    LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
        .format(DateTimeFormatter.ofPattern("d MMM yyyy"))

private fun relativeDay(iso: String): String = runCatching {
    val d = LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
    val diff = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), d)
    when {
        diff <= 0L -> "today"
        diff == 1L -> "tomorrow"
        else -> "in ${diff}d"
    }
}.getOrDefault("")

@Composable
private fun EventRow(e: CalendarEvent) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val kindColor = when (e.kind) {
        "earnings" -> Color(0xFF16A34A)
        "opex" -> MaterialTheme.colorScheme.primary
        else -> neutral // si_settlement / si_publication
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Date block: MM-DD large + relative-day underneath — scannable down the timeline.
        Column(modifier = Modifier.width(64.dp)) {
            Text(
                e.date.takeLast(5),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(relativeDay(e.date), style = MaterialTheme.typography.labelSmall, color = neutral)
        }
        Box(
            modifier = Modifier
                .background(kindColor.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                e.symbol ?: "ALL",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = kindColor,
            )
        }
        Text(e.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
