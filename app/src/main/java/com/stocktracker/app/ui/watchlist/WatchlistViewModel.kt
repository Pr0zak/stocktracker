package com.stocktracker.app.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.remote.GateResponse
import com.stocktracker.app.data.remote.MarketNowResponse
import com.stocktracker.app.data.remote.RegimeResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.data.prefs.PriceCache
import com.stocktracker.app.data.prefs.SectorCache
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

/** State for the auto-loaded market-regime banner (Theme D). */
data class RegimeUi(
    val loading: Boolean = false,
    val result: RegimeResponse? = null,
    val error: String? = null,
)

/**
 * SWT-13 — state for the five-leg regime gate card.
 *
 * [result] and [error] are BOTH meaningful at once: a failed refresh keeps the last reading and sets
 * the error beside it, the same shape [com.stocktracker.app.ui.heatmap.HeatmapViewModel] uses, so a
 * transient network blip does not blank a card that is still holding a real answer. The card then
 * says "showing the last read" rather than pretending the gate is unknown.
 *
 * Nothing here flattens [GateResponse.passed]. It stays true / false / null all the way to the
 * composable, because null is "a leg could not be measured", which is not a closed gate.
 */
data class GateUi(
    val loading: Boolean = false,
    val result: GateResponse? = null,
    val error: String? = null,
)

data class WatchlistUiState(
    val items: List<WatchlistItemUi> = emptyList(),
    val loading: Boolean = true,
    val stocksEnabled: Boolean = true,
    /**
     * SWT-14 — what the dip strip is ALLOWED TO SAY, as the same four-state machine the full radar
     * screen runs on ([DipRadar.state]). The strip used to have one state: a list of dips, absent
     * whenever anything went wrong. A user who never opens the radar screen therefore had no way to
     * learn that the scan service was down — the strip's absence is indistinguishable from a market
     * with no dips in it, which is the reassuring reading and the one that stops them looking.
     *
     * There is exactly ONE state machine for this, shared with [DipListScreen], because two would
     * drift and the screens would disagree about whether the market is calm.
     */
    val dipRadar: DipRadarState = DipRadarState.Loading,
    /**
     * Set when a refresh FAILED and [dipRadar] is therefore the previous, still-displayed scan.
     *
     * Without this the strip had no way to keep a good reading through a blip: a fetch that failed
     * after a successful one replaced Ready with Unreachable, so a working dip list vanished and was
     * replaced by an error — losing information the app already held, to a transient network
     * failure. Every other card here (gate, regime, heatmap) keeps its last reading and sets an
     * error beside it, and the strip now does the same: the dips stay on screen and say they are the
     * last read rather than the current one.
     */
    val dipStale: String? = null,
    val marketNow: MarketNowUi = MarketNowUi(),
    val regime: RegimeUi = RegimeUi(),
    /** SWT-13 — the five-leg gate. Free (no LLM), so it loads regardless of the AI master switch. */
    val gate: GateUi = GateUi(),
    /**
     * Symbol -> sector, for the vertical sections.
     *
     * A key present with a NULL value means the server classified it and found no sector; a key
     * ABSENT means nobody has looked yet. The screen renders those as "Other" and "Not classified
     * yet" respectively, so the two must not be flattened into one here.
     */
    val sectors: Map<String, String?> = emptyMap(),
    /** Last deleted asset, held so the UI can offer UNDO. Non-null means the snackbar is due. */
    val recentlyRemoved: Asset? = null,
    /** A refresh the user asked for is in flight — distinct from the initial [loading] spinner. */
    val refreshing: Boolean = false,
    /**
     * Set when a fetch reached nothing at all and the rows are the previous prices.
     *
     * The timestamps alone would leave the user to infer this from a number that failed to move,
     * which is a lot to ask of a line of small grey text. Say it.
     */
    val refreshError: String? = null,
) {
    /**
     * The dips we are actually holding — DERIVED from [dipRadar] rather than stored beside it, so
     * there is no second copy to go stale. Empty here means "we hold a scan and none qualified" only
     * when [dipRadar] is [DipRadarState.Ready]; in every other state it means we hold no scan at
     * all, and the strip must render that state rather than this list.
     */
    val dips: List<DipEntry> get() = (dipRadar as? DipRadarState.Ready)?.dips.orEmpty()
}

