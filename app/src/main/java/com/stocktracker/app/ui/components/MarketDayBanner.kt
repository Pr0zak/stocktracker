package com.stocktracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.notify.MarketHoursText
import com.stocktracker.app.ui.theme.Signal
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A thin strip across the top of the app on days the stock or bond market is closed or closes
 * early, and the evening before stocks do — the in-app twin of the market-hours notification.
 *
 * Sits in the root Scaffold's top bar, so it takes the status-bar inset itself; the screens below
 * see that inset as consumed and do not pad for it twice. Dismissing hides that one message only;
 * a different message (tomorrow's, say) shows again.
 */
@Composable
fun MarketDayBanner() {
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var dismissed by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            message = MarketHoursText.banner(ZonedDateTime.now(), ZoneId.systemDefault())
            delay(60_000)
        }
    }
    val text = message ?: return
    if (text == dismissed) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Signal.copy(alpha = 0.16f))
            .statusBarsPadding()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.EventBusy, contentDescription = null, tint = Signal, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Signal,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        IconButton(onClick = { dismissed = text }) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Signal, modifier = Modifier.size(16.dp))
        }
    }
}
