package com.stocktracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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

/**
 * The single, consistent "can't reach the AI service" indicator.
 *
 * The backend is self-hosted, so the phone loses it routinely — off the home network, VPN down, service
 * restarting. Without this, every AI feature just silently returned nothing and looked broken.
 *
 * Deliberately ONE compact line: this sits above real content on several screens, so it states the fact
 * and the remedy and nothing else. The specific cause is still tracked in SignalsHealth.lastError for
 * debugging. Shows only when a URL IS configured and unreachable — an unconfigured service isn't an
 * error, it's an unused feature.
 */
@Composable
fun BackendStatusBanner(modifier: Modifier = Modifier) {
    val health by SignalsHealth.state.collectAsState()
    if (!health.isOffline) return

    val scope = rememberCoroutineScope()
    val red = Color(0xFFC64040)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(red.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .clickable(enabled = !health.checking) { scope.launch { SignalsHealth.check() } }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (health.checking) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = red)
        } else {
            Icon(Icons.Filled.CloudOff, contentDescription = null, tint = red, modifier = Modifier.size(16.dp))
        }
        Text(
            if (health.checking) "Reconnecting…" else "Backend offline · tap to retry",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = red,
        )
    }
}
