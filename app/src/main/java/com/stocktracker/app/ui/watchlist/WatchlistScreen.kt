package com.stocktracker.app.ui.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.ui.components.AssetRow
import com.stocktracker.app.util.Formatting

private enum class Filter(val label: String) { ALL("All"), STOCKS("Stocks"), CRYPTO("Crypto") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    onOpenDetail: (Asset) -> Unit,
    onAdd: () -> Unit,
) {
    val vm: WatchlistViewModel = viewModel()
    val state by vm.state.collectAsState()
    var filter by remember { mutableStateOf(Filter.ALL) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StockTracker") },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add ticker")
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { innerPadding ->
        val filtered = state.items.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.STOCKS -> it.asset.type == AssetType.STOCK
                Filter.CRYPTO -> it.asset.type == AssetType.CRYPTO
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Filter.entries.forEach { f ->
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                label = { Text(f.label) },
                            )
                        }
                    }
                }

                if (!state.stocksEnabled) {
                    item {
                        Text(
                            "Add a Finnhub API key to enable live stock quotes (see README). Crypto works without a key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(filtered, key = { it.asset.id }) { item ->
                    val q = item.quote
                    val up = q?.isUp ?: true
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value != SwipeToDismissBoxValue.Settled) {
                                vm.remove(item.asset)
                                true
                            } else {
                                false
                            }
                        },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = { RemoveBackground() },
                    ) {
                        AssetRow(
                            symbol = item.asset.symbol,
                            name = item.asset.displayName,
                            priceText = q?.let { Formatting.price(it.price, it.currency) } ?: "—",
                            changeText = q?.let { Formatting.changeLine(it.change, it.changePercent, it.isUp) } ?: "…",
                            up = up,
                            sparkline = item.sparkline,
                            onClick = { onOpenDetail(item.asset) },
                        )
                    }
                }
            }

            if (state.loading && state.items.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun RemoveBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(20.dp))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = "Remove",
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
