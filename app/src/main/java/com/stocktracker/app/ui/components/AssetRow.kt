package com.stocktracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.ui.theme.CryptoAccent
import com.stocktracker.app.ui.theme.EtfAccent
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.PriceMedium

@Composable
fun AssetRow(
    symbol: String,
    name: String,
    priceText: String,
    changeText: String,
    up: Boolean,
    sparkline: List<Double>,
    onClick: () -> Unit,
    holdingsText: String? = null,
    isCrypto: Boolean = false,
    isEtf: Boolean = false,
    belowLine: Boolean = false,
    showDragHandle: Boolean = false,
) {
    // Crypto takes precedence over ETF; equities get no accent.
    val accent: Color? = if (isCrypto) CryptoAccent else if (isEtf) EtfAccent else null
    Card(
        onClick = onClick,
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
            // Leading accent stripe marks crypto (amber) and ETF (teal) rows for quick visual
            // separation from single equities.
            if (accent != null) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(36.dp)
                        .background(accent, RoundedCornerShape(2.dp)),
                )
                Spacer12()
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        symbol,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = accent ?: MaterialTheme.colorScheme.onSurface,
                    )
                    // Amber "below its 200-week line" marker — long-term value context, not a buy flag.
                    if (belowLine) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "200w",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD29922),
                            modifier = Modifier
                                .background(Color(0xFFD29922).copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (holdingsText != null) {
                    Text(
                        holdingsText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }

            if (sparkline.size >= 2) {
                Sparkline(
                    values = sparkline,
                    up = up,
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp),
                )
                Spacer12()
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(priceText, style = PriceMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    changeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (up) GainGreen else LossRed,
                    fontWeight = FontWeight.Medium,
                )
            }
            // Drag-to-reorder affordance, shown only in the reorderable "All" tab. The whole row is
            // the long-press-drag handle; this icon makes that discoverable.
            if (showDragHandle) {
                Spacer12()
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Spacer12() {
    androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
}

