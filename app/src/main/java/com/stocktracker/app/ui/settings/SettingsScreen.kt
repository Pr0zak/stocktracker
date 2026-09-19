package com.stocktracker.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocktracker.app.BuildConfig
import com.stocktracker.app.data.BackupManager
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.notify.AlertDelivery
import com.stocktracker.app.notify.AlertDeliveryStatus
import com.stocktracker.app.notify.AlertNotifier
import com.stocktracker.app.notify.SignalScanNotifier
import androidx.compose.material3.AlertDialog
import com.stocktracker.app.data.remote.WatchlistSyncRefusal
import com.stocktracker.app.data.remote.watchlistSyncRefusal
import com.stocktracker.app.update.UpdateDialog
import com.stocktracker.app.update.UpdateUiState
import com.stocktracker.app.update.rememberUpdateController
import com.stocktracker.app.widget.WidgetRefreshScheduler
import kotlinx.coroutines.launch
import com.stocktracker.app.ui.theme.Signal
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.stocktracker.app.ui.theme.OnSurfaceDark
import com.stocktracker.app.widget.WidgetBackground
import com.stocktracker.app.widget.WidgetRefresh
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenMethodology: () -> Unit = {}, onOpenWidgets: () -> Unit = {}) {
    val settings = ServiceLocator.settingsStore
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val refresh by settings.defaultRefreshMinutes.collectAsState(initial = 15)
    val widgetBgArgb by settings.widgetBackgroundArgb.collectAsState(initial = WidgetBackground.DEFAULT_ARGB)
    val widgetBgTransparency by settings.widgetBackgroundTransparency
        .collectAsState(initial = WidgetBackground.DEFAULT_TRANSPARENCY)
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
    // A pending OPS-3 removal-guard refusal from "Sync now" — non-null shows the confirm/cancel
    // dialog below. Cleared on either choice; never auto-retried with replace=true.
    var syncRefusal by remember { mutableStateOf<WatchlistSyncRefusal?>(null) }

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
            // The theme picker and the Material You switch are gone. The app is dark, always: the
            // light scheme was never finished — its gain and loss inks were the dark theme's
            // pastels reused unchanged, at 1.66:1 and 2.63:1 against a light surface — and dynamic
            // colour meant the branded palette never rendered at all on Android 12+. A picker with
            // one option left in it is not a picker.
            SettingsSection("Appearance") {
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

            SettingsSection("Home screen") {
                // The widget gallery used to hold a permanent tab — a fifth of this app's
                // navigation — for a job you do once. This is where a one-time setup step lives.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenWidgets() }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Home-screen widgets", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Pick a layout and place it on your home screen",
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

                WidgetBackgroundSetting(
                    colorArgb = widgetBgArgb,
                    transparencyPct = widgetBgTransparency,
                    onColorChange = { argb ->
                        scope.launch {
                            settings.setWidgetBackgroundArgb(argb)
                            WidgetRefresh.repaintAll(context)
                        }
                    },
                    onTransparencyChange = { pct ->
                        scope.launch {
                            settings.setWidgetBackgroundTransparency(pct)
                            WidgetRefresh.repaintAll(context)
                        }
                    },
                )
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
                            ok -> GainGreen
                            health.state == BackendState.OFFLINE -> Signal
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
                                    // Reachable is not the same as healthy. A backend whose
                                    // settings.json was unreadable answers every request perfectly
                                    // while running on the wrong watchlist with no API key, so
                                    // "Connected" alone would be a true statement that misleads.
                                    ok && health.settingsDegraded -> when (health.settingsSource) {
                                        "backup" -> "Connected \u2014 settings recovered from backup"
                                        "env" -> "Connected \u2014 settings file unreadable, using defaults"
                                        else -> "Connected \u2014 settings source ${health.settingsSource}"
                                    }
                                    ok -> "Connected"
                                    health.state == BackendState.OFFLINE ->
                                        health.lastError ?: "Can't reach the service"
                                    else -> "Not checked yet"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                // Amber is this app's "we have an opinion about our own data"
                                // colour; a degraded settings source is exactly that.
                                color = if (ok && health.settingsDegraded) Signal
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    runCatching { SignalScanNotifier.syncNow() }.fold(
                                        { n ->
                                            Toast.makeText(
                                                context, "Watchlist synced ($n symbols)", Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                        { e ->
                                            // A 409 means OPS-3's removal guard refused this sync — surface
                                            // exactly what it would have removed and let the user decide,
                                            // rather than folding it into a generic failure toast.
                                            val refusal = watchlistSyncRefusal(e)
                                            if (refusal != null) {
                                                syncRefusal = refusal
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "Sync failed: ${e.message ?: "network error"}",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        },
                                    )
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
                        // 48dp, like every other tappable row. A single line of bodyLarge with 4dp
                        // of padding is about 32dp, which is a target you have to aim at.
                        .heightIn(min = 48.dp)
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
                        .heightIn(min = 48.dp)
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

    // OPS-3: "Sync now" was refused because it would have removed more than the backend's guard
    // allows from a list a different install last wrote. Show exactly what, and require an explicit
    // choice — this dialog is the ONLY path that may resend with replace=true.
    syncRefusal?.let { refusal ->
        val listName = if (refusal.field == "crypto_watchlist") "crypto watchlist" else "watchlist"
        AlertDialog(
            onDismissRequest = { syncRefusal = null },
            title = { Text("Sync would remove ${refusal.removedCount} symbol${if (refusal.removedCount == 1) "" else "s"}") },
            text = {
                Text(
                    "Another device set the $listName last. Syncing from this one would shrink it from " +
                        "${refusal.nBefore} to ${refusal.nAfter} symbols, removing:\n\n" +
                        refusal.removed.joinToString(", ") +
                        "\n\nForce the sync to apply anyway, or cancel and leave the backend's list as it is.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    syncRefusal = null
                    scope.launch {
                        runCatching { SignalScanNotifier.syncNow(replace = true) }.fold(
                            { n ->
                                Toast.makeText(
                                    context, "Watchlist synced ($n symbols, forced)", Toast.LENGTH_SHORT,
                                ).show()
                            },
                            { e ->
                                Toast.makeText(
                                    context, "Sync failed: ${e.message ?: "network error"}", Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                }) { Text("Force sync") }
            },
            dismissButton = {
                TextButton(onClick = { syncRefusal = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Whether the background job that evaluates price alerts is actually running, AND — NOTIF-1 —
 * whether a post it makes has any chance of reaching the user. These are independent dimensions: a
 * worker ticking exactly on schedule with zero reported failures tells you nothing about whether
 * every [AlertNotifier.notify] call it made was silently swallowed by a blocked app, a muted channel,
 * or a missing permission. This composable used to only measure the first and call the result
 * "Running normally" — which is exactly the label you'd see with alerts fully, silently dead.
 *
 * Alerts are only checked when this job runs (every ~15 min via WorkManager). Nothing in the app
 * previously said whether it had — so "my jump/drop alerts never fire" had three very different
 * causes that looked identical: no thresholds configured, the job not running at all, or the job
 * running fine and being ignored by the notification system. Android defers or kills background work
 * aggressively under battery optimisation, and the app cannot fix that; it can at least stop
 * pretending everything is armed.
 */
@Composable
private fun BackgroundRunStatus() {
    val context = LocalContext.current
    val settings = ServiceLocator.settingsStore
    val lastRun by settings.lastBackgroundRunAt.collectAsState(initial = 0L)
    val failures by settings.lastBackgroundFailures.collectAsState(initial = "")
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

    // AlertDelivery reads live OS state (permission, the app-wide notification toggle, the channel's
    // mute state, battery-optimisation exemption) that Compose has no observable API for. None of it
    // changes while this screen just sits open — it only changes when the user leaves for a system
    // settings screen (one of the buttons below sends them to exactly one) and comes back — so refresh
    // on ON_RESUME rather than polling.
    val lifecycleOwner = LocalLifecycleOwner.current
    var delivery by remember { mutableStateOf(AlertDeliveryStatus.OK) }
    DisposableEffect(lifecycleOwner, context) {
        fun refresh() { delivery = AlertDelivery.current(context) }
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val now = System.currentTimeMillis()
    val ageMin = if (lastRun > 0) (now - lastRun) / 60_000 else -1
    // The job is scheduled every 15 min; Android may stretch that, so an hour is the point at which
    // it has clearly stopped rather than merely slipped.
    val workerHealthy = ageMin in 0..59 && failures.isBlank()
    val healthy = workerHealthy && delivery == AlertDeliveryStatus.OK
    val color = when {
        lastRun <= 0 -> neutral
        healthy -> GainGreen
        else -> Signal
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
                !workerHealthy ->
                    "It should run about every 15 minutes. This long a gap usually means Android is " +
                        "deferring it."
                healthy -> "Running normally. Set per-ticker thresholds on a stock's detail screen."
                // delivery != OK but the worker itself looks fine — the specific reason and its fix
                // render below, not folded into this line, so "Running normally" is never shown
                // alongside a delivery block.
                else -> "The worker is running, but alerts may not reach you — see below."
            },
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
        if (delivery != AlertDeliveryStatus.OK) {
            DeliveryBlockedNotice(delivery)
        }
    }
}

/**
 * The specific reason [status] is blocking delivery, with a button straight to the system screen that
 * fixes it. NOTIF-1 point 4: a paragraph of prose telling the user to go find a setting is not a fix;
 * three of these four need no special permission to launch, including battery optimisation
 * (ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) — the one this screen used to only describe in text.
 */
@Composable
private fun DeliveryBlockedNotice(status: AlertDeliveryStatus) {
    val context = LocalContext.current
    if (status == AlertDeliveryStatus.OK) return
    val (message, buttonLabel, intent) = when (status) {
        AlertDeliveryStatus.PERMISSION_DENIED -> Triple(
            "Notification permission is off — alerts cannot be delivered at all.",
            "Grant permission",
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
        AlertDeliveryStatus.APP_BLOCKED -> Triple(
            "Notifications are turned off for StockTracker — alerts cannot be delivered at all.",
            "App notification settings",
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
        AlertDeliveryStatus.CHANNEL_MUTED -> Triple(
            "The \"Price alerts\" channel is muted — other StockTracker notifications still work, " +
                "but price alerts will not arrive.",
            "Channel settings",
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, AlertNotifier.CHANNEL_ID),
        )
        AlertDeliveryStatus.BATTERY_RESTRICTED -> Triple(
            "Battery optimisation may delay or skip the background check entirely.",
            "Exclude from battery optimisation",
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
        AlertDeliveryStatus.OK -> return
    }
    Text(message, style = MaterialTheme.typography.labelSmall, color = Signal)
    TextButton(
        onClick = { runCatching { context.startActivity(intent) } },
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 0.dp),
    ) { Text(buttonLabel) }
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

/**
 * Card colour and transparency for every home-screen widget.
 *
 * The preview sits on a checkerboard rather than on the settings surface, because transparency is
 * the one property of this control that cannot be judged against an opaque backdrop: at 40% over a
 * flat panel the card simply looks like a slightly different colour, which is the wrong reading.
 */
@Composable
private fun WidgetBackgroundSetting(
    colorArgb: Long,
    transparencyPct: Int,
    onColorChange: (Long) -> Unit,
    onTransparencyChange: (Int) -> Unit,
) {
    // The slider writes to DataStore and repaints every placed widget, so it commits on release
    // rather than on every frame of the drag. Keyed on the stored value so a change from anywhere
    // else still lands here.
    var draft by remember(transparencyPct) { mutableStateOf(transparencyPct.toFloat()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Background", style = MaterialTheme.typography.bodyLarge)

        WidgetBackgroundPreview(colorArgb = colorArgb, transparencyPct = draft.roundToInt())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetBackground.COLOR_CHOICES.forEach { (name, argb) ->
                val selected = argb == colorArgb
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = name }
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onColorChange(argb) },
                        )
                        .background(Color(argb.toInt()), CircleShape)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            // Every swatch is dark by design, so without an outline the near-black
                            // ones are indistinguishable from each other and from the panel.
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Transparency", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${draft.roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = draft,
                onValueChange = { draft = it },
                onValueChangeFinished = { onTransparencyChange(draft.roundToInt()) },
                valueRange = 0f..100f,
                steps = 19, // 5% stops
                modifier = Modifier.semantics {
                    contentDescription = "Widget background transparency"
                },
            )
        }

        HelperText(
            "0% is solid, 100% shows only the text. Applies to all three widgets. " +
                "The widget's text stays light, so a mostly-transparent card over a pale " +
                "wallpaper will be hard to read.",
        )
    }
}

/** The chosen card over a checkerboard, so the transparency is visible rather than implied. */
@Composable
private fun WidgetBackgroundPreview(colorArgb: Long, transparencyPct: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Checkerboard(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .background(
                    Color(WidgetBackground.argbWith(colorArgb, transparencyPct)),
                    RoundedCornerShape(20.dp),
                )
                .padding(12.dp),
        ) {
            Text("AAPL", color = Color(0xFFB4A0FF), fontWeight = FontWeight.Bold)
            Text(
                "$229.14",
                color = OnSurfaceDark,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Text("\u25b2 +1.20%", color = GainGreen, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Checkerboard(modifier: Modifier) {
    val light = Color(0xFF3C3C44)
    val dark = Color(0xFF2A2A31)
    Canvas(modifier) {
        val cell = 10.dp.toPx()
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var col = 0
            while (x < size.width) {
                drawRect(
                    color = if ((row + col) % 2 == 0) light else dark,
                    topLeft = Offset(x, y),
                    size = Size(min(cell, size.width - x), min(cell, size.height - y)),
                )
                x += cell
                col++
            }
            y += cell
            row++
        }
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
