package com.stocktracker.app.ui.funds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.FundGroup
import com.stocktracker.app.data.remote.FundGroupsResponse
import com.stocktracker.app.data.remote.FundOverlapResponse
import com.stocktracker.app.data.remote.FundPerformanceResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.detail.FundCostText
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Funds screen (FUND-1..6): how the user's funds overlap, what they cost a year, a check before
 * buying another, and the catalogue of look-alike groups.
 *
 * Holdings never leave the phone. The backend is sent symbols only, and every dollar figure here —
 * what a fund is worth to the user, what its fee costs them — is computed on this side.
 */
class FundsViewModel : ViewModel() {

    enum class Mode { HOLDINGS, WATCHLIST }

    /** The "before you buy" check for one symbol. */
    data class Check(
        val symbol: String = "",
        val loading: Boolean = false,
        val failed: Boolean = false,
        val notAFund: Boolean = false,
        val fee: String? = null,
        val covers: String? = null,
        val lines: List<String> = emptyList(),
    )

    /** One look-alike group's performance, loaded when the group is opened. */
    data class GroupPerf(val loading: Boolean = false, val resp: FundPerformanceResponse? = null, val failed: Boolean = false)

    data class UiState(
        val configured: Boolean = true,
        val mode: Mode = Mode.HOLDINGS,
        val loading: Boolean = true,
        val failed: Boolean = false,
        /** Holdings mode found no funds among the holdings and fell back to the watchlist. */
        val noHeldFunds: Boolean = false,
        val overlap: FundOverlapResponse? = null,
        /** Dollars per held fund, priced on the phone (holdings mode only). */
        val values: Map<String, Double> = emptyMap(),
        /** Held funds that could not be priced — named, and left out of every dollar figure. */
        val unpriced: List<String> = emptyList(),
        /** Held funds priced from the last saved quote because a live one failed — said on screen. */
        val cachePriced: List<String> = emptyList(),
        val heldCoins: List<String> = emptyList(),
        val groups: FundGroupsResponse? = null,
        val groupsFailed: Boolean = false,
        val groupPerf: Map<String, GroupPerf> = emptyMap(),
        val check: Check? = null,
    ) {
        val groupsById: Map<String, FundGroup>? get() = groups?.groups?.associateBy { it.id }
    }

    private val store = ServiceLocator.watchlistStore
    private val settings = ServiceLocator.settingsStore
    private val repo = ServiceLocator.repository
    private val api = SignalsApiService()

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null
    private var checkJob: Job? = null
    private var autoFellBack = false

    init { load() }

    fun setMode(mode: Mode) {
        if (mode == _state.value.mode) return
        // Clear the other mode's result at once: left in place it was drawn under the new heading
        // ("Your watchlist's funds" over the holdings, with their dollars) until the new load landed.
        _state.update {
            it.copy(mode = mode, noHeldFunds = false, loading = true, failed = false, overlap = null,
                values = emptyMap(), unpriced = emptyList(), cachePriced = emptyList())
        }
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) {
                _state.update { it.copy(configured = false, loading = false) }
                return@launch
            }
            _state.update { it.copy(configured = true, loading = true, failed = false) }
            val groupsCall = async { runCatching { api.fundGroups(base) }.getOrNull() }

            val list = store.watchlist.first()
            val held = list.filter { (it.shares ?: 0.0) > 0.0 }
            val mode = _state.value.mode
            val symbols = when (mode) {
                Mode.HOLDINGS -> held.filter { it.type == AssetType.STOCK }
                Mode.WATCHLIST -> list.filter { it.type == AssetType.STOCK }
            }.map { it.symbol.uppercase() }.distinct().take(80)

            val resp = if (symbols.isEmpty()) FundOverlapResponse()
            else runCatching { api.fundOverlap(base, symbols) }.getOrNull()
            // runCatching also swallows the CancellationException of a superseded load (a mode
            // switch, a retry); without this it went on to write "Couldn't load" over the new one.
            ensureActive()

