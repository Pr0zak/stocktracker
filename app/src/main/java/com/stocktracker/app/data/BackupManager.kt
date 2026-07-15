package com.stocktracker.app.data

import android.content.Context
import android.net.Uri
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.IOException

/** Portable snapshot of everything the user configured (survives reinstall / phone switch). */
@Serializable
data class BackupData(
    val version: Int = 1,
    val assets: List<Asset> = emptyList(),
    val groups: List<String> = emptyList(),
)

/** Exports/imports the watchlist (with shares, cost, alerts, group membership) + watchlist names. */
object BackupManager {

    suspend fun exportTo(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val assets = ServiceLocator.watchlistStore.snapshot()
        val groups = ServiceLocator.settingsStore.watchlistGroups.first()
        val json = Http.json.encodeToString(BackupData(assets = assets, groups = groups))
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            ?: throw IOException("Couldn't open the chosen file for writing")
        assets.size
    }

    /** Replaces the current watchlist + groups with the file's contents. Returns imported count. */
    suspend fun importFrom(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IOException("Couldn't read the chosen file")
        val data = Http.json.decodeFromString<BackupData>(text)
        ServiceLocator.watchlistStore.setAll(data.assets)
        ServiceLocator.settingsStore.setWatchlistGroups(data.groups)
        data.assets.size
    }
}
