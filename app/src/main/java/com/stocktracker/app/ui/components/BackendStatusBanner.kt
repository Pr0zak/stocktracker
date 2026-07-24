package com.stocktracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.SignalsHealth
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The single, consistent "can't reach the AI service" indicator.
 *
 * The backend is self-hosted, so the phone loses it routinely — off the home network, VPN down, service
 * restarting. Without this, every AI feature just silently returned nothing and looked broken. Shows
 * only when a URL IS configured and the service is unreachable (an unconfigured service isn't an error,
 * it's an unused feature). Tap to retry immediately.
 */
@Composable
fun BackendStatusBanner(modifier: Modifier = Modifier) {
    val health by SignalsHealth.state.collectAsState()
    if (!health.isOffline) return

    val scope = rememberCoroutineScope()
    val red = Color(0xFFC64040)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(red.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .clickable(enabled = !health.checking) { scope.launch { SignalsHealth.check() } }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (health.checking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = red)
            } else {
                Icon(Icons.Filled.CloudOff, contentDescription = null, tint = red, modifier = Modifier.size(18.dp))
            }
            Text(
                if (health.checking) "Reconnecting…" else "AI service unreachable",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = red,
            )
        }
        Text(
            buildString {
                append(health.lastError ?: "Can't reach the Signals service")
                append(" · ")
                append(lastSeen(health.lastOkAt))
                if (!health.checking) append(" · tap to retry")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "AI features (verdicts, ideas, sandbox) are paused until it's back. Prices still work.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun lastSeen(lastOkAt: Long): String {
    if (lastOkAt <= 0L) return "never connected"
    val ms = System.currentTimeMillis() - lastOkAt
    val mins = TimeUnit.MILLISECONDS.toMinutes(ms)
    val hrs = TimeUnit.MILLISECONDS.toHours(ms)
    return when {
        mins < 1 -> "last seen just now"
        mins < 60 -> "last seen ${mins}m ago"
        hrs < 24 -> "last seen ${hrs}h ago"
        else -> "last seen ${TimeUnit.MILLISECONDS.toDays(ms)}d ago"
    }
}
