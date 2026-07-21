package com.stocktracker.app.notify

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.stocktracker.app.data.prefs.priceCacheStore
import kotlinx.coroutines.flow.first

/**
 * Dedupe state for OC-4 call-exit alerts: the set of currently-"fired" keys ("positionId:TYPE"), so
 * each crossing notifies ONCE (not every 15-min worker run). A key is cleared when its condition
 * clears (so it can re-fire on a re-cross) and dropped entirely when its position is deleted.
 *
 * Kept in [priceCacheStore] (a separate key from the watchlist alerts' "fired_alerts") so writes never
 * re-trigger the call-positions / settings flows.
 */
class CallAlertStateStore(private val context: Context) {

    private val key = stringSetPreferencesKey("call_exit_fired_alerts")

    suspend fun fired(): Set<String> = context.priceCacheStore.data.first()[key] ?: emptySet()

    suspend fun save(set: Set<String>) = context.priceCacheStore.edit { it[key] = set }
}
