package com.stocktracker.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
) {
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
            Column(modifier = Modifier.weight(1f)) {
                Text(symbol, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
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
        }
    }
}

@Composable
private fun Spacer12() {
    androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
}

