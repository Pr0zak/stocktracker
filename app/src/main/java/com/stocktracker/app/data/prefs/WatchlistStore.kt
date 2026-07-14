package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.Http
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** The user's tracked assets, persisted as a JSON list in DataStore. */
class WatchlistStore(private val context: Context) {

    private val key = stringPreferencesKey("watchlist_json")

    val watchlist: Flow<List<Asset>> = context.dataStore.data.map { prefs ->
        decode(prefs[key]) ?: DEFAULT
    }

    suspend fun snapshot(): List<Asset> = watchlist.first()

    suspend fun add(asset: Asset) = context.dataStore.edit { prefs ->
        val cur = decode(prefs[key]) ?: DEFAULT
        if (cur.none { it.id == asset.id }) prefs[key] = encode(cur + asset)
    }

    suspend fun remove(asset: Asset) = context.dataStore.edit { prefs ->
        val cur = decode(prefs[key]) ?: DEFAULT
        prefs[key] = encode(cur.filterNot { it.id == asset.id })
    }

    suspend fun setAll(list: List<Asset>) = context.dataStore.edit { prefs ->
        prefs[key] = encode(list)
    }

    /** Replace the entry with the same id (used to set shares / alerts). Adds it if absent. */
    suspend fun update(asset: Asset) = context.dataStore.edit { prefs ->
        val cur = decode(prefs[key]) ?: DEFAULT
        prefs[key] = encode(
            if (cur.any { it.id == asset.id }) cur.map { if (it.id == asset.id) asset else it }
            else cur + asset,
        )
    }

    private fun decode(raw: String?): List<Asset>? =
        raw?.let { runCatching { Http.json.decodeFromString<List<Asset>>(it) }.getOrNull() }

    private fun encode(list: List<Asset>): String = Http.json.encodeToString(list)

    companion object {
        val DEFAULT: List<Asset> = listOf(
            Asset("AAPL", AssetType.STOCK, "Apple Inc."),
            Asset("BTC", AssetType.CRYPTO, "Bitcoin", coinGeckoId = "bitcoin"),
            Asset("NVDA", AssetType.STOCK, "NVIDIA Corporation"),
            Asset("MSFT", AssetType.STOCK, "Microsoft Corporation"),
            Asset("ETH", AssetType.CRYPTO, "Ethereum", coinGeckoId = "ethereum"),
        )
    }
}
