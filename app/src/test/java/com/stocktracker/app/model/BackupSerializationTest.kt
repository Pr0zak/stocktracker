package com.stocktracker.app.model

import com.stocktracker.app.data.BackupData
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetAlerts
import com.stocktracker.app.data.model.AssetType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `backup round-trips assets with holdings, cost, alerts, groups`() {
        val original = BackupData(
            assets = listOf(
                Asset(
                    symbol = "AAPL", type = AssetType.STOCK, displayName = "Apple Inc.",
                    shares = 3.0, avgCost = 190.5,
                    alerts = AssetAlerts(priceAbove = 250.0), groups = listOf("Tech", "Core"),
                ),
                Asset(
                    symbol = "BTC", type = AssetType.CRYPTO, displayName = "Bitcoin",
                    coinGeckoId = "bitcoin", shares = 0.5,
                ),
            ),
            groups = listOf("Tech", "Core"),
        )
        val restored = json.decodeFromString<BackupData>(json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test fun `old backup without new fields still decodes`() {
        // Simulates a file exported before avgCost/groups existed.
        val legacy = """{"version":1,"assets":[{"symbol":"MSFT","type":"STOCK","displayName":"Microsoft"}]}"""
        val data = json.decodeFromString<BackupData>(legacy)
        assertEquals(1, data.assets.size)
        assertEquals(null, data.assets[0].avgCost)
        assertEquals(emptyList<String>(), data.assets[0].groups)
    }
}
