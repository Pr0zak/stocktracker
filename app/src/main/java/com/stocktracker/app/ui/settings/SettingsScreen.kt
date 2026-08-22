package com.stocktracker.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.stocktracker.app.data.remote.SignalsHealth
import com.stocktracker.app.data.remote.BackendState
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocktracker.app.BuildConfig
import com.stocktracker.app.data.BackupManager
import com.stocktracker.app.data.prefs.ThemeMode
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.notify.SignalScanNotifier
import com.stocktracker.app.update.UpdateDialog
import com.stocktracker.app.update.UpdateUiState
import com.stocktracker.app.update.rememberUpdateController
import com.stocktracker.app.widget.WidgetRefreshScheduler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenMethodology: () -> Unit = {}) {
    val settings = ServiceLocator.settingsStore
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val theme by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val dynamic by settings.dynamicColor.collectAsState(initial = true)
    val refresh by settings.defaultRefreshMinutes.collectAsState(initial = 15)
    val savedKey by settings.finnhubApiKey.collectAsState(initial = "")
    val hideZeroCents by settings.hideZeroCents.collectAsState(initial = false)
    val showExtendedHours by settings.showExtendedHours.collectAsState(initial = false)
    val showMarketStatus by settings.showMarketStatus.collectAsState(initial = true)
    val showVix by settings.showVix.collectAsState(initial = true)
    val showGate by settings.showGate.collectAsState(initial = true)
    val showVolume by settings.showVolume.collectAsState(initial = false)
    val savedSignalsUrl by settings.signalsApiUrl.collectAsState(initial = "")
    val aiOn by settings.aiAnalystEnabled.collectAsState(initial = true)
    val marketSummary by settings.marketSummaryEnabled.collectAsState(initial = true)
    val marketSummaryAfterHours by settings.marketSummaryAfterHours.collectAsState(initial = true)
    val marketSummaryMarketWide by settings.marketSummaryMarketWide.collectAsState(initial = false)
    val aiDailyBrief by settings.aiDailyBriefEnabled.collectAsState(initial = false)

    var keyField by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedKey) { if (keyField == null) keyField = savedKey }
    var showKey by remember { mutableStateOf(false) }
    var signalsUrlField by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedSignalsUrl) { if (signalsUrlField == null) signalsUrlField = savedSignalsUrl }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            val n = runCatching { BackupManager.exportTo(context, uri) }.getOrElse { -1 }
            Toast.makeText(context, if (n >= 0) "Exported $n tickers" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val n = runCatching { BackupManager.importFrom(context, uri) }.getOrElse { -1 }
            Toast.makeText(context, if (n >= 0) "Imported $n tickers" else "Import failed", Toast.LENGTH_SHORT).show()
        }
    }

    val updater = rememberUpdateController()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsSection("Appearance") {
                LabeledChips("Theme") {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = theme == mode,
                            onClick = { scope.launch { settings.setThemeMode(mode) } },
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                SwitchRow(
                    "Material You dynamic colour",
                    "Tint the app from your wallpaper",
                    dynamic,
                ) { scope.launch { settings.setDynamicColor(it) } }
                SwitchRow(
                    "Hide .00 on whole prices",
                    "Show $12 instead of $12.00",
                    hideZeroCents,
                ) {
                    scope.launch {
                        settings.setHideZeroCents(it)
                        WidgetRefreshScheduler.refreshNow(context) // reflect on placed widgets
                    }
                }
            }

            SettingsSection("Dashboard") {
                SwitchRow("Market session timeline", null, showMarketStatus) {
                    scope.launch { settings.setShowMarketStatus(it) }
                }
                SwitchRow("VIX fear gauge", null, showVix) {
                    scope.launch { settings.setShowVix(it) }
                }
                SwitchRow(
                    "Market gate",
                    "The five checkable conditions behind the regime read",
                    showGate,
                ) { scope.launch { settings.setShowGate(it) } }
            }

            SettingsSection("Notifications") {
                // Whether the background job that evaluates price alerts is actually running. It
                // fires every ~15 min; anything much older means Android has been deferring or
                // killing it (battery optimisation is the usual cause), and your alerts are not
                // being checked no matter how they are configured.
                BackgroundRunStatus()

                SwitchRow(
                    "Market close & after-hours summary",
                    "A notification of your watchlist's top movers at the close and after hours",
                    marketSummary,
                ) { scope.launch { settings.setMarketSummaryEnabled(it) } }

                if (marketSummary) {
                    Column(
                        modifier = Modifier.padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SwitchRow(
                            "Include after-hours summary",
                            "Also recap movers after the 8pm ET after-hours close",
                            marketSummaryAfterHours,
                        ) { scope.launch { settings.setMarketSummaryAfterHours(it) } }

                        LabeledChips("Top movers") {
                            FilterChip(
                                selected = !marketSummaryMarketWide,
                                onClick = { scope.launch { settings.setMarketSummaryMarketWide(false) } },
                                label = { Text("Watchlist") },
                            )
                            FilterChip(
                                selected = marketSummaryMarketWide,
                                onClick = { scope.launch { settings.setMarketSummaryMarketWide(true) } },
                                label = { Text("Whole market") },
                            )
                        }
                        HelperText(
                            "Your watchlist, or the whole market's biggest movers. Whole-market applies " +
                                "to the close recap.",
                        )
                    }
                }

                SwitchRow(
                    "AI morning brief",
                    "A once-a-morning AI read of the tape, your watchlist movers, and any earnings due " +
                        "today (needs the AI analyst on + a Signals URL)",
                    aiDailyBrief,
                ) { scope.launch { settings.setAiDailyBriefEnabled(it) } }

                if (aiDailyBrief) {
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                Toast.makeText(context, "Fetching brief…", Toast.LENGTH_SHORT).show()
                                val err = com.stocktracker.app.notify.AiDailyBriefNotifier.sendNow(context)
                                Toast.makeText(
                                    context, err ?: "Brief sent — check your notifications", Toast.LENGTH_LONG,
                                ).show()
                            }
                        }) { Text("Send a test brief now") }
                        HelperText("The brief posts automatically each trading morning (8:30–10am ET).")
                    }
                }
            }

            SettingsSection("Chart") {
                SwitchRow(
                    "Extended-hours",
                    "Adds pre-market & after-hours to the 1D stock chart, dashed in a shaded band",
                    showExtendedHours,
                ) { scope.launch { settings.setShowExtendedHours(it) } }
                SwitchRow("Volume on chart", null, showVolume) {
                    scope.launch { settings.setShowVolume(it) }
                }
                HelperText(
                    "Tap “Indicators” on any chart to add moving averages, Bollinger Bands, VWAP, RSI, and MACD.",
                )
            }

            SettingsSection("Widgets") {
                LabeledChips("Default refresh interval") {
                    listOf(15, 30, 60, 120).forEach { minutes ->
                        FilterChip(
                            selected = refresh == minutes,
                            onClick = { scope.launch { settings.setDefaultRefreshMinutes(minutes) } },
                            label = { Text(if (minutes < 60) "${minutes}m" else "${minutes / 60}h") },
                        )
                    }
                }
                HelperText("Android refreshes home-screen widgets at most every 15 minutes.")
            }

            SettingsSection("Data") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Finnhub API key (optional)", style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(
                        value = keyField ?: "",
                        onValueChange = { keyField = it },
                        label = { Text("Finnhub API key") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (showKey) "Hide key" else "Show key",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { settings.setFinnhubApiKey(keyField.orEmpty()) } }) {
                            Text("Save key")
                        }
                        if (!savedKey.isNullOrBlank()) {
                            TextButton(onClick = {
                                keyField = ""
                                scope.launch { settings.setFinnhubApiKey("") }
                            }) { Text("Clear") }
                        }
                    }
                }
                HelperText(
                    "✓ Stocks & crypto work with no key (Yahoo + CoinGecko). A Finnhub key just adds an " +
                        "extra search source. Stored on-device only.",
                )
            }

            SettingsSection("AI analyst") {
                SwitchRow(
                    "AI analyst",
                    "Off pauses all Claude calls (verdicts, entry plans, ideas) to save token cost. The " +
                        "server's nightly scan still runs.",
                    aiOn,
                ) { scope.launch { settings.setAiAnalystEnabled(it) } }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Signals service URL", style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(
                        value = signalsUrlField ?: "",
                        onValueChange = { signalsUrlField = it },
                        label = { Text("http://host:8000") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // The diagnosis belongs HERE, not in the banner. The banner is deliberately one
                    // terse line above real content, but SignalsHealth.lastError already distinguishes
                    // "host not found - check the URL" from "connection refused - is the service
                    // running?", and this screen is the only place a wrong URL can actually be fixed.
                    // Without it, a typo and a down homelab were the same undiagnosable "offline".
                    if (savedSignalsUrl.isNotBlank()) {
                        val health by SignalsHealth.state.collectAsState()
                        val ok = health.state == BackendState.ONLINE
                        val dotColor = when {
                            health.checking -> MaterialTheme.colorScheme.onSurfaceVariant
                            ok -> Color(0xFF2E7D32)
                            health.state == BackendState.OFFLINE -> Color(0xFFC64040)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(Modifier.size(8.dp).background(dotColor, RoundedCornerShape(50)))
                            Text(
                                when {
                                    health.checking -> "Checking\u2026"
                                    ok -> "Connected"
                                    health.state == BackendState.OFFLINE ->
                                        health.lastError ?: "Can't reach the service"
                                    else -> "Not checked yet"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = !health.checking,
                                onClick = { SignalsHealth.retry() },
                            ) { Text("Test") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { settings.setSignalsApiUrl(signalsUrlField.orEmpty()) } }) {
                            Text("Save URL")
                        }
                        if (savedSignalsUrl.isNotBlank()) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val msg = runCatching { SignalScanNotifier.syncNow() }.fold(
                                        { "Watchlist synced ($it symbols)" },
                                        { "Sync failed: ${it.message ?: "network error"}" },
                                    )
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Sync now") }
                            TextButton(onClick = {
                                signalsUrlField = ""
                                scope.launch { settings.setSignalsApiUrl("") }
                            }) { Text("Clear") }
                        }
                    }
                }
                HelperText(
                    "Set this to your self-hosted signals service to show a Claude analyst verdict on the " +
                        "detail screen. Your watchlist auto-syncs there about every 15 min — tap “Sync now” " +
                        "to push it immediately. Leave blank to keep it off. Decision support only — not advice.",
                )
            }

            SettingsSection("Backup") {
                HelperText("Save your watchlist, holdings, cost, alerts, and lists to a file — or restore from one.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exportLauncher.launch("stocktracker-backup.json") }) { Text("Export") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        Text("Import")
                    }
                }
            }

            SettingsSection("Updates") {
                Button(onClick = { updater.check() }) { Text("Check for updates") }
                when (val us = updater.state) {
                    is UpdateUiState.Checking -> HelperText("Checking…")
                    is UpdateUiState.UpToDate -> Text(
                        "✓ You're on the latest version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is UpdateUiState.Error -> Text(
                        us.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
            }
            UpdateDialog(updater) // shows the Available/Downloading modal

            SettingsSection("How the numbers are made") {
                // A methodology page nobody can reach is a methodology page nobody has. It sits in
                // Settings rather than behind any one card because it qualifies figures on half a
                // dozen screens, and every one of them would otherwise need its own footnote.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenMethodology() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("What these numbers don't account for", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Read",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HelperText(
                    "Slippage and fees, survivorship, simulated versus recorded-live figures, why a " +
                        "time exit counts as a win, and every threshold that was chosen rather than " +
                        "measured.",
                )
            }

            SettingsSection("About") {
                var showChangelog by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showChangelog = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("StockTracker v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "What's new",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HelperText("Stocks: Yahoo · Crypto: CoinGecko · Search extra: Finnhub")
                if (showChangelog) {
                    val recent = com.stocktracker.app.update.Changelog.recent()
                    com.stocktracker.app.update.ChangelogSheet(
                        title = "What's new",
                        sections = recent.map { (v, lines) -> "v$v" to lines },
                        onDismiss = { showChangelog = false },
                    )
                }
            }
        }
    }
}

