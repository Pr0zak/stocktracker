package com.stocktracker.app.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.remote.MarketNowResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.util.downsample
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WatchlistItemUi(
    val asset: Asset,
    val quote: Quote?,
    val sparkline: List<Double>,
    /** Below its 200-week line per the latest nightly scan — drives the "Below 200w" tab + row badge. */
    val below200wma: Boolean? = null,
)

/** A "good time to add" entry for the dip strip atop the watchlist (from the nightly scan). */
data class DipEntry(
    val symbol: String,        // display form (crypto -USD stripped)
    val tier: String,          // mega_dip | below_line | oversold | pullback_10 | pullback_5
    val pctOffHigh: Double?,   // off the recent ~3-month high
    val pctOff52w: Double?,    // off the 52-week high
)

/** State for the on-demand "Market now" AI overview dialog (AIE-5). */
data class MarketNowUi(
    val open: Boolean = false,
    val loading: Boolean = false,
    val result: MarketNowResponse? = null,
    val error: String? = null,
)

data class WatchlistUiState(
    val items: List<WatchlistItemUi> = emptyList(),
    val loading: Boolean = true,
    val stocksEnabled: Boolean = true,
    /** "Good time to add" dips from the latest scan, most-severe first — the buy-signal strip. */
    val dips: List<DipEntry> = emptyList(),
    val marketNow: MarketNowUi = MarketNowUi(),
)

class WatchlistViewModel : ViewModel() {

    private val repo = ServiceLocator.repository
    private val store = ServiceLocator.watchlistStore
    private val settings = ServiceLocator.settingsStore
    private val cache = ServiceLocator.priceCache
    private val signalsApi = SignalsApiService()

    private val _state = MutableStateFlow(WatchlistUiState(stocksEnabled = repo.stocksEnabled))
    val state = _state.asStateFlow()

    // symbol (scan form: uppercase stock / "SYM-USD" crypto) → below its 200-week line, from the
    // latest nightly scan. Applied to rows as they load so the "Below 200w" tab/badge can filter.
    private var belowLineMap: Map<String, Boolean> = emptyMap()
    private fun scanKey(a: Asset): String =
        if (a.type == AssetType.CRYPTO) "${a.symbol.uppercase()}-USD" else a.symbol.uppercase()

    private var currentAssets: List<Asset> = emptyList()
    private var lastKey: String? = null

    // Bumped whenever the desired list changes; a load whose generation is stale won't emit,
    // so an in-flight refresh can't re-add a just-removed row.
    private var loadGeneration = 0

    init {
        viewModelScope.launch { loadBelowLineFlags() }
        viewModelScope.launch {
            // Reload when the watchlist OR the Finnhub key changes (adding a key should immediately
            // start fetching stocks). distinctUntilChanged avoids reacting to unrelated settings.
            combine(
                store.watchlist.distinctUntilChanged(),
                settings.finnhubApiKey.distinctUntilChanged(),
            ) { assets, key -> assets to key }
                .collect { (assets, key) ->
                    val keyChanged = key != lastKey
                    lastKey = key
                    ServiceLocator.finnhubKeyOverride = key // ensure repo sees it before we fetch
                    currentAssets = assets

                    // Removal (or shares/alerts edit that only drops/keeps existing ids) shouldn't
                    // trigger a network refetch — reconcile the list instantly. Only fetch when new
                    // tickers appear or the key changed.
                    val newIds = assets.map { it.id }.toSet()
                    val displayedIds = _state.value.items.map { it.asset.id }.toSet()
                    val noNewTickers = !keyChanged && _state.value.items.isNotEmpty() && newIds.all { it in displayedIds }

                    if (noNewTickers) {
                        loadGeneration++ // invalidate any in-flight load
                        _state.update { st ->
                            st.copy(
                                items = assets.mapNotNull { a -> st.items.firstOrNull { it.asset.id == a.id }?.copy(asset = a) },
                                loading = false,
                                stocksEnabled = repo.stocksEnabled,
                            )
                        }
                    } else {
                        _state.update { it.copy(stocksEnabled = repo.stocksEnabled) }
                        loadQuotes(assets)
                    }
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loadQuotes(currentAssets)
            loadBelowLineFlags()
        }
    }

    /** Open the "Market now" dialog and load the AI overview (a cached result is reused until refreshed). */
    fun openMarketNow() {
        _state.update { it.copy(marketNow = it.marketNow.copy(open = true)) }
        loadMarketNow(force = false)
    }

    fun dismissMarketNow() {
        _state.update { it.copy(marketNow = it.marketNow.copy(open = false)) }
    }

    /** Fetch the instant market overview (one LLM call; the server caches ~3 min). Gated on a configured
     *  Signals URL + the AI master switch. [force] re-runs even when a result is already loaded. */
    fun loadMarketNow(force: Boolean) {
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            val on = settings.aiAnalystEnabled.first()
            if (base.isBlank() || !on) {
                _state.update { st ->
                    st.copy(marketNow = st.marketNow.copy(
                        loading = false,
                        error = if (base.isBlank()) "Set the Signals service URL in Settings to use this."
                        else "The AI analyst is off — turn it on in Settings.",
                    ))
                }
                return@launch
            }
            if (!force && _state.value.marketNow.result != null) return@launch
            _state.update { it.copy(marketNow = it.marketNow.copy(loading = true, error = null)) }
            val res = runCatching { signalsApi.marketNow(base) }
            _state.update { st ->
                st.copy(marketNow = st.marketNow.copy(
                    loading = false,
                    result = res.getOrNull() ?: st.marketNow.result,
                    error = res.exceptionOrNull()?.let { it.message ?: "Couldn't load the market overview." },
                ))
            }
        }
    }

