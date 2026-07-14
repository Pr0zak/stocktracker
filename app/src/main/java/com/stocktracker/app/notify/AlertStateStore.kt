package com.stocktracker.app.notify

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.stocktracker.app.data.prefs.priceCacheStore
import kotlinx.coroutines.flow.first

/**
 * Tracks which alert conditions are currently "fired" so we notify once per crossing (not every
 * 15-min poll). A key is removed when its condition clears, so it can fire again next time.
 * Stored in [priceCacheStore] to avoid re-triggering the watchlist/settings flows.
 */
class AlertStateStore(private val context: Context) {

    private val key = stringSetPreferencesKey("fired_alerts")

    suspend fun fired(): Set<String> = context.priceCacheStore.data.first()[key] ?: emptySet()

    suspend fun save(set: Set<String>) = context.priceCacheStore.edit { it[key] = set }
}
