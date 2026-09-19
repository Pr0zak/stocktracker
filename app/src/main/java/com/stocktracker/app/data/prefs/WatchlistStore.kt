package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Lot
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

    /** The exact bytes on disk, or null if the key was never written. For [com.stocktracker.app.data.BackupManager]
     *  only: a backup restore snapshots this (not [snapshot]) precisely because [snapshot] collapses
     *  "unreadable" to an empty list, and an undo built on that lie would write an empty list over
     *  bytes that were actually still recoverable. */
    internal fun rawValue(prefs: Preferences): String? = prefs[key]

    /** Writes [raw] verbatim — or clears the key when null — bypassing [mutate]'s corruption guard on
     *  purpose. Used only to commit an import or restore a pre-import snapshot, both of which must
     *  act on exactly what is there rather than a "safe" reconstruction of it. Participates in a
     *  caller-supplied transaction so several stores can be replaced atomically in one write. */
    internal fun writeRaw(prefs: MutablePreferences, raw: String?) {
        if (raw == null) prefs.remove(key) else prefs[key] = raw
    }

    /** Replace the entry with the same id (used to set shares / alerts). Adds it if absent. */
    suspend fun update(asset: Asset) = context.dataStore.edit { prefs ->
        mutate(prefs) { cur ->
            if (cur.any { it.id == asset.id }) cur.map { if (it.id == asset.id) asset else it }
            else cur + asset
        }
    }

    /**
     * Append a purchase lot to the tracked asset matching [symbol] (MONEY-2) — the ONE path a
     * recorded journal fill ([com.stocktracker.app.ui.journal.JournalViewModel.markTaken]) and an
     * exercised call ([com.stocktracker.app.ui.calls.CallsViewModel.markExercised]) both funnel
     * through, so a real, dated acquisition is recorded the same way regardless of which screen it
     * came from.
     *
     * Matching is case-insensitive and strips a trailing "-USD" — the verdict journal stores crypto
     * symbols Yahoo-style ("BTC-USD") so its replay can look the bars up, and that must still land on
     * the plain "BTC" watchlist entry.
     *
     * Returns true if a matching tracked asset was found and updated, false otherwise. This never
     * CREATES an asset from a bare ticker: a symbol with nothing on the watchlist could be a stock or
     * a coin, and guessing wrong would silently mis-file the position.
     */
    suspend fun addLot(symbol: String, lot: Lot): Boolean {
        var applied = false
        context.dataStore.edit { prefs ->
            mutate(prefs) { cur ->
                val (next, matched) = appendLot(cur, symbol, lot)
                applied = matched
                next
            }
        }
        return applied
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

        /**
         * The pure matching-and-append step behind [addLot], pulled out so it is unit-testable
         * without a Context/DataStore (MONEY-2) — mirrors [CallPositionStore.currentForMutation] /
         * [VerdictJournalStore]'s split between "the rule" and "the storage plumbing that applies it".
         *
         * Matching is case-insensitive and strips a trailing "-USD" — the verdict journal stores
         * crypto symbols Yahoo-style ("BTC-USD") for its replay, and that must still land on the plain
         * "BTC" watchlist entry. Returns the (possibly unchanged) list plus whether a match was found;
         * this never CREATES an asset from a bare ticker, since an unmatched symbol could be a stock
         * or a coin and guessing wrong would silently mis-file the position.
         */
        fun appendLot(assets: List<Asset>, symbol: String, lot: Lot): Pair<List<Asset>, Boolean> {
            val bare = symbol.removeSuffix("-USD")
            val match = assets.firstOrNull { it.symbol.equals(bare, ignoreCase = true) } ?: return assets to false
            return assets.map { if (it.id == match.id) it.copy(lots = it.lots + lot) else it } to true
        }
    }
}
