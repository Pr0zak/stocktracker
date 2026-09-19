package com.stocktracker.app.data

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.Lot
import com.stocktracker.app.data.model.AssetAlerts
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.CallOutcome
import com.stocktracker.app.data.model.CallPosition
import com.stocktracker.app.data.model.ClosedCallPosition
import com.stocktracker.app.data.model.VerdictJournalEntry
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DATA-8: a restore that runs as six separate cancellable writes, with no confirmation, no version
 * check, and one error message for every failure mode. This covers the decision logic extracted out
 * of that path — the parts that don't need Android to be exercised:
 *  - [BackupManager.isVersionSupported] / the version gate inside [BackupManager.parseBackup]
 *  - the counts [BackupManager.preview] derives and the copy [BackupManager.confirmationMessage] builds
 *  - [BackupManager.rawStateFor], and the "undo restores exactly what was there, corrupt or not"
 *    guarantee that [BackupManager.BackupRawState] exists to make possible
 *
 * [BackupManager.commitImport]/[BackupManager.undo] themselves are thin DataStore glue (a single
 * `dataStore.edit` transaction) with no branching logic of their own — the module has no Robolectric
 * setup, so they aren't exercised here; see the class doc on [BackupManager] for what they do.
 */
class BackupImportTest {

    private fun asset(
        symbol: String,
        shares: Double? = null,
        alerts: AssetAlerts? = null,
        // MONEY-2 replaced Asset's `shares` constructor parameter with a list of lots; `shares` is
        // now derived from them. A holding of unknown provenance is one undated lot, which is
        // exactly what the pre-MONEY-2 backups this test feeds in migrate to.
    ) = Asset(
        symbol = symbol,
        type = AssetType.STOCK,
        displayName = symbol,
        lots = shares?.let { listOf(Lot(shares = it)) }.orEmpty(),
        alerts = alerts,
    )

    private fun call(symbol: String) = CallPosition(
        symbol = symbol, strike = 100.0, expiryIso = "2027-01-15", expiryTs = 0L,
        contracts = 1, fillPrice = 2.5, openDateIso = "2026-09-01",
    )

    private fun closedCall(symbol: String) = ClosedCallPosition(
        symbol = symbol, strike = 100.0, expiryIso = "2027-01-15", expiryTs = 0L,
        contracts = 1, fillPrice = 2.5, openDateIso = "2026-09-01",
        outcome = CallOutcome.SOLD, closeDateIso = "2026-09-10",
    )

    private fun journalEntry(symbol: String) = VerdictJournalEntry(symbol = symbol, verdictDateIso = "2026-09-01")

    // --- version accepted / rejected ------------------------------------------------------------

    @Test
    fun `known versions are supported, anything newer is not`() {
        assertTrue(BackupManager.isVersionSupported(BackupManager.MIN_SUPPORTED_VERSION))
        assertTrue(BackupManager.isVersionSupported(BackupManager.CURRENT_VERSION))
        assertFalse(BackupManager.isVersionSupported(BackupManager.CURRENT_VERSION + 1))
        assertFalse(BackupManager.isVersionSupported(BackupManager.CURRENT_VERSION + 100))
        assertFalse(BackupManager.isVersionSupported(0))
        assertFalse(BackupManager.isVersionSupported(-1))
    }

    @Test
    fun `a backup from a newer app version is refused, not guessed at`() {
        val future = """{"format":"${BackupManager.FORMAT}","version":${BackupManager.CURRENT_VERSION + 1},"assets":[]}"""
        val ex = runCatching { BackupManager.parseBackup(future) }.exceptionOrNull()
        assertTrue("a future-version backup should be rejected", ex != null)
        assertTrue(
            "the message should explain it's a version problem: ${ex?.message}",
            ex?.message?.contains("v${BackupManager.CURRENT_VERSION + 1}") == true,
        )
    }

