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
    private val bufferKey = stringPreferencesKey("price_buffer_json")

    suspend fun putQuote(assetId: String, quote: Quote) = context.priceCacheStore.edit { prefs ->
        val map = decodeQuotes(prefs[quotesKey]).toMutableMap()
        map[assetId] = quote
        prefs[quotesKey] = Http.json.encodeToString(map)

        val buffers = decodeBuffers(prefs[bufferKey]).toMutableMap()
        val series = buffers[assetId] ?: emptyList()
        // Only append when the price actually moved — avoids flooding the buffer with duplicates.
        if (series.lastOrNull() != quote.price) {
            buffers[assetId] = (series + quote.price).takeLast(MAX_SAMPLES)
            prefs[bufferKey] = Http.json.encodeToString(buffers)
        }
    }

    suspend fun getQuote(assetId: String): Quote? = snapshotQuotes()[assetId]

    suspend fun getBuffer(assetId: String): List<Double> = snapshotBuffers()[assetId] ?: emptyList()

    /** Decode the whole quotes map once (callers indexing many keys should use this, not getQuote). */
    suspend fun snapshotQuotes(): Map<String, Quote> =
        decodeQuotes(context.priceCacheStore.data.first()[quotesKey])

    suspend fun snapshotBuffers(): Map<String, List<Double>> =
        decodeBuffers(context.priceCacheStore.data.first()[bufferKey])

    private fun decodeQuotes(raw: String?): Map<String, Quote> =
        raw?.let { runCatching { Http.json.decodeFromString<Map<String, Quote>>(it) }.getOrNull() } ?: emptyMap()

    private fun decodeBuffers(raw: String?): Map<String, List<Double>> =
        raw?.let { runCatching { Http.json.decodeFromString<Map<String, List<Double>>>(it) }.getOrNull() } ?: emptyMap()

    companion object {
        const val MAX_SAMPLES = 40
    }
}
