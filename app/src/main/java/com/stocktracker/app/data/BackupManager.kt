package com.stocktracker.app.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.CallPosition
import com.stocktracker.app.data.model.ClosedCallPosition
import com.stocktracker.app.data.model.VerdictJournalEntry
import com.stocktracker.app.data.prefs.dataStore
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.Locale

/**
 * Portable snapshot of everything the user configured (survives reinstall / phone switch).
 *
 * [calls], [closedCalls] and [investableCash] were missing for a long time, so a backup taken before
 * a phone switch silently left behind every tracked option position and the ENTIRE realized-P&L
 * history — the numbers behind the "Total realized P&L" and win-rate card — while the Settings copy
 * promised a full snapshot.
 */
@Serializable
data class BackupData(
    val version: Int = BackupManager.CURRENT_VERSION,
    val format: String = BackupManager.FORMAT,
    val assets: List<Asset> = emptyList(),
    val groups: List<String> = emptyList(),
    val calls: List<CallPosition> = emptyList(),
    val closedCalls: List<ClosedCallPosition> = emptyList(),
    val investableCash: Double = 0.0,
    /** The verdict journal (SWT-8). Defaulted, so a backup written before it existed still imports. */
    val journal: List<VerdictJournalEntry> = emptyList(),
)

/**
 * Exports/imports everything the user hand-entered: watchlist (shares, cost, alerts, groups), tracked
 * option positions, closed-position history, investable cash and the verdict journal.
 *
 * Restoring is the single most destructive action in the app — one tap replaces all six of the above
 * — so this file is organised around three properties an import must have (DATA-8), not just around
 * getting the bytes onto disk:
 *
 * 1. **Atomic.** [commitImport] performs ONE [androidx.datastore.core.DataStore.edit] transaction that
 *    sets all six preference keys at once, rather than six separate `store.setAll()` calls each with
 *    its own commit. [androidx.datastore.core.DataStore.edit] serialises the WHOLE preferences map to
 *    disk as a single atomic file swap, so either every store ends up holding the new data or (on any
 *    failure, including the calling coroutine being cancelled) none of them do — there is no partial
 *    state for a crash or a navigation event to catch it in.
 * 2. **Confirmed.** [readForImport] parses the file and produces an [ImportPreview] with concrete
 *    counts *before* anything is written, so the caller can show the user exactly what is about to be
 *    replaced and get an explicit "go ahead" first.
 * 3. **Reversible.** [commitImport] snapshots the exact pre-import bytes (not the decoded lists — see
 *    [BackupRawState]) and returns them, so a completed import can be undone with [undo]. See
 *    [BackupRawState]'s doc for exactly what that guarantees and what it does not.
 */
object BackupManager {

    const val FORMAT = "stocktracker-backup"

    /**
     * The newest backup format this build understands.
     *
     * [parseBackup] refuses any backup whose `version` is higher than this — a file written by a
     * newer, incompatible build should be REJECTED, not parsed on a best-effort basis and silently
     * missing whatever changed. Versions at or below this are all understood: `ignoreUnknownKeys` +
     * every field's default already handle an OLDER file that is simply missing newer fields (see the
     * "legacy backup" tests), so bump this only when a change to [BackupData] is not just an added,
     * defaulted field — e.g. a field whose meaning changed incompatibly.
     */
    const val CURRENT_VERSION = 2

    /** The oldest backup format still accepted. Files older than this predate `version` meaning
     *  anything and are told apart by the legacy `assets`-array check in [parseBackup] instead. */
    const val MIN_SUPPORTED_VERSION = 1

