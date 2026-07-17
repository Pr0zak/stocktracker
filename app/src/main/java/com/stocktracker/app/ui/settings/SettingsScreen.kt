package com.stocktracker.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import com.stocktracker.app.BuildConfig
import com.stocktracker.app.data.BackupManager
import com.stocktracker.app.data.prefs.ThemeMode
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.notify.SignalScanNotifier
import com.stocktracker.app.update.UpdateDialog
import com.stocktracker.app.update.UpdateUiState
import com.stocktracker.app.update.rememberUpdateController
import com.stocktracker.app.widget.WidgetRefreshScheduler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
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
    val showVolume by settings.showVolume.collectAsState(initial = false)
    val savedSignalsUrl by settings.signalsApiUrl.collectAsState(initial = "")

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

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header("Appearance")
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = theme == mode,
                        onClick = { scope.launch { settings.setThemeMode(mode) } },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Material You dynamic color")
                Switch(checked = dynamic, onCheckedChange = { scope.launch { settings.setDynamicColor(it) } })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Hide .00 on whole prices")
                Switch(
                    checked = hideZeroCents,
                    onCheckedChange = {
                        scope.launch {
                            settings.setHideZeroCents(it)
                            WidgetRefreshScheduler.refreshNow(context) // reflect on placed widgets
                        }
                    },
                )
            }

            Header("Dashboard")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Show market session timeline")
                Switch(
                    checked = showMarketStatus,
                    onCheckedChange = { scope.launch { settings.setShowMarketStatus(it) } },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Show VIX fear gauge")
                Switch(
                    checked = showVix,
                    onCheckedChange = { scope.launch { settings.setShowVix(it) } },
                )
            }

            Header("Chart")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Show extended-hours (pre/post-market)")
                Switch(
                    checked = showExtendedHours,
                    onCheckedChange = { scope.launch { settings.setShowExtendedHours(it) } },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Show volume on chart")
                Switch(
                    checked = showVolume,
                    onCheckedChange = { scope.launch { settings.setShowVolume(it) } },
                )
            }
            Text(
                "Tap “Indicators” on any chart to add moving averages, Bollinger Bands, VWAP, RSI, and MACD.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Adds pre-market and after-hours to the 1D stock chart, drawn dashed in a shaded band.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Header("Widgets")
            Text("Default refresh interval", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 120).forEach { minutes ->
                    FilterChip(
                        selected = refresh == minutes,
                        onClick = { scope.launch { settings.setDefaultRefreshMinutes(minutes) } },
                        label = { Text(if (minutes < 60) "${minutes}m" else "${minutes / 60}h") },
                    )
                }
            }
            Text(
                "Android refreshes home-screen widgets at most every 15 minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Header("Data")
            Text("Finnhub API key (optional)", style = MaterialTheme.typography.bodyMedium)
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
            Text(
                "✓ Stocks & crypto work with no key (Yahoo + CoinGecko). A Finnhub key just adds an " +
                    "extra search source. Stored on-device only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Header("AI analyst (optional)")
            val aiOn by settings.aiAnalystEnabled.collectAsState(initial = true)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("AI analyst")
                Switch(checked = aiOn, onCheckedChange = { scope.launch { settings.setAiAnalystEnabled(it) } })
            }
            Text(
                "Off pauses all Claude calls from the app (verdicts, entry plans, ideas) to save " +
                    "token cost — your service URL is kept. The server's nightly scan still runs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Signals service URL", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = signalsUrlField ?: "",
                onValueChange = { signalsUrlField = it },
                label = { Text("http://host:8000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
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
            Text(
                "Set this to your self-hosted signals service to show a Claude analyst verdict on the " +
                    "detail screen. Your watchlist auto-syncs there about every 15 min — tap “Sync now” " +
                    "to push it immediately. Leave blank to keep it off. Decision support only — not advice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Header("Backup")
            Text(
                "Save your watchlist, holdings, cost, alerts, and lists to a file — or restore from one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("stocktracker-backup.json") }) { Text("Export") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                    Text("Import")
                }
            }

            Header("Updates")
            val updater = rememberUpdateController()
            Button(onClick = { updater.check() }) { Text("Check for updates") }
            when (val us = updater.state) {
                is UpdateUiState.Checking -> Text(
                    "Checking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            UpdateDialog(updater) // shows the Available/Downloading modal

            Header("About")
            Text("StockTracker v${BuildConfig.VERSION_NAME}")
            Text(
                "Stocks: Yahoo · Crypto: CoinGecko · Search extra: Finnhub",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}
