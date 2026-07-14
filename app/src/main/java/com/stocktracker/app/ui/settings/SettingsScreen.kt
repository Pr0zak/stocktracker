package com.stocktracker.app.ui.settings

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.stocktracker.app.BuildConfig
import com.stocktracker.app.data.prefs.ThemeMode
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val settings = ServiceLocator.settingsStore
    val scope = rememberCoroutineScope()
    val theme by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val dynamic by settings.dynamicColor.collectAsState(initial = true)
    val refresh by settings.defaultRefreshMinutes.collectAsState(initial = 15)
    val savedKey by settings.finnhubApiKey.collectAsState(initial = "")
    val stocksEnabled = savedKey.ifBlank { BuildConfig.FINNHUB_API_KEY }.isNotBlank()

    var keyField by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedKey) { if (keyField == null) keyField = savedKey }
    var showKey by remember { mutableStateOf(false) }

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
            Text("Finnhub API key (for stock quotes)", style = MaterialTheme.typography.bodyMedium)
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
                if (stocksEnabled) "✓ Stock quotes enabled" else "Stock quotes disabled — add a key above",
                style = MaterialTheme.typography.bodySmall,
                color = if (stocksEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(
                "Get a free key at finnhub.io/register. Stored on-device only. Crypto works without a key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Header("About")
            Text("StockTracker v${BuildConfig.VERSION_NAME}")
            Text(
                "Stocks: Finnhub · Crypto: CoinGecko · Stock charts: Yahoo",
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
