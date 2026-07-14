package com.stocktracker.app.ui.gallery

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.widget.WidgetPinning

private enum class WidgetKind { TICKER, WATCHLIST }

private data class GalleryEntry(
    val title: String,
    val description: String,
    val kind: WidgetKind,
    val preview: @Composable () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetGalleryScreen() {
    val context = LocalContext.current
    val entries = listOf(
        GalleryEntry("Single ticker", "One stock, resizable 2×1 bubble.", WidgetKind.TICKER) {
            TickerMock("AAPL", "$229.14", "▲ +1.20%", GainGreen)
        },
        GalleryEntry("Crypto", "Track BTC, ETH, and more.", WidgetKind.TICKER) {
            TickerMock("BTC", "$94,210", "▼ -0.78%", LossRed)
        },
        GalleryEntry("Ticker + chart", "Resize a ticker to 2×2 for a sparkline.", WidgetKind.TICKER) {
            TickerMock("ETH", "$3,180.00", "▲ +1.53%", GainGreen)
        },
        GalleryEntry("Watchlist", "All your tracked tickers in one 4×2 tile.", WidgetKind.WATCHLIST) {
            WatchlistMock()
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Widgets")
                    Text(
                        "Pick a layout, then place it on your home screen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries) { entry ->
                GalleryCard(entry) {
                    val ok = when (entry.kind) {
                        WidgetKind.TICKER -> WidgetPinning.requestPinTicker(context)
                        WidgetKind.WATCHLIST -> WidgetPinning.requestPinWatchlist(context)
                    }
                    if (!ok) {
                        Toast.makeText(
                            context,
                            "Long-press your home screen → Widgets → StockTracker",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryCard(entry: GalleryEntry, onAdd: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(120.dp)) { entry.preview() }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            ) {
                Text(entry.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onAdd, modifier = Modifier.padding(top = 8.dp)) { Text("Add") }
            }
        }
    }
}

@Composable
private fun TickerMock(symbol: String, price: String, change: String, changeColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(0.dp),
            ) {
                Text(symbol, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(price, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(change, color = changeColor, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WatchlistMock() {
    Column(modifier = Modifier.fillMaxWidth()) {
        MockRow("NVDA", "▲ +2.97%", GainGreen)
        MockRow("MSFT", "▲ +0.24%", GainGreen)
        MockRow("TSLA", "▼ -2.58%", LossRed)
    }
}

@Composable
private fun MockRow(symbol: String, change: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(symbol, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Text(change, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
