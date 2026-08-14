package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.remote.Http
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Sector per ticker, cached on device so the watchlist keeps its sections when the backend is not
 * reachable.
 *
 * The backend already caches this on disk with a long TTL, so the round trip is cheap — but "cheap"
 * and "available" are different properties. Without a local copy the watchlist would collapse into
 * one undifferentiated list every time the signals box was down, which is precisely the moment the
 * user is least likely to read it as a transient fault and most likely to read it as the feature
 * being broken.
 *
 * Three states are kept apart deliberately, because they mean different things on screen:
 * - a symbol with a sector          -> file it under that vertical
 * - a symbol [classified] with none -> the server looked and found nothing (ETFs, warrants) -> Other
 * - a symbol absent from the cache  -> nobody has looked yet -> ask, and until the answer lands do
 *   not assert anything about it
 *
 * Collapsing the last two would turn "we have not checked" into a confident "Other".
 */
class SectorCache(private val context: Context) {

    private val key = stringPreferencesKey("sector_cache_v1_json")

    @Serializable
    data class Entry(val sector: String? = null, val industry: String? = null, val ts: Long = 0L)

    val sectors: Flow<Map<String, Entry>> = context.priceCacheStore.data.map { decode(it[key]) }

    suspend fun snapshot(): Map<String, Entry> = sectors.first()

    /** Merge a freshly fetched batch in. Absent keys in [fetched] are left untouched rather than
     *  deleted — a partial response must not erase what an earlier, fuller one established. */
    suspend fun put(fetched: Map<String, Entry>) {
        if (fetched.isEmpty()) return
        context.priceCacheStore.edit { prefs ->
            val merged = decode(prefs[key]).toMutableMap()
            fetched.forEach { (sym, e) -> merged[sym.uppercase()] = e }
            prefs[key] = Http.json.encodeToString(merged as Map<String, Entry>)
        }
    }

    /** Symbols with no cached answer at all, or one older than [TTL_MS]. Never returns a symbol
     *  merely because its sector is null — that is a real answer and re-asking would spend a request
     *  per refresh on every ETF in the list, forever. */
    suspend fun stale(symbols: List<String>, nowMs: Long = System.currentTimeMillis()): List<String> {
        val have = snapshot()
        return symbols.map { it.uppercase() }.distinct().filter { sym ->
            val e = have[sym]
            e == null || nowMs - e.ts > TTL_MS
        }
    }

    private fun decode(raw: String?): Map<String, Entry> =
        raw?.let { runCatching { Http.json.decodeFromString<Map<String, Entry>>(it) }.getOrNull() }
            ?: emptyMap()

    companion object {
        /** 30 days. A company changes sector approximately never, and the cost of being a month out
         *  of date is one row under the wrong heading — set against re-fetching the whole watchlist
         *  on a schedule that would never pay for itself. */
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