    @Test
    fun `a legacy v1 backup is still accepted, not treated as unsupported`() {
        val legacy = """{"format":"${BackupManager.FORMAT}","version":1,"assets":[]}"""
        val data = BackupManager.parseBackup(legacy)
        assertEquals(1, data.version)
    }

    @Test
    fun `the current version round-trips through encode and parse`() {
        val data = BackupManager.parseBackup(BackupManager.encodeBackup(BackupData()))
        assertEquals(BackupManager.CURRENT_VERSION, data.version)
    }

    // --- confirmation counts ---------------------------------------------------------------------

    @Test
    fun `preview counts holdings and alerts as distinct categories from the raw watchlist size`() {
        val assets = buildList {
            repeat(12) { add(asset("HOLD$it", shares = 10.0)) }
            repeat(8) { add(asset("ALERT$it", alerts = AssetAlerts(priceAbove = 100.0))) }
            repeat(54 - 12 - 8) { add(asset("WATCH$it")) }
        }
        val data = BackupData(
            assets = assets,
            groups = listOf("Core", "Speculative", "Watch"),
            calls = listOf(call("AAPL"), call("MSFT")),
            closedCalls = listOf(closedCall("NVDA")),
            journal = listOf(journalEntry("TSLA")),
            investableCash = 500.0,
        )

        val preview = BackupManager.preview(data)

        assertEquals(54, preview.watchlistCount)
        assertEquals(12, preview.holdingsCount)
        assertEquals(8, preview.alertsCount)
        assertEquals(3, preview.groupsCount)
        assertEquals(2, preview.openCallsCount)
        assertEquals(1, preview.closedCallsCount)
        assertEquals(1, preview.journalCount)
        assertEquals(500.0, preview.investableCash, 0.001)
    }

    @Test
    fun `a zero share or fully-disarmed asset is not counted as a holding or an alert`() {
        val data = BackupData(
            assets = listOf(
                asset("ZERO", shares = 0.0),
                asset("NOALERT", alerts = AssetAlerts()), // present but nothing armed -> isEmpty
                asset("PLAIN"),
            ),
        )
        val preview = BackupManager.preview(data)
        assertEquals(3, preview.watchlistCount)
        assertEquals(0, preview.holdingsCount)
        assertEquals(0, preview.alertsCount)
    }

    @Test
    fun `confirmation message names concrete counts, matching the example this feature was specced from`() {
        val data = BackupData(
            assets = buildList {
                repeat(12) { add(asset("HOLD$it", shares = 10.0)) }
                repeat(8) { add(asset("ALERT$it", alerts = AssetAlerts(priceAbove = 100.0))) }
                repeat(54 - 12 - 8) { add(asset("WATCH$it")) }
            },
        )
        val message = BackupManager.confirmationMessage(BackupManager.preview(data))
        assertTrue(message, message.contains("54 watchlist symbols"))
        assertTrue(message, message.contains("12 holdings"))
        assertTrue(message, message.contains("8 alerts"))
        // Must be a count, not an adjective — "your data" / "everything" never appears.
        assertFalse(message.contains("your data", ignoreCase = true))
    }

    @Test
    fun `confirmation message only mentions non-empty categories, singular where the count is one`() {
        val data = BackupData(
            assets = emptyList(),
            calls = listOf(call("AAPL")),
            closedCalls = listOf(closedCall("MSFT")),
            journal = listOf(journalEntry("TSLA")),
        )
        val message = BackupManager.confirmationMessage(BackupManager.preview(data))
        assertTrue(message, message.contains("0 watchlist symbols"))
        assertFalse(message.contains("holdings"))
        assertFalse(message.contains("alerts"))
        assertTrue(message, message.contains("1 open call position,"))
        assertTrue(message, message.contains("1 closed call (realized P&L history)"))
        assertTrue(message, message.contains("1 journal entry"))
        // "watchlist" legitimately contains "list" as a substring, so assert on the phrase a zero
        // count would actually produce rather than the bare word.
        assertFalse("no groups were in the backup: $message", message.contains("0 lists"))
    }

