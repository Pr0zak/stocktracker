package com.stocktracker.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.SearchResult
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.di.ServiceLocator
import kotlinx.serialization.encodeToString
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.StockTrackerTheme
import com.stocktracker.app.util.Formatting
import kotlinx.coroutines.launch

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // If the user backs out, the widget host must not add the widget.
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            StockTrackerTheme {
                WidgetConfigScreen(
                    onCancel = { finish() },
                    onConfirm = ::confirm,
                )
            }
        }
    }

    private fun confirm(config: TickerWidgetConfig) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(applicationContext, glanceId) { prefs ->
                prefs[TickerWidgetState.CONFIG] = Http.json.encodeToString(config)
            }
            WidgetRefresh.refreshTicker(applicationContext, glanceId, force = true)
            WidgetRefreshScheduler.ensureScheduled(applicationContext)

            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, result)
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigScreen(
    onCancel: () -> Unit,
    onConfirm: (TickerWidgetConfig) -> Unit,
) {
    var config by remember { mutableStateOf(TickerWidgetConfig()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        searching = true
        kotlinx.coroutines.delay(300)
        results = runCatching { ServiceLocator.repository.search(query) }.getOrDefault(emptyList())
        searching = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configure Widget") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(onClick = { onConfirm(config) }) { Text("Add") }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = { onConfirm(config) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) { Text("Add to Home Screen") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { WidgetPreview(config) }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search ticker or crypto") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Text(
                    "Tracking: ${config.symbol} · ${config.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(results) { result ->
                SearchResultRow(result) {
                    config = config.copy(
                        symbol = result.symbol,
                        type = result.type,
                        displayName = result.name,
                        coinGeckoId = result.coinGeckoId,
                    )
                    query = ""
                    results = emptyList()
                }
            }

            item { SectionHeader("Display") }
            item {
                ToggleRow("Show change %", config.showChangePercent) {
                    config = config.copy(showChangePercent = it)
                }
            }
            item {
                ToggleRow("Show sparkline", config.showSparkline) {
                    config = config.copy(showSparkline = it)
                }
            }
            item {
                ToggleRow("Show company name", config.showName) {
                    config = config.copy(showName = it)
                }
            }

            item { SectionHeader("Accent") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TickerWidgetConfig.ACCENT_CHOICES.forEach { argb ->
                        AccentSwatch(
                            color = Color(argb.toInt()),
                            selected = config.accentArgb == argb,
                        ) { config = config.copy(accentArgb = argb) }
                    }
                }
            }

            item { SectionHeader("Refresh") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TickerWidgetConfig.REFRESH_CHOICES.forEach { minutes ->
                        FilterChip(
                            selected = config.refreshMinutes == minutes,
                            onClick = { config = config.copy(refreshMinutes = minutes) },
                            label = { Text(if (minutes < 60) "${minutes}m" else "${minutes / 60}h") },
                        )
                    }
                }
            }

            item { Spacer(Modifier.width(1.dp)) }
        }
    }
}

@Composable
private fun WidgetPreview(config: TickerWidgetConfig) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1C1B21), RoundedCornerShape(20.dp))
                .padding(16.dp)
                .width(150.dp),
        ) {
            if (config.showName) {
                Text(config.displayName, color = Color(0xFFCAC4D3), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            Text(config.symbol, color = Color(config.accentArgb.toInt()), fontWeight = FontWeight.Bold)
            Text(Formatting.price(229.14), color = Color(0xFFE6E1E9), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            if (config.showChangePercent) {
                Text("${Formatting.arrow(true)} ${Formatting.percent(1.20)}", color = GainGreen)
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(result.symbol, fontWeight = FontWeight.Bold)
            Text(result.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(
            if (result.type == AssetType.CRYPTO) "Crypto" else "Stock",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(color, CircleShape)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black)
    }
}
