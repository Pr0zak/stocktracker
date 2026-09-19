package com.stocktracker.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
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
import com.stocktracker.app.ui.theme.OnSurfaceDark
import com.stocktracker.app.ui.theme.OnSurfaceVariantDark

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark, always -- see MainActivity's identical call. A bare enableEdgeToEdge()
        // lets the SYSTEM day/night setting pick the status-bar icon color, which is how a
        // system-light phone got black icons on the black bar INK-2 gave every other screen.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
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

        // Both widgets' APPWIDGET_CONFIGURE intent-filters point at this one activity (see the
        // manifest) rather than each carrying its own -- the host has already bound appWidgetId to
        // a provider by the time this activity launches, so its provider info says which widget is
        // actually being configured.
        val isWatchlist = isWatchlistWidget(applicationContext, appWidgetId)

        setContent {
            StockTrackerTheme {
                if (isWatchlist) {
                    WatchlistWidgetConfigScreen(
                        appWidgetId = appWidgetId,
                        onCancel = { finish() },
                        onConfirm = ::confirmWatchlist,
                    )
                } else {
                    WidgetConfigScreen(
                        appWidgetId = appWidgetId,
                        onCancel = { finish() },
                        onConfirm = ::confirm,
                    )
                }
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

    private fun confirmWatchlist(config: WatchlistWidgetConfig) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(applicationContext, glanceId) { prefs ->
                prefs[WatchlistWidgetState.CONFIG] = Http.json.encodeToString(config)
            }
            WidgetRefresh.refreshWatchlistInstance(applicationContext, glanceId, force = true)
            WidgetRefreshScheduler.ensureScheduled(applicationContext)

            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, result)
            finish()
        }
    }

    companion object {
        /** True when [appWidgetId] belongs to the watchlist widget rather than the ticker widget. */
        internal fun isWatchlistWidget(context: android.content.Context, appWidgetId: Int): Boolean {
            val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)
            return info?.provider?.className == WatchlistWidgetReceiver::class.java.name
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigScreen(
    appWidgetId: Int,
    onCancel: () -> Unit,
    onConfirm: (TickerWidgetConfig) -> Unit,
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(TickerWidgetConfig()) }
    // When reconfiguring an existing widget, seed the form with its saved config.
    LaunchedEffect(appWidgetId) {
        runCatching {
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
            if (prefs.contains(TickerWidgetState.CONFIG)) {
                config = TickerWidgetState.readConfig(prefs)
            }
        }
    }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    // The preview used to paint a fixed dark card regardless of the app-wide background setting, so
    // once that setting was anything else the preview was showing a widget the user was not about
    // to get. Background is not configured here -- it lives in Settings and applies to all widgets.
    val backgroundArgb by ServiceLocator.settingsStore.widgetBackgroundArgb
        .collectAsState(initial = WidgetBackground.DEFAULT_ARGB)
    val backgroundTransparency by ServiceLocator.settingsStore.widgetBackgroundTransparency
        .collectAsState(initial = WidgetBackground.DEFAULT_TRANSPARENCY)

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
            item { WidgetPreview(config, backgroundArgb, backgroundTransparency) }

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
private fun WidgetPreview(
    config: TickerWidgetConfig,
    backgroundArgb: Long = WidgetBackground.DEFAULT_ARGB,
    backgroundTransparency: Int = WidgetBackground.DEFAULT_TRANSPARENCY,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(
                    Color(WidgetBackground.argbWith(backgroundArgb, backgroundTransparency)),
                    RoundedCornerShape(20.dp),
                )
                .padding(16.dp)
                .width(150.dp),
        ) {
            if (config.showName) {
                Text(config.displayName, color = OnSurfaceVariantDark, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            Text(config.symbol, color = Color(config.accentArgb.toInt()), fontWeight = FontWeight.Bold)
            Text(Formatting.price(229.14), color = OnSurfaceDark, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
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

// -----------------------------------------------------------------------------------------------
// Watchlist widget configuration (WGT-5)
// -----------------------------------------------------------------------------------------------

private val SORT_ORDER_LABELS = listOf(
    WatchlistSortOrder.MANUAL to "Manual",
    WatchlistSortOrder.ALPHABETICAL to "A–Z",
    WatchlistSortOrder.CHANGE_DESC to "Top gainers",
    WatchlistSortOrder.CHANGE_ASC to "Top losers",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchlistWidgetConfigScreen(
    appWidgetId: Int,
    onCancel: () -> Unit,
    onConfirm: (WatchlistWidgetConfig) -> Unit,
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(WatchlistWidgetConfig()) }
    // When reconfiguring an existing widget, seed the form with its saved config.
    LaunchedEffect(appWidgetId) {
        runCatching {
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
            if (prefs.contains(WatchlistWidgetState.CONFIG)) {
                config = WatchlistWidgetState.readConfig(prefs)
            }
        }
    }

    val backgroundArgb by ServiceLocator.settingsStore.widgetBackgroundArgb
        .collectAsState(initial = WidgetBackground.DEFAULT_ARGB)
    val backgroundTransparency by ServiceLocator.settingsStore.widgetBackgroundTransparency
        .collectAsState(initial = WidgetBackground.DEFAULT_TRANSPARENCY)
    // The user's own named lists (Asset.groups), on top of the three built-ins -- a group they
    // create after this widget is already configured just doesn't show up here until reopened;
    // there is no live list of "in-use" group names to watch instead.
    val groups by ServiceLocator.settingsStore.watchlistGroups.collectAsState(initial = emptyList())
    val lists = WatchlistWidgetConfig.BUILTIN_LISTS + groups.filterNot { it in WatchlistWidgetConfig.BUILTIN_LISTS }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configure Watchlist") },
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
            item { WatchlistWidgetPreview(config, backgroundArgb, backgroundTransparency) }

            item { SectionHeader("List") }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lists.forEach { name ->
                        FilterChip(
                            selected = config.listName == name,
                            onClick = { config = config.copy(listName = name) },
                            label = { Text(name) },
                        )
                    }
                }
            }

            item { SectionHeader("Sort") }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SORT_ORDER_LABELS.forEach { (order, label) ->
                        FilterChip(
                            selected = config.sortOrder == order,
                            onClick = { config = config.copy(sortOrder = order) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            item { SectionHeader("Change column") }
            item {
                ToggleRow("Show dollar change instead of %", config.valueMode == WatchlistValueMode.DOLLAR) { checked ->
                    config = config.copy(valueMode = if (checked) WatchlistValueMode.DOLLAR else WatchlistValueMode.PERCENT)
                }
            }

            item { SectionHeader("Accent") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WatchlistWidgetConfig.ACCENT_CHOICES.forEach { argb ->
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
                    WatchlistWidgetConfig.REFRESH_CHOICES.forEach { minutes ->
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
private fun WatchlistWidgetPreview(
    config: WatchlistWidgetConfig,
    backgroundArgb: Long = WidgetBackground.DEFAULT_ARGB,
    backgroundTransparency: Int = WidgetBackground.DEFAULT_TRANSPARENCY,
) {
    // Fabricated rows -- purely to show what the accent + dollar/percent choice will look like.
    // The real widget's own rows come from the user's actual (per-instance) list once it's placed.
    val sample = listOf(Triple("AAPL", 229.14, 1.20), Triple("BTC", 64213.0, -0.82))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(
                    Color(WidgetBackground.argbWith(backgroundArgb, backgroundTransparency)),
                    RoundedCornerShape(20.dp),
                )
                .padding(16.dp)
                .width(220.dp),
        ) {
            Text("Watchlist", color = Color(config.accentArgb.toInt()), fontWeight = FontWeight.Bold)
            watchlistListLabel(config.listName)?.let {
                Text(it, color = OnSurfaceVariantDark, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(6.dp))
            sample.forEach { (symbol, price, pct) ->
                val up = pct >= 0.0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(symbol, color = OnSurfaceDark, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Text(
                        Formatting.price(price),
                        color = OnSurfaceDark,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (config.valueMode == WatchlistValueMode.DOLLAR) {
                            Formatting.change(price * pct / 100.0)
                        } else {
                            "${Formatting.arrow(up)} ${Formatting.percent(pct)}"
                        },
                        color = if (up) GainGreen else LossRed,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