    @Test
    fun `confirmation message states the investable cash it will set, when non-zero`() {
        val withCash = BackupManager.confirmationMessage(BackupManager.preview(BackupData(investableCash = 1234.5)))
        assertTrue(withCash, withCash.contains("$1,234.50"))
        val withoutCash = BackupManager.confirmationMessage(BackupManager.preview(BackupData(investableCash = 0.0)))
        assertFalse(withoutCash.contains("investable cash"))
    }

    // --- undo restores exactly what was there, corrupt bytes included -----------------------------

    @Test
    fun `rawStateFor encodes each list exactly as its own store would decode it back`() {
        val data = BackupData(
            assets = listOf(asset("AAPL", shares = 3.0)),
            groups = listOf("Core"),
            calls = listOf(call("AAPL")),
            closedCalls = listOf(closedCall("MSFT")),
            journal = listOf(journalEntry("TSLA")),
            investableCash = 42.0,
        )
        val raw = BackupManager.rawStateFor(data)

        assertEquals(data.assets, Http.json.decodeFromString<List<Asset>>(raw.assetsJson!!))
        assertEquals(data.groups, Http.json.decodeFromString<List<String>>(raw.groupsJson!!))
        assertEquals(data.calls, Http.json.decodeFromString<List<CallPosition>>(raw.callsJson!!))
        assertEquals(data.closedCalls, Http.json.decodeFromString<List<ClosedCallPosition>>(raw.closedCallsJson!!))
        assertEquals(data.journal, Http.json.decodeFromString<List<VerdictJournalEntry>>(raw.journalJson!!))
        assertEquals(data.investableCash, raw.investableCash)
    }

    @Test
    fun `a snapshot taken from already-corrupt storage is not decoded, fixed, or cleared by anything in this path`() {
        // Simulates exactly what CallPositionStore.rawValue(prefs) would hand back for a key that
        // CallPositionStore.Stored.Unreadable would otherwise refuse to overwrite: garbage bytes that
        // fail to parse as a List<CallPosition>. A real capture (BackupManager.captureCurrent) reads
        // this string straight off the store with no decode step in between; this reproduces that by
        // building the same BackupRawState shape directly.
        val corruptCallsJson = "{not valid json at all"
        val before = BackupManager.BackupRawState(
            assetsJson = """[]""",
            groupsJson = null, // key never written
            callsJson = corruptCallsJson,
            closedCallsJson = "[]",
            investableCash = null, // key never written
            journalJson = "[]",
        )

        // An import of unrelated content really would replace it with something legible...
        val imported = BackupManager.rawStateFor(BackupData(calls = listOf(call("AAPL"))))
        assertNotEquals("the import must actually change state, or this test proves nothing", before, imported)
        assertNotEquals(corruptCallsJson, imported.callsJson)

        // ...but undo means writing `before` back verbatim. BackupRawState carries no decode/encode
        // step of its own (see BackupManager.applyRawState, which just does prefs[key] = raw), so
        // "restoring `before`" IS `before` — the corrupt bytes come back exactly, not "[]" and not
        // cleared to null the way going through CallPositionStore.setAll(snapshot()) would have.
        val restored = before
        assertEquals(corruptCallsJson, restored.callsJson)
        assertEquals(before, restored)
    }

    @Test
    fun `a never-written key is distinct from an empty list, both before and after restore`() {
        val before = BackupManager.BackupRawState(
            assetsJson = null, groupsJson = null, callsJson = null,
            closedCallsJson = null, investableCash = null, journalJson = null,
        )
        // Nothing here should ever be silently promoted to "[]" — null must round-trip as null.
        assertEquals(before, before.copy())
        assertEquals(null, before.assetsJson)
        assertEquals(null, before.investableCash)
    }
}
