package com.stocktracker.app.data

import android.content.Context
import android.net.Uri
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.CallPosition
import com.stocktracker.app.data.model.ClosedCallPosition
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
    val version: Int = 2,
    val format: String = BackupManager.FORMAT,
    val assets: List<Asset> = emptyList(),
    val groups: List<String> = emptyList(),
    val calls: List<CallPosition> = emptyList(),
    val closedCalls: List<ClosedCallPosition> = emptyList(),
    val investableCash: Double = 0.0,
)

/** Exports/imports everything the user hand-entered: watchlist (shares, cost, alerts, groups),
 *  tracked option positions, closed-position history and investable cash. */
object BackupManager {

    const val FORMAT = "stocktracker-backup"

    /** `encodeDefaults` so the marker and version are always written, even for an empty backup —
     *  without it an empty snapshot serialises to `{}`, which is exactly what we refuse to import. */
    private val codec = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true    // tolerate fields added by newer versions
        prettyPrint = false
    }

    fun encodeBackup(data: BackupData): String = codec.encodeToString(data)

    /**
     * Decode a backup, REFUSING anything that isn't one.
     *
     * Every field of [BackupData] has a default and the app's shared Json is deliberately permissive,
     * so a plain decode accepts any JSON object at all and yields an empty backup — which the import
     * path then writes over the user's real holdings. Restoring is destructive by design, so the file
     * has to prove it is a backup first.
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
        return runCatching { codec.decodeFromString<BackupData>(text) }
            .getOrElse { throw IOException("That backup file is damaged and couldn't be read.") }
    }

    suspend fun exportTo(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val json = encodeBackup(
            BackupData(
                assets = ServiceLocator.watchlistStore.snapshot(),
                groups = ServiceLocator.settingsStore.watchlistGroups.first(),
                calls = ServiceLocator.callPositionStore.snapshot(),
                closedCalls = ServiceLocator.closedCallPositionStore.snapshot(),
                investableCash = ServiceLocator.settingsStore.investableCash.first(),
            ),
        )
        // "wt", not the default "w". Plain "w" is MODE_WRITE_ONLY|MODE_CREATE with NO truncation, so
        // re-exporting over a larger existing backup left the old tail past the end of the new JSON
        // and produced a corrupt file that then failed to import.
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
            ?: throw IOException("Couldn't open the chosen file for writing")
        ServiceLocator.watchlistStore.snapshot().size
    }

    /** Replaces the current data with the file's contents. Returns imported asset count. */
    suspend fun importFrom(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IOException("Couldn't read the chosen file")
        val data = parseBackup(text)          // throws before anything is written
        ServiceLocator.watchlistStore.setAll(data.assets)
        ServiceLocator.settingsStore.setWatchlistGroups(data.groups)
        ServiceLocator.callPositionStore.setAll(data.calls)
        ServiceLocator.closedCallPositionStore.setAll(data.closedCalls)
        ServiceLocator.settingsStore.setInvestableCash(data.investableCash)
        data.assets.size
    }
}