            // Nothing held is a fund: show the watchlist's funds instead, once, and say so. Only when
            // the lookup actually answered — "couldn't look it up" is not "none of them is a fund".
            if (resp != null && mode == Mode.HOLDINGS && resp.funds.isEmpty() && resp.unknown.isEmpty() &&
                resp.live && !autoFellBack && list.any { it.type == AssetType.STOCK }
            ) {
                autoFellBack = true
                _state.update { it.copy(mode = Mode.WATCHLIST, noHeldFunds = true) }
                groupsCall.cancel()
                load()
                return@launch
            }

            val values = mutableMapOf<String, Double>()
            val unpriced = mutableListOf<String>()
            val cachePriced = mutableListOf<String>()
            if (resp != null && mode == Mode.HOLDINGS) {
                for (a in held.filter { it.symbol.uppercase() in resp.funds }) {
                    val live = runCatching { repo.quote(a) }.getOrNull()
                    ensureActive()
                    val q = live ?: ServiceLocator.priceCache.getQuote(a.id)
                    val px = q?.price?.takeIf { it.isFinite() && it > 0.0 }
                    if (px == null) {
                        unpriced += a.symbol.uppercase()
                    } else {
                        if (live == null) cachePriced += a.symbol.uppercase()
                        values[a.symbol.uppercase()] = (values[a.symbol.uppercase()] ?: 0.0) + (a.shares ?: 0.0) * px
                    }
                }
            }
            val groups = groupsCall.await()
            ensureActive()
            _state.update {
                it.copy(
                    loading = false,
                    failed = resp == null,
                    overlap = resp ?: it.overlap,
                    values = values,
                    unpriced = unpriced,
                    cachePriced = cachePriced,
                    heldCoins = held.filter { a -> a.type == AssetType.CRYPTO }.map { a -> a.symbol },
                    groups = groups ?: it.groups,
                    groupsFailed = groups == null,
                )
            }
        }
    }

    fun loadGroupPerf(g: FundGroup) {
        val cur = _state.value.groupPerf[g.id]
        if (cur != null && (cur.loading || cur.resp != null)) return
        _state.update { it.copy(groupPerf = it.groupPerf + (g.id to GroupPerf(loading = true))) }
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            val r = runCatching { api.fundPerformance(base, g.funds.map { it.symbol }) }.getOrNull()
            _state.update { it.copy(groupPerf = it.groupPerf + (g.id to GroupPerf(resp = r, failed = r == null))) }
        }
    }

    /** "Before you buy": [raw] against everything the user holds. */
    fun check(raw: String) {
        val sym = raw.trim().uppercase()
        if (sym.isBlank()) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _state.update { it.copy(check = Check(sym, loading = true)) }
            val base = settings.signalsApiUrl.first()
            val allHeld = store.watchlist.first()
                .filter { it.type == AssetType.STOCK && (it.shares ?: 0.0) > 0.0 }
                .map { it.symbol.uppercase() }
                .distinct()
            val held = allHeld.filter { it != sym }.take(79)
            val resp = runCatching { api.fundOverlap(base, listOf(sym) + held) }.getOrNull()
            // A superseded check (a newer check() or clearCheck()) must not write its stale result.
            ensureActive()
            val check = when {
                resp == null || sym in resp.unknown -> Check(sym, failed = true)
                sym !in resp.funds -> Check(sym, notAFund = true)
                else -> {
                    val ownedFunds = held.filter { it in resp.funds }
                    val ownedStocks = held.filter { it in resp.notFunds }
                    val p = resp.funds.getValue(sym)
                    val lines = FundsLogic.beforeYouBuy(sym, resp, ownedFunds, ownedStocks, alreadyOwned = sym in allHeld)
                    Check(
                        sym,
                        fee = FundCostText.headline(p.expenseRatioPct),
                        covers = FundsLogic.covers(p),
                        lines = lines.ifEmpty {
                            listOf("You don't hold any funds or stocks for it to overlap with.")
                        },
                    )
                }
            }
            _state.update { it.copy(check = check) }
        }
    }

    fun clearCheck() {
        checkJob?.cancel()
        _state.update { it.copy(check = null) }
    }

    /** The asset to open for a fund row. */
    fun assetFor(symbol: String, name: String?): Asset = Asset(symbol, AssetType.STOCK, name ?: symbol)
}