/**
 * Whether the background job that evaluates price alerts is actually running.
 *
 * Alerts are only checked when this job runs (every ~15 min via WorkManager). Nothing in the app
 * previously said whether it had — so "my jump/drop alerts never fire" had two very different causes
 * that looked identical: no thresholds configured, or the job not running at all. Android defers or
 * kills background work aggressively under battery optimisation, and the app cannot fix that; it can
 * at least stop pretending everything is armed.
 */
@Composable
private fun BackgroundRunStatus() {
    val settings = ServiceLocator.settingsStore
    val lastRun by settings.lastBackgroundRunAt.collectAsState(initial = 0L)
    val failures by settings.lastBackgroundFailures.collectAsState(initial = "")
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

    val now = System.currentTimeMillis()
    val ageMin = if (lastRun > 0) (now - lastRun) / 60_000 else -1
    // The job is scheduled every 15 min; Android may stretch that, so an hour is the point at which
    // it has clearly stopped rather than merely slipped.
    val healthy = ageMin in 0..59 && failures.isBlank()
    val color = when {
        lastRun <= 0 -> neutral
        healthy -> GainGreen
        else -> Color(0xFFB0872B)
    }
    val label = when {
        lastRun <= 0 -> "Alert checks: hasn't run yet"
        ageMin < 1 -> "Alert checks: just now"
        ageMin < 60 -> "Alert checks: ${ageMin}m ago"
        ageMin < 60 * 24 -> "Alert checks: ${ageMin / 60}h ago"
        else -> "Alert checks: ${ageMin / (60 * 24)}d ago"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = color)
        Text(
            when {
                lastRun <= 0 ->
                    "Price alerts are evaluated by a background job that hasn't run since install. " +
                        "Open the app once with a network connection to start it."
                failures.isNotBlank() ->
                    "Last run had failures: $failures. Alerts still ran unless 'alerts' is listed."
                !healthy ->
                    "It should run about every 15 minutes. This long a gap usually means Android is " +
                        "deferring it — exclude StockTracker from battery optimisation to fix it."
                else -> "Running normally. Set per-ticker thresholds on a stock's detail screen."
            },
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    }
}

/** A titled group of settings on one surfaceVariant card, with a primary-tinted eyebrow above it. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** A settings row: title (+ optional subtitle) on the left, a Switch on the right. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A label above a horizontal row of choice chips. */
@Composable
private fun LabeledChips(label: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

/** Muted small print for a section's explanatory note. */
@Composable
private fun HelperText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
