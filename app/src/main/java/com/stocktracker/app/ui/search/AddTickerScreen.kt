package com.stocktracker.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.SearchResult
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.widget.WidgetRefreshScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Curated "watch for dips" starter list — core ETFs + wide-moat compounders. Added alert-only
 *  (no shares) so they simply populate the watchlist for the buy-the-dip radar. */
private val STARTER_WATCHLIST = listOf(
    Asset("VOO", AssetType.STOCK, "Vanguard S&P 500 ETF"),
    Asset("VTI", AssetType.STOCK, "Vanguard Total Stock Market ETF"),
    Asset("VXUS", AssetType.STOCK, "Vanguard Total International Stock ETF"),
    Asset("QQQM", AssetType.STOCK, "Invesco NASDAQ 100 ETF"),
    Asset("MSFT", AssetType.STOCK, "Microsoft Corporation"),
    Asset("AAPL", AssetType.STOCK, "Apple Inc."),
    Asset("GOOGL", AssetType.STOCK, "Alphabet Inc."),
    Asset("AMZN", AssetType.STOCK, "Amazon.com, Inc."),
    Asset("NVDA", AssetType.STOCK, "NVIDIA Corporation"),
    Asset("V", AssetType.STOCK, "Visa Inc."),
    Asset("MA", AssetType.STOCK, "Mastercard Incorporated"),
    Asset("COST", AssetType.STOCK, "Costco Wholesale"),
    Asset("UNH", AssetType.STOCK, "UnitedHealth Group"),
    Asset("LLY", AssetType.STOCK, "Eli Lilly and Company"),
    Asset("HD", AssetType.STOCK, "The Home Depot"),
    Asset("JNJ", AssetType.STOCK, "Johnson & Johnson"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTickerScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        results = runCatching { ServiceLocator.repository.search(query) }.getOrDefault(emptyList())
        searching = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add ticker") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search ticker or crypto") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )

            if (searching) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }

            // One-tap curated starter list when the search is empty — a ready-made "watch for dips" set.
            if (query.isBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                STARTER_WATCHLIST.forEach { ServiceLocator.watchlistStore.add(it) }
                                WidgetRefreshScheduler.refreshNow(context)
                                onBack()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("＋  Add starter watchlist (Core & Quality)") }
                    Text(
                        "16 core ETFs + wide-moat names to watch for dips: VOO, VTI, VXUS, QQQM, MSFT, " +
                            "AAPL, GOOGL, AMZN, NVDA, V, MA, COST, UNH, LLY, HD, JNJ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results) { result ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    ServiceLocator.watchlistStore.add(result.toAsset())
                                    WidgetRefreshScheduler.refreshNow(context)
                                    onBack()
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(result.symbol, fontWeight = FontWeight.Bold)
                            Text(
                                result.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Text(
                            if (result.type == AssetType.CRYPTO) "Crypto" else "Stock",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
