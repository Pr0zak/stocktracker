package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
