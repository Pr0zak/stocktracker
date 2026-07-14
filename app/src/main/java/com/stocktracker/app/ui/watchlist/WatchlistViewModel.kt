package com.stocktracker.app.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.util.downsample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WatchlistItemUi(
    val asset: Asset,
    val quote: Quote?,
    val sparkline: List<Double>,
)

data class WatchlistUiState(
    val items: List<WatchlistItemUi> = emptyList(),
    val loading: Boolean = true,
    val stocksEnabled: Boolean = true,
)

class WatchlistViewModel : ViewModel() {

    private val repo = ServiceLocator.repository
    private val store = ServiceLocator.watchlistStore
    private val cache = ServiceLocator.priceCache

    private val _state = MutableStateFlow(WatchlistUiState(stocksEnabled = repo.stocksEnabled))
    val state = _state.asStateFlow()

    private var currentAssets: List<Asset> = emptyList()

    init {
        viewModelScope.launch {
            // distinctUntilChanged: the watchlist + settings share one DataStore, so a settings
            // write would otherwise re-emit an identical list and pointlessly refetch quotes.
            store.watchlist.distinctUntilChanged().collect { assets ->
                currentAssets = assets
                loadQuotes(assets)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { loadQuotes(currentAssets) }
    }

    fun remove(asset: Asset) {
        viewModelScope.launch { store.remove(asset) }
    }

    private class Fetched(val asset: Asset, val quote: Quote?, val cryptoSpark: List<Double>)

    private suspend fun loadQuotes(assets: List<Asset>) {
        _state.update { it.copy(loading = true) }
        val markets = runCatching { repo.cryptoMarkets(assets) }.getOrDefault(emptyMap())

        // Fetch + cache first...
        val fetched = assets.map { asset ->
            when (asset.type) {
                AssetType.CRYPTO -> {
                    val m = markets[asset.coinGeckoId]
                    val quote = m?.let {
                        Quote(
                            symbol = asset.symbol,
                            price = it.price,
                            change = it.change,
                            changePercent = it.changePercent,
                            currency = "USD",
                            asOfEpochMs = System.currentTimeMillis(),
                        )
                    }
                    if (quote != null) cache.putQuote(asset.id, quote)
                    Fetched(asset, quote, (m?.sparkline ?: emptyList()).downsample(40))
                }
                AssetType.STOCK -> {
                    val fresh = runCatching { repo.quote(asset) }.getOrNull()
                    if (fresh != null) cache.putQuote(asset.id, fresh)
                    Fetched(asset, fresh, emptyList())
                }
            }
        }

        // ...then read the cache maps ONCE (avoids O(N^2) full-map decodes).
        val buffers = cache.snapshotBuffers()
        val cachedQuotes = cache.snapshotQuotes()
        val items = fetched.map { f ->
            val quote = f.quote ?: cachedQuotes[f.asset.id]
            val spark = when (f.asset.type) {
                AssetType.CRYPTO -> f.cryptoSpark
                AssetType.STOCK -> (buffers[f.asset.id] ?: emptyList()).downsample(40)
            }
            WatchlistItemUi(f.asset, quote, spark)
        }
        _state.update { it.copy(items = items, loading = false) }
    }
}
