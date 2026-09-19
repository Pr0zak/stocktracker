package com.stocktracker.app.model

import com.stocktracker.app.data.BackupData
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetAlerts
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Lot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `backup round-trips assets with lots, alerts, groups`() {
        val original = BackupData(
            assets = listOf(
                Asset(
                    symbol = "AAPL", type = AssetType.STOCK, displayName = "Apple Inc.",
                    lots = listOf(
                        Lot(shares = 2.0, costPerShare = 180.0, acquiredDateIso = "2025-01-15"),
                        Lot(shares = 1.0, costPerShare = 210.0, acquiredDateIso = "2025-06-02"),
                    ),
                    alerts = AssetAlerts(priceAbove = 250.0), groups = listOf("Tech", "Core"),
                ),
                Asset(
                    symbol = "BTC", type = AssetType.CRYPTO, displayName = "Bitcoin",
                    coinGeckoId = "bitcoin", lots = listOf(Lot(shares = 0.5, costPerShare = null, acquiredDateIso = null)),
                ),
            ),
            groups = listOf("Tech", "Core"),
        )
        val restored = json.decodeFromString<BackupData>(json.encodeToString(original))
        assertEquals(original, restored)
        // And the derived numbers survive the round trip along with the raw lots.
        assertEquals(3.0, restored.assets[0].shares!!, 0.0001)
        assertEquals(190.0, restored.assets[0].avgCost!!, 0.0001) // (2*180 + 1*210) / 3
        assertEquals(0.5, restored.assets[1].shares!!, 0.0001)
        assertNull("a lot with an unknown cost must not report a fabricated average", restored.assets[1].avgCost)
    }

    @Test fun `old backup without new fields still decodes`() {
        // Simulates a file exported before avgCost/groups existed.
        val legacy = """{"version":1,"assets":[{"symbol":"MSFT","type":"STOCK","displayName":"Microsoft"}]}"""
        val data = json.decodeFromString<BackupData>(legacy)
        assertEquals(1, data.assets.size)
        assertEquals(null, data.assets[0].avgCost)
        assertEquals(emptyList<String>(), data.assets[0].groups)
        assertTrue("no shares recorded must mean no lots at all", data.assets[0].lots.isEmpty())
    }

    @Test fun `a pre-MONEY-2 backup with shares and avgCost migrates to one undated lot`() {
        // The exact shape every backup taken before lots existed is in: a bare shares/avgCost pair,
        // no "lots" key anywhere.
        val legacy = """
            {"version":2,"assets":[
                {"symbol":"NVDA","type":"STOCK","displayName":"NVIDIA","shares":10.0,"avgCost":120.5}
            ]}
        """.trimIndent()
        val data = json.decodeFromString<BackupData>(legacy)
        val asset = data.assets.single()
        assertEquals(1, asset.lots.size)
        assertEquals(10.0, asset.lots[0].shares, 0.0001)
        assertEquals(120.5, asset.lots[0].costPerShare!!, 0.0001)
        assertNull("a migrated lot's acquisition date is UNKNOWN, never invented", asset.lots[0].acquiredDateIso)
        // And the derived scalars still read exactly as they would have pre-MONEY-2.
        assertEquals(10.0, asset.shares!!, 0.0001)
        assertEquals(120.5, asset.avgCost!!, 0.0001)
    }
}
