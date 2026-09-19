package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.model.VixQuote
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.ScanLatest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * DATA-9 — the last nightly scan and the last VIX reading, each stamped with the time THIS APP
 * fetched it, surviving a process restart.
 *
 * On [PriceCache]'s own store deliberately: this is the same kind of thing PriceCache already
 * holds — a disposable, rebuild-from-the-network cache of the last good market reading — and a
 * write here must not re-trigger the watchlist/settings DataStore's flows any more than a quote
 * write does.
 */
class MarketContextCache(private val context: Context) {

    private val scanKey = stringPreferencesKey("market_context_scan_v1_json")
    private val vixKey = stringPreferencesKey("market_context_vix_v1_json")

    @Serializable
    data class ScanEntry(val scan: ScanLatest, val fetchedAtMs: Long)

    @Serializable
    data class VixEntry(val vix: VixQuote, val fetchedAtMs: Long)

    suspend fun loadScan(): ScanEntry? {
        val raw = context.priceCacheStore.data.first()[scanKey] ?: return null
        return runCatching { Http.json.decodeFromString<ScanEntry>(raw) }.getOrNull()
    }

    suspend fun saveScan(scan: ScanLatest, fetchedAtMs: Long) {
        context.priceCacheStore.edit { prefs ->
            prefs[scanKey] = Http.json.encodeToString(ScanEntry(scan, fetchedAtMs))
        }
    }

    suspend fun loadVix(): VixEntry? {
        val raw = context.priceCacheStore.data.first()[vixKey] ?: return null
        return runCatching { Http.json.decodeFromString<VixEntry>(raw) }.getOrNull()
    }

    suspend fun saveVix(vix: VixQuote, fetchedAtMs: Long) {
        context.priceCacheStore.edit { prefs ->
            prefs[vixKey] = Http.json.encodeToString(VixEntry(vix, fetchedAtMs))
        }
    }
}
