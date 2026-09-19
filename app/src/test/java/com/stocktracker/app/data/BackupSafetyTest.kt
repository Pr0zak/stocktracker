package com.stocktracker.app.data

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Lot
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards on the one operation in this app that can destroy data the user typed in by hand.
 *
 * The watchlist holds shares and cost basis that exist nowhere else — not on the broker, not on a
 * server. `importFrom` replaces it wholesale. Before this test existed, ANY syntactically valid JSON
 * object decoded cleanly into an empty `BackupData` (every field defaulted, and the shared
 * `Http.json` is configured `ignoreUnknownKeys` + `isLenient` + `coerceInputValues`), so picking the
 * wrong file in the document picker silently wiped every holding.
 */
class BackupSafetyTest {

    private val notBackups = listOf(
        "{}",
        """{"hello":"world"}""",
        """{"version":2,"tickers":[{"symbol":"AAPL"}]}""",
        """{"assets":null}""",
        """{"name":"something else","items":[1,2,3]}""",
    )

    @Test
    fun `arbitrary json is rejected instead of decoding to an empty backup`() {
        for (text in notBackups) {
            val parsed = runCatching { BackupManager.parseBackup(text) }
            assertTrue(
                "a non-backup file was accepted and would have wiped the watchlist: $text",
                parsed.isFailure,
            )
        }
    }

    @Test
    fun `the raw decode really was permissive - guards the premise of this test`() {
        // If this ever starts failing, kotlinx or Http.json changed and the test above may no longer
        // be exercising the risk it was written for.
        for (text in notBackups) {
            val direct = runCatching { Http.json.decodeFromString<BackupData>(text) }
            assertTrue("expected the permissive decode to succeed for $text", direct.isSuccess)
            assertTrue("expected it to yield an EMPTY backup", direct.getOrThrow().assets.isEmpty())
        }
    }

    @Test
    fun `a real backup round-trips including calls, closed calls and cash`() {
        val original = BackupData(
            assets = emptyList(),
            groups = listOf("Core", "Speculative"),
            calls = emptyList(),
            closedCalls = emptyList(),
            investableCash = 2500.0,
        )
        val text = BackupManager.encodeBackup(original)
        val back = BackupManager.parseBackup(text)
        assertEquals(original.groups, back.groups)
        assertEquals(2500.0, back.investableCash, 0.001)
    }

    @Test
    fun `the exported file carries an explicit format marker`() {
        val text = BackupManager.encodeBackup(BackupData(groups = listOf("A")))
        val obj = Json.parseToJsonElement(text)
        assertTrue("export must be self-identifying", text.contains(BackupManager.FORMAT))
        assertFalse(obj.toString().isEmpty())
    }

    @Test
    fun `a legacy backup without the marker is still accepted`() {
        // Files written before the marker existed carry an `assets` array and nothing else.
        val legacy = """{"version":1,"assets":[],"groups":["Old"]}"""
        val back = BackupManager.parseBackup(legacy)
        assertEquals(listOf("Old"), back.groups)
    }

    // ---------------------------------------------------------------------------------- MONEY-2

    @Test
    fun `a real backup round-trips purchase lots through the actual export-import codec`() {
        val original = BackupData(
            assets = listOf(
                Asset(
                    symbol = "AAPL", type = AssetType.STOCK, displayName = "Apple Inc.",
                    lots = listOf(
                        Lot(shares = 5.0, costPerShare = 150.0, acquiredDateIso = "2024-03-01"),
                        Lot(shares = 5.0, costPerShare = 170.0, acquiredDateIso = "2025-01-10"),
                    ),
                ),
            ),
        )
        val text = BackupManager.encodeBackup(original)
        val back = BackupManager.parseBackup(text)
        assertEquals(original.assets, back.assets)
        assertEquals(10.0, back.assets[0].shares!!, 0.0001)
        assertEquals(160.0, back.assets[0].avgCost!!, 0.0001)
    }

    @Test
    fun `a backup taken before lots existed imports its shares+avgCost as one undated lot`() {
        // The real shape of every backup exported before MONEY-2: no "lots" key at all.
        val legacy = """
            {"format":"${BackupManager.FORMAT}","assets":[
                {"symbol":"TSLA","type":"STOCK","displayName":"Tesla","shares":4.0,"avgCost":220.0}
            ]}
        """.trimIndent()
        val back = BackupManager.parseBackup(legacy)
        val asset = back.assets.single()
        assertEquals(1, asset.lots.size)
        assertEquals(4.0, asset.lots[0].shares, 0.0001)
        assertEquals(220.0, asset.lots[0].costPerShare!!, 0.0001)
        assertNull("a position with no recorded fill history has an unknown acquisition date", asset.lots[0].acquiredDateIso)
        assertEquals(4.0, asset.shares!!, 0.0001)
        assertEquals(220.0, asset.avgCost!!, 0.0001)
    }
}
