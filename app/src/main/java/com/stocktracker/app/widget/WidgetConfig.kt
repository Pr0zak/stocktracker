package com.stocktracker.app.widget

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import kotlinx.serialization.Serializable

/** Per-widget configuration for a single-ticker widget. Stored in that widget's Glance state. */
@Serializable
data class TickerWidgetConfig(
    val symbol: String = "AAPL",
    val type: AssetType = AssetType.STOCK,
    val displayName: String = "Apple Inc.",
    val coinGeckoId: String? = null,
    val showChangePercent: Boolean = true,
    val showSparkline: Boolean = true,
    val showName: Boolean = false,
    val accentArgb: Long = 0xFFB4A0FF,
    val refreshMinutes: Int = 15,
) {
    fun toAsset() = Asset(symbol = symbol, type = type, displayName = displayName, coinGeckoId = coinGeckoId)

    companion object {
        val ACCENT_CHOICES = listOf(
            0xFFB4A0FFL, // indigo
            0xFF4ADE80L, // green
            0xFF38BDF8L, // sky
            0xFFFB923CL, // orange
            0xFFF472B6L, // pink
        )
        val REFRESH_CHOICES = listOf(15, 30, 60, 120)
    }
}
