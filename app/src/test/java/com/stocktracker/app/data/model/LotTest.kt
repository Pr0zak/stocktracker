package com.stocktracker.app.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MONEY-2: a holding is a list of [Lot]s now, not a bare shares+avgCost pair. [Asset.shares] and
 * [Asset.avgCost] are derived from [Asset.lots] — these tests pin the arithmetic, the pre-MONEY-2
 * migration-on-decode, and the "unknown, not zero" handling of a lot with no recorded cost or date.
 */
class LotTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun stock(vararg lots: Lot) = Asset(
        symbol = "AAPL", type = AssetType.STOCK, displayName = "Apple Inc.", lots = lots.toList(),
    )

    // ---------------------------------------------------------------------------- derived shares

    @Test fun `shares sums every lot`() {
        val asset = stock(
            Lot(shares = 10.0, costPerShare = 100.0, acquiredDateIso = "2024-01-01"),
            Lot(shares = 5.0, costPerShare = 120.0, acquiredDateIso = "2024-06-01"),
            Lot(shares = 2.5, costPerShare = 130.0, acquiredDateIso = "2025-01-01"),
        )
        assertEquals(17.5, asset.shares!!, 0.0001)
    }

    @Test fun `no lots means no position - null, not zero`() {
        val asset = stock()
        assertNull(asset.shares)
        assertNull(asset.avgCost)
    }

    // --------------------------------------------------------------------------- derived avgCost

    @Test fun `avgCost is the share-weighted average across lots`() {
        val asset = stock(
            Lot(shares = 10.0, costPerShare = 100.0, acquiredDateIso = "2024-01-01"),
            Lot(shares = 30.0, costPerShare = 140.0, acquiredDateIso = "2024-06-01"),
        )
        // (10*100 + 30*140) / 40 = (1000 + 4200) / 40 = 130
        assertEquals(130.0, asset.avgCost!!, 0.0001)
    }

    @Test fun `a single lot's avgCost is just its own cost`() {
        val asset = stock(Lot(shares = 3.0, costPerShare = 190.5, acquiredDateIso = null))
        assertEquals(190.5, asset.avgCost!!, 0.0001)
        assertEquals(3.0, asset.shares!!, 0.0001)
    }

    @Test fun `any lot with an unknown cost makes the blended average unknown, not partial`() {
        // Blending a real cost against a missing one would silently treat the unpriced lot as free
        // and understate the true basis -- this project refuses to print that confident-looking
        // wrong number, so ANY unknown cost poisons the whole average, not just its own share.
        val asset = stock(
            Lot(shares = 10.0, costPerShare = 100.0, acquiredDateIso = "2024-01-01"),
            Lot(shares = 10.0, costPerShare = null, acquiredDateIso = "2024-06-01"), // exercised call, cost unclear
        )
        assertNull(asset.avgCost)
        // Shares are still known even though the cost basis is not.
        assertEquals(20.0, asset.shares!!, 0.0001)
    }

    @Test fun `a lot with a null acquisition date does not disturb the arithmetic`() {
        val asset = stock(
            Lot(shares = 4.0, costPerShare = 50.0, acquiredDateIso = null), // migrated legacy lot
            Lot(shares = 6.0, costPerShare = 70.0, acquiredDateIso = "2025-03-15"), // a real fill
        )
        assertEquals(10.0, asset.shares!!, 0.0001)
        assertEquals(62.0, asset.avgCost!!, 0.0001) // (4*50 + 6*70) / 10
        assertNull(asset.lots[0].acquiredDateIso)
        assertEquals("2025-03-15", asset.lots[1].acquiredDateIso)
    }

    // -------------------------------------------------------------------- migration on decode

    @Test fun `an Asset persisted in the pre-MONEY-2 shape loads as one lot with a null date`() {
        val legacy = """{"symbol":"MSFT","type":"STOCK","displayName":"Microsoft","shares":8.0,"avgCost":310.25}"""
        val asset = json.decodeFromString<Asset>(legacy)
        assertEquals(1, asset.lots.size)
        assertEquals(8.0, asset.lots[0].shares, 0.0001)
        assertEquals(310.25, asset.lots[0].costPerShare!!, 0.0001)
        assertNull("an absent purchase date must decode as unknown, never a guessed default", asset.lots[0].acquiredDateIso)
        assertEquals(8.0, asset.shares!!, 0.0001)
        assertEquals(310.25, asset.avgCost!!, 0.0001)
    }

    @Test fun `a pre-MONEY-2 Asset with shares but no avgCost migrates with an unknown cost`() {
        val legacy = """{"symbol":"MSFT","type":"STOCK","displayName":"Microsoft","shares":8.0}"""
        val asset = json.decodeFromString<Asset>(legacy)
        assertEquals(1, asset.lots.size)
        assertEquals(8.0, asset.lots[0].shares, 0.0001)
        assertNull(asset.lots[0].costPerShare)
        assertNull(asset.avgCost)
        assertEquals(8.0, asset.shares!!, 0.0001)
    }

    @Test fun `a pre-MONEY-2 Asset with no position at all migrates to zero lots`() {
        for (legacy in listOf(
            """{"symbol":"MSFT","type":"STOCK","displayName":"Microsoft"}""",
            """{"symbol":"MSFT","type":"STOCK","displayName":"Microsoft","shares":0.0,"avgCost":100.0}""",
        )) {
            val asset = json.decodeFromString<Asset>(legacy)
            assertTrue("expected no lots for: $legacy", asset.lots.isEmpty())
            assertNull(asset.shares)
            assertNull(asset.avgCost)
        }
    }

    @Test fun `a lots-shape Asset is not re-migrated from stray legacy keys`() {
        // If a "lots" array is present it is authoritative -- a stray/stale "shares" key alongside it
        // (as an old external tool or a hand-edited file might carry) must NOT add a phantom lot.
        val mixed = """
            {"symbol":"MSFT","type":"STOCK","displayName":"Microsoft",
             "lots":[{"shares":2.0,"costPerShare":300.0,"acquiredDateIso":"2025-05-01"}],
             "shares":999.0,"avgCost":1.0}
        """.trimIndent()
        val asset = json.decodeFromString<Asset>(mixed)
        assertEquals(1, asset.lots.size)
        assertEquals(2.0, asset.shares!!, 0.0001)
        assertEquals(300.0, asset.avgCost!!, 0.0001)
    }

    // ------------------------------------------------------------------------------ round trip

    @Test fun `encode-decode round trip preserves every lot exactly, including a null date`() {
        val original = stock(
            Lot(shares = 4.0, costPerShare = 50.0, acquiredDateIso = null),
            Lot(shares = 6.0, costPerShare = 70.0, acquiredDateIso = "2025-03-15"),
        )
        val restored = json.decodeFromString<Asset>(json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(original.lots, restored.lots)
    }

    @Test fun `a migrated legacy asset re-serializes to the lots shape and is idempotent on a second decode`() {
        val legacy = """{"symbol":"MSFT","type":"STOCK","displayName":"Microsoft","shares":8.0,"avgCost":310.25}"""
        val once = json.decodeFromString<Asset>(legacy)
        val encoded = json.encodeToString(once)
        val twice = json.decodeFromString<Asset>(encoded)
        assertEquals(once, twice)
        assertEquals(1, twice.lots.size) // decoding an already-migrated asset again must not double it up
    }
}