class WatchlistViewModel : ViewModel() {

    private val repo = ServiceLocator.repository
    private val store = ServiceLocator.watchlistStore
    private val settings = ServiceLocator.settingsStore
    private val cache = ServiceLocator.priceCache
    private val sectorCache = ServiceLocator.sectorCache
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
        loadRegime()
        loadGate()
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
                    // Cheap and cached — a new ticker needs a vertical before its first price lands,
                    // or it appears under "Not classified yet" and then jumps sections a second later.
                    loadSectors()

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

    /**
     * Re-read every price from the source, not from the memo.
     *
     * [MarketRepository.invalidateQuotes] first, because otherwise this is a no-op the user cannot
     * distinguish from a market that did not move — the TTL would serve the same quote back and, in
     * an outage, the stale-while-error path would return the old value with no error at all.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, refreshError = null) }
            repo.invalidateQuotes()
            loadQuotes(currentAssets)
            loadBelowLineFlags()
            loadSectors()
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** Open the "Market now" dialog and load the AI overview (a cached result is reused until refreshed). */
    /** Auto-load the market-regime banner (Theme D). One market-wide LLM call, cached ~30 min server-
     *  side, so this is cheap on a re-open. Silent no-op when the AI analyst is off or unconfigured —
     *  the banner just doesn't appear. [force] bypasses the "already loaded" guard for the refresh tap. */
    fun loadRegime(force: Boolean = false) {
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            val on = settings.aiAnalystEnabled.first()
            if (base.isBlank() || !on) {
                _state.update { it.copy(regime = RegimeUi()) } // clear any stale banner when AI is off
                return@launch
            }
            if (!force && (_state.value.regime.result != null || _state.value.regime.loading)) return@launch
            _state.update { it.copy(regime = it.regime.copy(loading = true, error = null)) }
            val res = runCatching { signalsApi.regime(base) }
            _state.update { st ->
                st.copy(regime = st.regime.copy(
                    loading = false,
                    result = res.getOrNull() ?: st.regime.result,
                    error = res.exceptionOrNull()?.let { it.message ?: "Couldn't load the regime." },
                ))
            }
        }
    }

    /**
     * SWT-13 — load the five-leg regime gate.
     *
     * Deliberately NOT gated on `aiAnalystEnabled`, unlike [loadRegime] directly above it. That one
     * spends a model call to write a sentence; this one is pure arithmetic over index prices and the
     * nightly scan's breadth, costs nothing, and is the checkable half of the same question. Hiding
     * it behind the AI switch would mean turning off the narrative also turned off the measurement.
     *
     * A failed refresh KEEPS the previous verdict and sets the error beside it, matching
     * HeatmapViewModel: a blip must not blank a card that still holds a real answer. Nothing here
     * flattens `passed` — it stays true / false / null the whole way to the composable, because null
     * means a leg could not be measured and that is not a closed gate.
     */
    fun loadGate(force: Boolean = false) {
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) {
                _state.update { it.copy(gate = GateUi()) }
                return@launch
            }
            if (!force && (_state.value.gate.result != null || _state.value.gate.loading)) return@launch
            _state.update { it.copy(gate = it.gate.copy(loading = true, error = null)) }
            val res = runCatching { signalsApi.gate(base, refresh = force) }
            _state.update { st ->
                st.copy(gate = st.gate.copy(
                    loading = false,
                    result = res.getOrNull() ?: st.gate.result,
                    error = res.exceptionOrNull()?.let { it.message ?: "Couldn't load the gate." },
                ))
            }
        }
    }

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

    /**
     * SWT-14 — the dip strip's own "try again", for the states that a retry could plausibly fix.
     *
     * Deliberately NOT [refresh]: the strip failing says nothing about the price feed, and making the
     * user re-pull every quote to re-ask the scan service would be a slower, noisier fix for a
     * narrower problem.
     *
     * It does not flip the state to Loading first. A retry that fails would then have thrown away a
     * scan we are still holding and showing, and the strip would blank instead of keeping its last
     * read.
     */
    fun reloadDips() {
        viewModelScope.launch { loadBelowLineFlags() }
    }

    /** Pull the latest nightly scan once to learn which watchlist names sit below their 200-week
     *  line, and stamp the flag onto the current rows. Best-effort; no-op without a Signals URL. */
    private suspend fun loadBelowLineFlags() {
        val configured = settings.signalsApiUrl.first().isNotBlank()
        // SWT-14. This used to `return` on every unhappy path, which was an improvement on the older
        // behaviour (overwriting the strip with an emptiness that read as "checked, all clear") but
        // still left the strip with exactly one thing it could say. A user who never opens the full
        // radar screen had no way to learn the scan service was down: the strip's silence looks the
        // same as a market with no dips in it, and that is the reassuring reading.
        //
        // So resolve the SAME four-state machine the radar screen runs on and hand it to the UI.
        // One state machine, shared — two would drift and the two screens would eventually disagree
        // about whether the market is calm.
        val scan = if (configured) {
            runCatching { signalsApi.latestScan(settings.signalsApiUrl.first()) }
        } else {
            Result.success(null)
        }
        val radar = DipRadar.state(
            scan = scan.getOrNull(),
            error = scan.exceptionOrNull(),
            configured = configured,
        )

        // The below-200w flags are a SEPARATE fact from the strip's state and keep the old rule:
        // only overwrite them when a real scan actually arrived. A failed fetch must not clear a
        // flag we already hold and are still showing on the row.
        val rows = (scan.getOrNull())?.results?.takeIf { it.isNotEmpty() && scan.getOrNull()?.hasScan == true }
        if (rows != null) {
            belowLineMap = rows.mapNotNull { r -> r.below200wma?.let { r.symbol.uppercase() to it } }.toMap()
        }
        _state.update { st ->
            // Keep a good scan through a blip — the rule lives in DipRadar.holdThroughBlip so it
            // is testable, and so the reasons NotConfigured and NoScan are excluded from it are
            // written down next to the rule rather than here.
            val upd = DipRadar.holdThroughBlip(st.dipRadar, radar)
            st.copy(
                items = if (rows != null) {
                    st.items.map { it.copy(below200wma = belowLineMap[scanKey(it.asset)]) }
                } else {
                    st.items
                },
                dipRadar = upd.state,
                dipStale = upd.stale,
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

    /**
     * Star / unstar a row. Written straight through to the store so it survives a restart.
     *
     * Nothing here defaults to true. A watchlist entry is not a favourite until the user says so —
     * a favourites section pre-filled with the whole watchlist would be a filter that filters
     * nothing, and the user would have to un-star their way to a useful one.
     */
    fun toggleFavorite(asset: Asset) {
        val next = asset.copy(favorite = !asset.favorite)
        // Optimistic: the star is a direct manipulation and must not wait on a DataStore round trip.
        _state.update { st ->
            st.copy(items = st.items.map { if (it.asset.id == asset.id) it.copy(asset = next) else it })
        }
        viewModelScope.launch { store.update(next) }
    }

    /**
     * Sector per symbol, for the vertical sections.
     *
     * Served from the on-device cache first so the sections survive a backend outage, then topped up
     * for anything unseen or older than the cache TTL. Crypto is skipped entirely — it is bucketed
     * locally and asking Yahoo about it would only ever return null.
     *
     * Failure is silent BY DESIGN and safe because of how the missing case renders: a symbol with no
     * cached answer falls into "Not classified yet", never "Other". The screen says it does not know
     * rather than claiming the security has no sector, so there is nothing to alarm the user with.
     */
    fun loadSectors() {
        viewModelScope.launch {
            val symbols = currentAssets
                .filter { it.type != AssetType.CRYPTO }
                .map { it.symbol.uppercase() }
                .distinct()
            if (symbols.isEmpty()) return@launch

            sectorCache.snapshot().let { cached -> _state.update { it.copy(sectors = cached.toLabels()) } }

            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) return@launch
            val stale = sectorCache.stale(symbols)
            if (stale.isEmpty()) return@launch

            val fetched = runCatching { signalsApi.sectors(base, stale) }.getOrNull() ?: return@launch
            val now = System.currentTimeMillis()
            sectorCache.put(fetched.mapValues { (_, p) -> SectorCache.Entry(p.sector, p.industry, now) })
            _state.update { it.copy(sectors = sectorCache.snapshot().toLabels()) }
        }
    }

    /** Cache entries → the map the UI groups by. Keeps the null/absent distinction intact: a key
     *  present with a null value is "classified, no sector"; an absent key is "never looked up". */
    private fun Map<String, SectorCache.Entry>.toLabels(): Map<String, String?> =
        mapValues { (_, e) -> e.sector }

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
        _state.update {
            it.copy(
                items = it.items.filterNot { i -> i.asset.id == asset.id },
                // Held for UNDO. `Asset` carries shares, avgCost and alerts, and the app stores
                // them nowhere else — no broker sync, no server copy — so a mis-tapped delete used
                // to be an unrecoverable loss of hand-entered data.
                recentlyRemoved = asset,
            )
        }
        viewModelScope.launch { store.remove(asset) }
    }

    /** Put back the last removed asset, with its shares, cost basis and alerts intact. */
    fun undoRemove() {
        val asset = _state.value.recentlyRemoved ?: return
        _state.update { it.copy(recentlyRemoved = null) }
        viewModelScope.launch {
            store.add(asset)
            refresh()
        }
    }

    fun clearUndo() = _state.update { it.copy(recentlyRemoved = null) }

    /** `spark` is a real price series: CoinGecko for crypto, intraday history for stocks. */
    private class Fetched(val asset: Asset, val quote: Quote?, val cryptoSpark: List<Double>)

    /**
     * A stock sparkline from the rolling price buffer, or NOTHING.
     *
     * Unlike crypto (which gets a real series from CoinGecko), stocks have no intraday history here —
     * these are just the prices this app happened to observe. That's defensible as a shape only if
     * it covers a bounded, recent window with enough points to mean something; the buffer used to be
     * unbounded and undated, so a "sparkline" beside today's change could span weeks of refreshes.
     * PriceCache now trims to 24h; below a handful of samples we draw nothing rather than imply a
     * trend from two dots.
     */
    private fun sparkFrom(samples: List<PriceCache.Sample>?): List<Double> {
        val s = samples.orEmpty()
        if (s.size < PriceCache.MIN_SPARK_SAMPLES) return emptyList()
        return s.map { it.price }.downsample(40)
    }

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
                        a, seedQuotes[a.id], sparkFrom(seedBuffers[a.id]),
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
                                    // CoinGecko has no "previous close" — crypto never closes — but
                                    // price minus the 24h change IS the level the percentage is
                                    // measured from, which is what the sparkline baseline needs.
                                    // Without it crypto rows drew no baseline while equities did,
                                    // and BTC is exactly the row where the rising-line/red-number
                                    // contradiction was first spotted.
                                    prevClose = it.price - it.change,
                                )
                            }
                            if (quote != null) cache.putQuote(asset.id, quote)
                            Fetched(asset, quote, (m?.sparkline ?: emptyList()).downsample(40))
                        }
                        AssetType.STOCK -> {
                            val fresh = runCatching { repo.quote(asset) }.getOrNull()
                            if (fresh != null) cache.putQuote(asset.id, fresh)
                            // REAL intraday history, the same source the detail chart uses, rather
                            // than the rolling observed-price buffer. Crypto always had a genuine
                            // series; stocks were drawing whatever prices this app happened to see,
                            // and after that buffer was time-bounded they had nothing to draw at all.
                            // repo.history caches on an intraday TTL, so this is not a fetch per refresh.
                            val hist = repo.sparkline(asset)
                            Fetched(asset, fresh, hist.downsample(40))
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
                // Real history when we have it; the observed-price buffer only as a fallback.
                AssetType.STOCK -> f.cryptoSpark.ifEmpty { sparkFrom(buffers[f.asset.id]) }
            }
            WatchlistItemUi(f.asset, quote, spark, below200wma = belowLineMap[scanKey(f.asset)])
        }
        if (gen != loadGeneration) return // superseded (e.g. by a remove) — don't clobber the UI
        // Every row on screen came out of the cache: the fetch reached nothing. The rows still show
        // prices, which is right, but nothing else on this screen would say why they stopped moving.
        val reachedNothing = assets.isNotEmpty() && fetched.none { it.quote != null }
        _state.update {
            it.copy(
                items = items,
                loading = false,
                refreshError = if (reachedNothing) "Couldn't reach the price service" else null,
            )
        }
    }
}
