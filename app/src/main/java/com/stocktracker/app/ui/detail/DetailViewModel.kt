package com.stocktracker.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetAlerts
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val asset: Asset,
    val quote: Quote? = null,
    val chart: List<PricePoint> = emptyList(),
    val range: ChartRange = ChartRange.MONTH,
    val loadingChart: Boolean = true,
    /** True when the last fetch *failed* (vs. succeeding with genuinely no data) — drives retry UI. */
    val chartError: Boolean = false,
    val inWatchlist: Boolean = false,
    val shares: Double? = null,
    val avgCost: Double? = null,
    val alerts: AssetAlerts = AssetAlerts(),
    val groups: List<String> = emptyList(),
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
)

class DetailViewModel(private val asset: Asset) : ViewModel() {

    private val repo = ServiceLocator.repository
    private val store = ServiceLocator.watchlistStore
    private val settings = ServiceLocator.settingsStore

    private val _state = MutableStateFlow(DetailUiState(asset))
    val state = _state.asStateFlow()

    @Volatile private var showExtended = false

    // Tracks the in-flight chart fetch so a superseded range's (possibly slow, possibly failed)
    // result can't land after — and clobber — a newer range's chart.
    private var chartJob: Job? = null

    init {
        viewModelScope.launch {
            store.watchlist.collect { list ->
                val stored = list.firstOrNull { it.id == asset.id }
                _state.update {
                    it.copy(
                        inWatchlist = stored != null,
                        shares = stored?.shares,
                        avgCost = stored?.avgCost,
                        alerts = stored?.alerts ?: AssetAlerts(),
                        groups = stored?.groups ?: emptyList(),
                    )
                }
            }
        }
        // Drives the initial chart load and re-fetches when the extended-hours toggle changes.
        viewModelScope.launch {
            settings.showExtendedHours.collect { ext ->
                showExtended = ext
                selectRange(_state.value.range)
            }
        }
        loadQuote()
        viewModelScope.launch {
            val hl = runCatching { repo.fiftyTwoWeek(asset) }.getOrNull()
            if (hl != null) _state.update { it.copy(fiftyTwoWeekHigh = hl.first, fiftyTwoWeekLow = hl.second) }
        }
    }

    private fun loadQuote() {
        viewModelScope.launch {
            val q = runCatching { repo.quote(asset) }.getOrNull()
                ?: ServiceLocator.priceCache.getQuote(asset.id)
            _state.update { it.copy(quote = q) }
        }
    }

    fun selectRange(range: ChartRange) {
        _state.update { it.copy(range = range, loadingChart = true, chartError = false) }
        chartJob?.cancel() // supersede any in-flight load for a previous range
        chartJob = viewModelScope.launch {
            val result = runCatching { repo.history(asset, range, includeExtended = showExtended) }
            // runCatching also swallows CancellationException, so a cancelled (superseded) load would
            // otherwise fall through and write stale state; ensureActive() bails it out first.
            ensureActive()
            _state.update {
                it.copy(
                    chart = result.getOrDefault(emptyList()),
                    loadingChart = false,
                    chartError = result.isFailure, // failed fetch → retry UI; empty success → "no data"
                )
            }
        }
    }

    fun toggleWatchlist() {
        viewModelScope.launch {
            if (_state.value.inWatchlist) store.remove(asset) else store.add(asset)
        }
    }

    /** Add/remove this asset from a named watchlist, ensuring it's on the watchlist first. */
    fun toggleGroup(name: String) {
        viewModelScope.launch {
            val base = store.snapshot().firstOrNull { it.id == asset.id } ?: asset
            val newGroups = if (base.groups.contains(name)) base.groups - name else base.groups + name
            store.update(base.copy(groups = newGroups))
        }
    }

    /** Create a new named watchlist and add this asset to it. */
    fun createGroupAndAdd(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val existing = settings.watchlistGroups.first()
            if (!existing.contains(clean)) settings.setWatchlistGroups(existing + clean)
            val base = store.snapshot().firstOrNull { it.id == asset.id } ?: asset
            if (!base.groups.contains(clean)) store.update(base.copy(groups = base.groups + clean))
        }
    }

    /**
     * Persist shares + alerts in a SINGLE write. (Two separate writes raced and clobbered each
     * other's field, so shares appeared not to save.) Adds the asset to the watchlist if needed.
     */
    fun saveHoldingsAndAlerts(shares: Double?, avgCost: Double?, alerts: AssetAlerts) {
        viewModelScope.launch {
            val base = store.snapshot().firstOrNull { it.id == asset.id } ?: asset
            store.update(
                base.copy(
                    shares = shares,
                    avgCost = avgCost.takeIf { shares != null }, // cost only meaningful with shares
                    alerts = alerts.takeUnless { it.isEmpty },
                ),
            )
        }
    }
}