    /** `encodeDefaults` so the marker and version are always written, even for an empty backup —
     *  without it an empty snapshot serialises to `{}`, which is exactly what we refuse to import. */
    private val codec = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true    // tolerate fields added by newer versions
        prettyPrint = false
    }

    fun encodeBackup(data: BackupData): String = codec.encodeToString(data)

    /** Pure: is this a format version the app actually understands, or is it guessing? */
    fun isVersionSupported(version: Int): Boolean = version in MIN_SUPPORTED_VERSION..CURRENT_VERSION

    /**
     * Decode a backup, REFUSING anything that isn't one.
     *
     * Every field of [BackupData] has a default and the app's shared Json is deliberately permissive,
     * so a plain decode accepts any JSON object at all and yields an empty backup — which the import
     * path then writes over the user's real holdings. Restoring is destructive by design, so the file
     * has to prove it is a backup first, AND prove its format is one this build understands: a backup
     * written by a future or incompatible version of the app carries a `version` this build has never
     * seen, and decoding it anyway (via `ignoreUnknownKeys`) can silently drop fields that mattered.
     *
     * Accepts either the explicit [FORMAT] marker, or (for files written before the marker existed)
     * the presence of an `assets` array.
     */
    fun parseBackup(text: String): BackupData {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IOException("That file isn't a StockTracker backup (not valid JSON).") }
        val marked = root["format"]?.toString()?.contains(FORMAT) == true
        val legacy = root["assets"] != null
        if (!marked && !legacy) {
            throw IOException(
                "That file isn't a StockTracker backup — nothing was changed. " +
                    "Pick a file exported from Settings → Backup.",
            )
        }
        val data = runCatching { codec.decodeFromString<BackupData>(text) }
            .getOrElse { throw IOException("That backup file is damaged and couldn't be read.") }
        if (!isVersionSupported(data.version)) {
            throw IOException(
                "That backup is format v${data.version}, and this version of StockTracker only " +
                    "understands up to v$CURRENT_VERSION. Update the app before restoring it — " +
                    "nothing was changed.",
            )
        }
        return data
    }

    // ---------------------------------------------------------------------------------------------
    // Confirmation preview — concrete counts, so a dialog can say what will actually happen.
    // ---------------------------------------------------------------------------------------------

    /** What restoring a [BackupData] would replace, in counts — not adjectives. */
    data class ImportPreview(
        val watchlistCount: Int,
        val holdingsCount: Int,
        val alertsCount: Int,
        val groupsCount: Int,
        val openCallsCount: Int,
        val closedCallsCount: Int,
        val journalCount: Int,
        val investableCash: Double,
    )

    /** Pure: derives [ImportPreview] from already-parsed backup content. */
    fun preview(data: BackupData): ImportPreview = ImportPreview(
        watchlistCount = data.assets.size,
        holdingsCount = data.assets.count { (it.shares ?: 0.0) > 0.0 },
        alertsCount = data.assets.count { it.alerts?.isEmpty == false },
        groupsCount = data.groups.size,
        openCallsCount = data.calls.size,
        closedCallsCount = data.closedCalls.size,
        journalCount = data.journal.size,
        investableCash = data.investableCash,
    )

    /**
     * Pure: the confirmation-dialog copy for [preview] — e.g. "Replace 54 watchlist symbols, 12
     * holdings, and 8 alerts. Also replaces 3 lists, 4 open call positions, 10 closed calls (realized
     * P&L history), and 20 journal entries." Every non-empty category the import actually touches is
     * named with its count, so the user isn't told "this will overwrite your data" about a call-option
     * book or a P&L history they didn't think to picture — see DATA-8.
     */
    fun confirmationMessage(preview: ImportPreview): String {
        val core = buildList {
            add(count(preview.watchlistCount, "watchlist symbol"))
            if (preview.holdingsCount > 0) add(count(preview.holdingsCount, "holding"))
            if (preview.alertsCount > 0) add(count(preview.alertsCount, "alert"))
        }
        val also = buildList {
            if (preview.groupsCount > 0) add(count(preview.groupsCount, "list"))
            if (preview.openCallsCount > 0) {
                add(count(preview.openCallsCount, "open call position", "open call positions"))
            }
            if (preview.closedCallsCount > 0) {
                add(count(preview.closedCallsCount, "closed call", "closed calls") + " (realized P&L history)")
            }
            if (preview.journalCount > 0) add(count(preview.journalCount, "journal entry", "journal entries"))
        }
        var text = "Replace ${joinEnglish(core)}."
        if (also.isNotEmpty()) text += " Also replaces ${joinEnglish(also)}."
        if (preview.investableCash != 0.0) text += " Sets investable cash to ${formatUsd(preview.investableCash)}."
        return text
    }

    private fun count(n: Int, singular: String, plural: String = "${singular}s") =
        "$n ${if (n == 1) singular else plural}"

    private fun joinEnglish(items: List<String>): String = when (items.size) {
        0 -> "nothing"
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }

    private fun formatUsd(amount: Double): String = String.format(Locale.US, "$%,.2f", amount)

    // ---------------------------------------------------------------------------------------------
    // Raw state — the snapshot-and-undo mechanism.
    // ---------------------------------------------------------------------------------------------

    /**
     * The exact bytes/value of the six preference entries an import replaces — captured, or restored,
     * verbatim. `null` in a field means that key had never been written (a fresh install); that is
     * different from an empty list, and different again from a value present but unreadable.
     *
     * This is what makes undo honest rather than a second way to lose data. [CallPositionStore],
     * [com.stocktracker.app.data.prefs.VerdictJournalStore] and
     * [com.stocktracker.app.data.prefs.WatchlistStore] all refuse to let their ordinary mutators
     * overwrite a key that is present but corrupt, specifically so a decode failure stays recoverable
     * instead of being silently replaced by an empty list. Their typed `snapshot()` — used everywhere
     * else in the app — cooperates with that guard by reporting corrupt storage AS an empty list, which
     * is correct for the UI but would be a lie here: capturing "pre-import state" with `snapshot()` and
     * writing it back on undo would take a corrupt-but-possibly-recoverable value and PERMANENTLY
     * replace it with an empty list the moment the user hits undo — defeating the exact guard those
     * stores exist to provide. [BackupRawState] captures the literal on-disk value instead (via each
     * store's `rawValue`/`writeRaw`, restricted to this file), so undo restores precisely what was
     * there — corrupt or not — the same way `git checkout` restores a file without asking whether its
     * contents made sense.
     */
    data class BackupRawState(
        val assetsJson: String?,
        val groupsJson: String?,
        val callsJson: String?,
        val closedCallsJson: String?,
        val investableCash: Double?,
        val journalJson: String?,
    )

    /**
     * Pure: the raw values committing [data] would write. Same shape as a captured [BackupRawState] on
     * purpose — it lets the "does undo really restore what was there" property be tested as a plain
     * round trip (capture → import → restore → compare) without a real DataStore.
     */
    internal fun rawStateFor(data: BackupData): BackupRawState = BackupRawState(
        assetsJson = Http.json.encodeToString(data.assets),
        groupsJson = Http.json.encodeToString(data.groups),
        callsJson = Http.json.encodeToString(data.calls),
        closedCallsJson = Http.json.encodeToString(data.closedCalls),
        investableCash = data.investableCash,
        journalJson = Http.json.encodeToString(data.journal),
    )

    /** Reads the current raw value of all six backup-covered keys, undecoded — the pre-import
     *  snapshot. A value that is currently corrupt is captured as exactly that corrupt string. */
    private suspend fun captureCurrent(context: Context): BackupRawState {
        val prefs = context.dataStore.data.first()
        return BackupRawState(
            assetsJson = ServiceLocator.watchlistStore.rawValue(prefs),
            groupsJson = ServiceLocator.settingsStore.rawWatchlistGroups(prefs),
            callsJson = ServiceLocator.callPositionStore.rawValue(prefs),
            closedCallsJson = ServiceLocator.closedCallPositionStore.rawValue(prefs),
            investableCash = ServiceLocator.settingsStore.rawInvestableCash(prefs),
            journalJson = ServiceLocator.verdictJournalStore.rawValue(prefs),
        )
    }

    /** Writes [state] into a live transaction, verbatim. The one function both [commitImport] and
     *  [undo] funnel through, so "what import writes" and "what undo restores" can never drift apart. */
    private fun applyRawState(prefs: MutablePreferences, state: BackupRawState) {
        ServiceLocator.watchlistStore.writeRaw(prefs, state.assetsJson)
        ServiceLocator.settingsStore.writeRawWatchlistGroups(prefs, state.groupsJson)
        ServiceLocator.callPositionStore.writeRaw(prefs, state.callsJson)
        ServiceLocator.closedCallPositionStore.writeRaw(prefs, state.closedCallsJson)
        ServiceLocator.settingsStore.writeRawInvestableCash(prefs, state.investableCash)
        ServiceLocator.verdictJournalStore.writeRaw(prefs, state.journalJson)
    }

    // ---------------------------------------------------------------------------------------------
    // The actual import flow: read+validate, confirm (caller's job), commit, optionally undo.
    // ---------------------------------------------------------------------------------------------

    /** Outcome of reading and validating a chosen file. Nothing is written for either case — safe to
     *  run on any scope, including one a navigation event can cancel; cancelling it loses nothing but
     *  a re-pick. */
    sealed interface ReadResult {
        /** [corruptedNow] names whichever of the guarded stores (watchlist, call positions, verdict
         *  journal) are CURRENTLY unreadable, i.e. before this import touches anything — so the
         *  confirmation dialog can tell the user their existing data there isn't merely small, it's
         *  broken, and that this import (or an undo of it) will not further disturb those bytes. */
        data class Ready(
            val data: BackupData,
            val preview: ImportPreview,
            val corruptedNow: List<String> = emptyList(),
        ) : ReadResult
        /** [message] distinguishes an unreadable file, a wrong file, a damaged backup and an
         *  unsupported version — never collapsed to one generic "Import failed" (DATA-8). */
        data class Failed(val message: String) : ReadResult
    }

    /** Outcome of [commitImport]. */
    sealed interface CommitResult {
        data class Success(val preview: ImportPreview, val snapshot: BackupRawState) : CommitResult
        /** The transaction did not commit, so — unlike the old six-separate-writes path — NOTHING
         *  changed; [snapshot] is returned anyway so the caller can treat every failure the same way
         *  rather than special-casing "nothing to undo". */
        data class Failed(val message: String, val snapshot: BackupRawState) : CommitResult
    }

    /** Step 1: read the chosen file and validate it, but write nothing. */
    suspend fun readForImport(context: Context, uri: Uri): ReadResult = withContext(Dispatchers.IO) {
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return@withContext ReadResult.Failed("Couldn't open the chosen file.")
        } catch (e: IOException) {
            return@withContext ReadResult.Failed("Couldn't read the chosen file: ${e.message ?: "unknown error"}.")
        }
        val data = try {
            parseBackup(text)
        } catch (e: IOException) {
            return@withContext ReadResult.Failed(e.message ?: "That file isn't a StockTracker backup.")
        }
        ReadResult.Ready(data, preview(data), corruptedNow())
    }

    /** Human labels for whichever guarded stores are unreadable right now, for [readForImport]. */
    private suspend fun corruptedNow(): List<String> = buildList {
        if (ServiceLocator.watchlistStore.corrupted.first()) add("watchlist")
        if (ServiceLocator.callPositionStore.corrupted.first()) add("call positions")
        if (ServiceLocator.verdictJournalStore.corrupted.first()) add("verdict journal")
    }

    /**
     * Step 2: the user confirmed the counts from [ReadResult.Ready.preview]. Snapshots the current
     * state, then replaces all six stores in ONE DataStore transaction.
     *
     * Call this from a scope that outlives the screen that launched it (e.g.
     * [ServiceLocator.applicationScope]) rather than a Composable's `rememberCoroutineScope` — the
     * latter is cancelled the instant its composable leaves composition, which is exactly how a
     * six-separate-writes import used to leave some stores replaced and others not (DATA-8). With a
     * single atomic transaction there is no in-between state left for that cancellation to catch
     * anyway, but running it on a scope that survives navigation means the transaction reliably starts
     * and reliably reports its result, instead of the write racing a scope teardown that may abandon it
     * before `dataStore.edit` is even called.
     */
    suspend fun commitImport(context: Context, data: BackupData): CommitResult = withContext(Dispatchers.IO) {
        val snapshot = captureCurrent(context)
        val incoming = rawStateFor(data)
        try {
            context.dataStore.edit { prefs -> applyRawState(prefs, incoming) }
            CommitResult.Success(preview(data), snapshot)
        } catch (e: IOException) {
            // DataStore.edit leaves the prior file untouched when the transform block or the write
            // itself fails, so nothing actually changed here.
            CommitResult.Failed("Couldn't save the import: ${e.message ?: "unknown error"}.", snapshot)
        }
    }

    /** Restores exactly what [captureCurrent] read immediately before an import — including a value
     *  that was already corrupt then. This does not go back through `decode`/`encode`, so it cannot
     *  "fix" or lose that corruption; it puts back the same bytes. */
    suspend fun undo(context: Context, snapshot: BackupRawState) = withContext(Dispatchers.IO) {
        context.dataStore.edit { prefs -> applyRawState(prefs, snapshot) }
        Unit
    }

    suspend fun exportTo(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val json = encodeBackup(
            BackupData(
                assets = ServiceLocator.watchlistStore.snapshot(),
                groups = ServiceLocator.settingsStore.watchlistGroups.first(),
                calls = ServiceLocator.callPositionStore.snapshot(),
                closedCalls = ServiceLocator.closedCallPositionStore.snapshot(),
                investableCash = ServiceLocator.settingsStore.investableCash.first(),
                journal = ServiceLocator.verdictJournalStore.snapshot(),
            ),
        )
        // "wt", not the default "w". Plain "w" is MODE_WRITE_ONLY|MODE_CREATE with NO truncation, so
        // re-exporting over a larger existing backup left the old tail past the end of the new JSON
        // and produced a corrupt file that then failed to import.
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
            ?: throw IOException("Couldn't open the chosen file for writing")
        ServiceLocator.watchlistStore.snapshot().size
    }
}
