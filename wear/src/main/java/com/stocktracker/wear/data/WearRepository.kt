package com.stocktracker.wear.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.stocktracker.shared.WEAR_SNAPSHOT_JSON_KEY
import com.stocktracker.shared.WEAR_SNAPSHOT_PATH
import com.stocktracker.shared.WearSnapshot
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val TAG = "StockTrackerWear"
private const val PREFS = "stocktracker_wear_snapshot"
private const val KEY_JSON = "json"

/**
 * Local mirror of the [WearSnapshot] most recently pushed from the phone (WGT-7).
 *
 * The watch NEVER fetches a price or a portfolio total itself -- see the module README/CLAUDE.md
 * design note this task was scoped against. Everything this repository returns either came from
 * [WearSnapshotListenerService.onDataChanged] (a live push while this app was running) or from a
 * direct one-shot [Wearable.getDataClient] read done here on a cold tile/complication request, to
 * cover the gap between "the phone already pushed a snapshot" and "this watch process has been
 * alive long enough to have received that push as an event" (a data item already sitting in the
 * Data Layer at registration time does not replay as a change event).
 */
object WearRepository {

    private val json = Json { ignoreUnknownKeys = true }

    fun cachedSnapshot(context: Context): WearSnapshot? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null)
            ?: return null
        return decode(raw)
    }

    fun save(context: Context, snapshotJson: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, snapshotJson)
            .apply()
    }

    /**
     * Returns the cached snapshot if one exists; otherwise makes ONE bounded attempt to read
     * whatever the phone already synced to the Data Layer before this process started listening,
     * caching it for next time. Bounded because both [androidx.wear.tiles.TileService] and
     * [androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService]
     * requests are expected to resolve quickly -- a slow or absent phone connection must fall
     * through to "not configured" rather than hang the tile/complication request.
     */
    suspend fun snapshotOrHydrate(context: Context): WearSnapshot? {
        cachedSnapshot(context)?.let { return it }
        return withTimeoutOrNull(3_000L) {
            try {
                val items = Wearable.getDataClient(context)
                    .getDataItems(Uri.parse("wear://*$WEAR_SNAPSHOT_PATH"))
                    .await()
                val raw = items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap.getString(WEAR_SNAPSHOT_JSON_KEY) }
                items.release()
                raw?.also { save(context, it) }?.let { decode(it) }
            } catch (e: Exception) {
                Log.w(TAG, "cold-start hydration failed: ${e.message}")
                null
            }
        }
    }

    private fun decode(raw: String): WearSnapshot? =
        runCatching { json.decodeFromString<WearSnapshot>(raw) }
            .onFailure { Log.w(TAG, "bad snapshot payload: ${it.message}") }
            .getOrNull()
}
