package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.remote.Http
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Last-known quotes + a rolling price buffer per asset, in its OWN DataStore
 * ([priceCacheStore]) so writes never re-trigger the watchlist/settings flows.
 *
 * - Quotes let widgets/watchlist show a price offline.
 * - The rolling buffer synthesizes a stock sparkline (it fills in over refreshes). Samples are
 *   deduped so a repeated identical price (e.g. a UI re-render re-putting a cached quote) never
 *   floods the buffer.
 */
class PriceCache(private val context: Context) {

    private val quotesKey = stringPreferencesKey("quote_cache_json")
    /** v2 carries a timestamp per sample. The v1 key held bare prices with no time basis at all, so
     *  a "sparkline" could span weeks of refreshes while sitting beside today's change — there is no
     *  way to scope the old data to a session, so it is abandoned rather than migrated. */
    private val bufferKey = stringPreferencesKey("price_buffer_v2_json")

    suspend fun putQuote(assetId: String, quote: Quote) = context.priceCacheStore.edit { prefs ->
        val map = decodeQuotes(prefs[quotesKey]).toMutableMap()
        map[assetId] = quote
        prefs[quotesKey] = Http.json.encodeToString(map)

        val buffers = decodeBuffers(prefs[bufferKey]).toMutableMap()
        val series = buffers[assetId] ?: emptyList()
        val now = System.currentTimeMillis()
        // Only append when the price actually moved — avoids flooding the buffer with duplicates.
        if (series.lastOrNull()?.price != quote.price) {
            // Drop samples older than the retention window BEFORE appending, so the buffer always
            // describes a bounded, recent period rather than an open-ended history of refreshes.
            val recent = (series + Sample(now, quote.price)).filter { now - it.ts <= BUFFER_WINDOW_MS }
            buffers[assetId] = recent.takeLast(MAX_SAMPLES)
            prefs[bufferKey] = Http.json.encodeToString(buffers)
        }
    }

    suspend fun getQuote(assetId: String): Quote? = snapshotQuotes()[assetId]

    suspend fun getBuffer(assetId: String): List<Double> =
        (snapshotBuffers()[assetId] ?: emptyList()).map { it.price }

    /** Decode the whole quotes map once (callers indexing many keys should use this, not getQuote). */
    suspend fun snapshotQuotes(): Map<String, Quote> =
        decodeQuotes(context.priceCacheStore.data.first()[quotesKey])

    suspend fun snapshotBuffers(): Map<String, List<Sample>> =
        decodeBuffers(context.priceCacheStore.data.first()[bufferKey])

    private fun decodeQuotes(raw: String?): Map<String, Quote> =
        raw?.let { runCatching { Http.json.decodeFromString<Map<String, Quote>>(it) }.getOrNull() } ?: emptyMap()

    private fun decodeBuffers(raw: String?): Map<String, List<Sample>> =
        raw?.let { runCatching { Http.json.decodeFromString<Map<String, List<Sample>>>(it) }.getOrNull() } ?: emptyMap()

    /** One observed price and when it was observed. */
    @kotlinx.serialization.Serializable
    data class Sample(val ts: Long, val price: Double)

    companion object {
        const val MAX_SAMPLES = 40
        /** How far back the rolling buffer may reach. Beyond this it stops being a picture of recent
         *  trading and becomes an undated smear of whenever the app happened to refresh. */
        const val BUFFER_WINDOW_MS = 24L * 60 * 60 * 1000
        /** Below this a sparkline is noise pretending to be a trend — draw nothing. */
        const val MIN_SPARK_SAMPLES = 6
    }
}
