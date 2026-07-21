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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.CalendarEvent
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private sealed interface CalState {
    data object Loading : CalState
    data class Ready(val events: List<CalendarEvent>) : CalState
    data class Error(val message: String) : CalState
}

/**
 * Catalyst calendar: every upcoming date that matters for the watchlist in one timeline —
 * short-interest settlements/publications, OPEX, and earnings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(onBack: () -> Unit, symbol: String? = null) {
    val state by produceState<CalState>(CalState.Loading, symbol) {
        val base = ServiceLocator.settingsStore.signalsApiUrl.first()
        value = if (base.isBlank()) {
            CalState.Error("Set your Signals service URL in Settings → AI analyst to see the calendar.")
        } else {
            runCatching { SignalsApiService().calendar(base, symbol) }.getOrNull()
                ?.let { CalState.Ready(it.events) }
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
            is CalState.Error -> Text(
                s.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            is CalState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (s.events.isEmpty()) {
                    item { Text("No upcoming events found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(s.events) { e -> EventRow(e) }
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