    /** Pull the latest nightly scan once to learn which watchlist names sit below their 200-week
     *  line, and stamp the flag onto the current rows. Best-effort; no-op without a Signals URL. */
    private suspend fun loadBelowLineFlags() {
        val base = settings.signalsApiUrl.first()
        if (base.isBlank()) return
        val scan = runCatching { signalsApi.latestScan(base) }.getOrNull() ?: return
        belowLineMap = scan.results.mapNotNull { r -> r.below200wma?.let { r.symbol.uppercase() to it } }.toMap()
        // "Good time to add" dips, most-severe first, for the strip atop the watchlist.
        val order = listOf("mega_dip", "below_line", "oversold", "pullback_10", "pullback_5")
        val dips = scan.results.mapNotNull { r ->
            r.dip?.let { DipEntry(r.symbol.removeSuffix("-USD"), it, r.pctOffRecentHigh, r.pctOff52wHigh) }
        }.sortedBy { order.indexOf(it.tier).let { i -> if (i < 0) 99 else i } }
        _state.update { st ->
            st.copy(
                items = st.items.map { it.copy(below200wma = belowLineMap[scanKey(it.asset)]) },
                dips = dips,
            )
        }
    }

    // Set by a real drag; guards persistOrder() from firing on initial composition (which would
    // otherwise write the still-empty list and wipe the watchlist).
    private var pendingReorder = false

    /** Drag-to-reorder (live): reorder the in-memory list only; [persistOrder] saves on drop. */
    fun moveLocal(fromId: String, toId: String) {
        val cur = _state.value.items
        val fromIdx = cur.indexOfFirst { it.asset.id == fromId }
        val toIdx = cur.indexOfFirst { it.asset.id == toId }
        if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return
        val newItems = cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
        loadGeneration++ // don't let an in-flight fetch clobber the new order
        _state.update { it.copy(items = newItems) }
        currentAssets = newItems.map { it.asset }
        pendingReorder = true
    }

    /** Persist the current row order — no-op unless a drag actually reordered the list. */
    fun persistOrder() {
        if (!pendingReorder) return
        pendingReorder = false
        val ordered = _state.value.items.map { it.asset }
        if (ordered.isEmpty()) return
        viewModelScope.launch { store.setAll(ordered) }
    }

    /** Create a new named watchlist. */
    fun createGroup(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val cur = settings.watchlistGroups.first()
            if (!cur.contains(clean)) settings.setWatchlistGroups(cur + clean)
        }
    }

    /** Delete a named watchlist and strip it from every asset's membership. */
    fun deleteGroup(name: String) {
        viewModelScope.launch {
            val cur = store.snapshot()
            store.setAll(cur.map { if (it.groups.contains(name)) it.copy(groups = it.groups - name) else it })
            settings.setWatchlistGroups(settings.watchlistGroups.first() - name)
        }
    }

    fun remove(asset: Asset) {
        // Drop it from the UI immediately — don't wait for the DataStore write + quote refetch —
        // and invalidate any in-flight load so it can't re-add the row.
        loadGeneration++
        _state.update { it.copy(items = it.items.filterNot { i -> i.asset.id == asset.id }) }
        viewModelScope.launch { store.remove(asset) }
    }

    private class Fetched(val asset: Asset, val quote: Quote?, val cryptoSpark: List<Double>)

    private suspend fun loadQuotes(assets: List<Asset>) {
        val gen = ++loadGeneration

        // Seed instantly from cache so the dashboard is never a blank spinner while the network
        // is in flight (rows show last-known prices, or "—" on a first-ever launch).
        val seedQuotes = cache.snapshotQuotes()
        val seedBuffers = cache.snapshotBuffers()
        _state.update { st ->
            st.copy(
                items = assets.map { a ->
                    WatchlistItemUi(
                        a, seedQuotes[a.id], (seedBuffers[a.id] ?: emptyList()).downsample(40),
                        below200wma = belowLineMap[scanKey(a)],
                    )
                },
                loading = true,
            )
        }

        val markets = runCatching { repo.cryptoMarkets(assets) }.getOrDefault(emptyMap())

        // Fetch + cache in parallel (sequential stock quotes + 429 backoff made refresh slow).
        val fetched = coroutineScope {
            assets.map { asset ->
                async {
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
            }.awaitAll()
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
            WatchlistItemUi(f.asset, quote, spark, below200wma = belowLineMap[scanKey(f.asset)])
        }
        if (gen != loadGeneration) return // superseded (e.g. by a remove) — don't clobber the UI
        _state.update { it.copy(items = items, loading = false) }
    }
}
