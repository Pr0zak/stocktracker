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

    /**
     * What is actually in storage.
     *
     * The distinction between [Empty] and [Unreadable] is the whole point. Collapsing both to "use
     * DEFAULT" meant a single unreadable byte looked exactly like a fresh install: the UI showed the
     * five demo tickers, and then the very next add/remove/update encoded that demo list back over
     * the user's still-intact JSON — turning a recoverable read failure into permanent destruction
     * of hand-entered shares and cost basis.
     */
    private sealed interface Stored {
        data class Ok(val assets: List<Asset>) : Stored
        data object Empty : Stored          // nothing ever written — DEFAULT is genuinely right
        data object Unreadable : Stored     // present but corrupt — must NOT be overwritten
    }

    private fun read(raw: String?): Stored = when {
        raw == null -> Stored.Empty
        else -> decode(raw)?.let { Stored.Ok(it) } ?: Stored.Unreadable
    }

    val watchlist: Flow<List<Asset>> = context.dataStore.data.map { prefs ->
        when (val s = read(prefs[key])) {
            is Stored.Ok -> s.assets
            Stored.Empty -> DEFAULT
            // Deliberately NOT the demo list: showing AAPL/BTC/NVDA/MSFT/ETH would assert that these
            // are the user's holdings. An empty list plus [corrupted] lets the UI say what is true.
            Stored.Unreadable -> emptyList()
        }
    }

    /** True when stored data exists but could not be parsed — the UI should warn rather than
     *  present an empty or demo watchlist as though it were the user's. */
    val corrupted: Flow<Boolean> = context.dataStore.data.map { read(it[key]) is Stored.Unreadable }

    suspend fun snapshot(): List<Asset> = watchlist.first()

    /** Mutators refuse to run against unreadable storage, so the original bytes stay recoverable. */
    private inline fun mutate(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        block: (List<Asset>) -> List<Asset>,
    ) {
        val cur = when (val s = read(prefs[key])) {
            is Stored.Ok -> s.assets
            Stored.Empty -> DEFAULT
            Stored.Unreadable -> return      // leave the corrupt value alone
        }
        prefs[key] = encode(block(cur))
    }

    suspend fun add(asset: Asset) = context.dataStore.edit { prefs ->
        mutate(prefs) { cur -> if (cur.none { it.id == asset.id }) cur + asset else cur }
    }

    suspend fun remove(asset: Asset) = context.dataStore.edit { prefs ->
        mutate(prefs) { cur -> cur.filterNot { it.id == asset.id } }
    }

    /** Wholesale replace. Unlike the incremental mutators this MAY overwrite unreadable storage —
     *  it is only reached from an explicit user action (backup restore, reorder). */
    suspend fun setAll(list: List<Asset>) = context.dataStore.edit { prefs ->
        prefs[key] = encode(list)
    }

    /** Replace the entry with the same id (used to set shares / alerts). Adds it if absent. */
    suspend fun update(asset: Asset) = context.dataStore.edit { prefs ->
        mutate(prefs) { cur ->
            if (cur.any { it.id == asset.id }) cur.map { if (it.id == asset.id) asset else it }
            else cur + asset
        }
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
