package com.stocktracker.app.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.remote.ReportSummary
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.components.Skeleton
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.Signal
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * RPT-1 — every stored weekly and monthly report, newest first, plus the two alert switches.
 * A spoke of the Markets tab, like the market scan and the heat map.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    onOpenReport: (String) -> Unit,
    onOpenSignalsSettings: () -> Unit,
    vm: ReportsViewModel = viewModel(),
) {
    val st by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("all") }
    val settings = ServiceLocator.settingsStore
    val weekly by settings.reportWeeklyNotifyEnabled.collectAsState(initial = true)
    val monthly by settings.reportMonthlyNotifyEnabled.collectAsState(initial = true)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reports") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "week" to "Weekly", "month" to "Monthly").forEach { (k, label) ->
                    FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(label) })
                }
            }

            when {
                !st.configured -> GlowCard(tint = null) {
                    Text("Reports need the Signals service", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Set its address in Settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onOpenSignalsSettings) { Text("Open Settings") }
                }
                st.loading && st.rows.isEmpty() -> repeat(3) { Skeleton(Modifier.fillMaxWidth().height(64.dp)) }
                st.error != null -> GlowCard(tint = null) {
                    Text(st.error ?: "", style = MaterialTheme.typography.bodyMedium, color = Signal)
                    TextButton(onClick = vm::load) { Text("Try again") }
                }
                else -> {
                    val rows = st.rows.filter { filter == "all" || it.kind == filter }
                    NextReportRow(filter, st.rows.mapNotNull { it.id }.toSet())
                    if (rows.isEmpty()) {
                        Text("No reports yet. The weekly one is made after Friday's close, the monthly one after the month's last close.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 4.dp)) {
                            rows.forEachIndexed { i, r ->
                                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                ReportRow(r, st.youPct[r.id], isNew = i == 0 && fresh(r)) { r.id?.let(onOpenReport) }
                            }
                        }
                    }
                }
            }

            Text("Alerts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 4.dp)) {
                SwitchRow("Weekly report", "Fridays after the close", weekly) { scope.launch { settings.setReportWeeklyNotifyEnabled(it) } }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow("Monthly report", "The month's last trading day, after the close", monthly) { scope.launch { settings.setReportMonthlyNotifyEnabled(it) } }
            }
        }
    }
}

/** A report made in the last three days gets the NEW pill. */
private fun fresh(r: ReportSummary): Boolean =
    r.madeAt?.let { System.currentTimeMillis() / 1000.0 - it < 3 * 86400 } ?: false

@Composable
private fun NextReportRow(filter: String, existing: Set<String>) {
    val kind = if (filter == "month") "month" else "week"
    val end = ReportRead.nextReportEnd(kind, LocalDate.now(), { com.stocktracker.app.util.MarketHolidays.isMarketHoliday(it) }, existing)
    val title = if (kind == "month") "${end.format(DateTimeFormatter.ofPattern("MMMM", Locale.US))} report"
    else "week of ${end.with(java.time.DayOfWeek.MONDAY).format(DateTimeFormatter.ofPattern("MMM d", Locale.US))}"
    val whenText = end.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)) + ", after the close"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f)).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Column {
            Text("Next: $title", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(whenText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReportRow(r: ReportSummary, youPct: Double?, isNew: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Open report", onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Assessment, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(rowTitle(r), style = MaterialTheme.typography.bodyLarge)
                if (isNew) Pill("NEW", MaterialTheme.colorScheme.primary)
            }
            Text(buildAnnotatedString {
                // Non-breaking spaces inside each figure, so a wrap never strands an arrow from its number.
                fun part(label: String, p: Double?) {
                    append(label.replace(' ', '\u00A0'))
                    withStyle(SpanStyle(color = if (p == null) Signal else if (p >= 0) GainGreen else LossRed)) {
                        append(ReportRead.pct(p).replace(' ', '\u00A0'))
                    }
                }
                part("S&P ", r.sp500Pct)
                if (youPct != null) { append(" · "); part("You ", youPct) }
                if (r.sandboxPct != null) { append(" · "); part("AI ", r.sandboxPct) }
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Week of Sep 21" or "September 2026". */
fun rowTitle(r: ReportSummary): String =
    if (r.kind == "month") r.label ?: "Month" else "Week of " + (r.label?.substringBefore(" –")?.trim() ?: r.end.orEmpty())

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = if (checked) "Turn off" else "Turn on") { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
