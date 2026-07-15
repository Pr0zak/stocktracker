package com.stocktracker.app.ui.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Holding(
    val asset: Asset,
    val shares: Double,
    val price: Double,
    val value: Double,
    val dayChange: Double,
    val costBasis: Double? = null, // shares × avg cost, when the user entered a cost
) {
    val gain: Double? get() = costBasis?.let { value - it }
    val gainPercent: Double? get() = costBasis?.takeIf { it != 0.0 }?.let { (value - it) / it * 100.0 }
}

data class PortfolioUiState(
    val totalValue: Double = 0.0,
    val dayChange: Double = 0.0,
    val dayChangePercent: Double = 0.0,
    val totalCost: Double = 0.0,
    val totalGain: Double = 0.0,
    val totalGainPercent: Double = 0.0,
    val hasCostBasis: Boolean = false,
    val holdings: List<Holding> = emptyList(),
    val chart: List<PricePoint> = emptyList(),
    val range: ChartRange = ChartRange.YEAR,
    val loading: Boolean = true,
    val loadingChart: Boolean = true,
    val hasHoldings: Boolean = true,
)

/** Ranges offered for the portfolio value graph (daily data). */
val PORTFOLIO_RANGES = listOf(ChartRange.MONTH, ChartRange.QUARTER, ChartRange.YEAR, ChartRange.THREE_YEAR, ChartRange.ALL)

class PortfolioViewModel : ViewModel() {

    private val repo = ServiceLocator.repository
    private val store = ServiceLocator.watchlistStore

    private val _state = MutableStateFlow(PortfolioUiState())
    val state = _state.asStateFlow()

    private var holdings: List<Asset> = emptyList()

    init {
        viewModelScope.launch {
            // Recompute when the set of holdings (or their share counts) changes.
            store.watchlist
                .map { list -> list.filter { (it.shares ?: 0.0) > 0.0 } }
                .distinctUntilChanged()
                .collect { held ->
                    holdings = held
                    if (held.isEmpty()) {
                        _state.update {
                            it.copy(hasHoldings = false, loading = false, loadingChart = false,
                                totalValue = 0.0, holdings = emptyList(), chart = emptyList())
                        }
                    } else {
                        _state.update { it.copy(hasHoldings = true) }
                        loadCurrent(held)
                        loadChart(held, _state.value.range)
                    }
                }
        }
    }

    fun refresh() {
        if (holdings.isNotEmpty()) viewModelScope.launch { loadCurrent(holdings) }
    }

    fun selectRange(range: ChartRange) {
        _state.update { it.copy(range = range, loadingChart = true) }
        if (holdings.isNotEmpty()) viewModelScope.launch { loadChart(holdings, range) }
    }

    private suspend fun loadCurrent(held: List<Asset>) {
        _state.update { it.copy(loading = true) }
        val quotes = coroutineScope {
            held.map { asset ->
                async {
                    val q = runCatching { repo.quote(asset) }.getOrNull()
                        ?: ServiceLocator.priceCache.getQuote(asset.id)
                    asset to q
                }
            }.awaitAll()
        }
        val rows = quotes.mapNotNull { (asset, q) ->
            if (q == null) null else {
                val shares = asset.shares ?: 0.0
                val costBasis = asset.avgCost?.let { it * shares }
                Holding(asset, shares, q.price, shares * q.price, shares * q.change, costBasis)
            }
        }.sortedByDescending { it.value }

        val total = rows.sumOf { it.value }
        val dayChange = rows.sumOf { it.dayChange }
        val prev = total - dayChange
        val pct = if (prev != 0.0) dayChange / prev * 100.0 else 0.0

        // Total return counts only holdings the user gave a cost for.
        val withCost = rows.filter { it.costBasis != null }
        val totalCost = withCost.sumOf { it.costBasis ?: 0.0 }
        val totalGain = withCost.sumOf { it.gain ?: 0.0 }
        val gainPct = if (totalCost != 0.0) totalGain / totalCost * 100.0 else 0.0

        _state.update {
            it.copy(
                holdings = rows, totalValue = total, dayChange = dayChange, dayChangePercent = pct,
                totalCost = totalCost, totalGain = totalGain, totalGainPercent = gainPct,
                hasCostBasis = withCost.isNotEmpty(),
                loading = false,
            )
        }
    }

    /**
     * Reconstruct the portfolio value over time = Σ (current shares × that asset's daily price),
     * summed per calendar day with each asset's last-known price forward-filled. Approximation:
     * assumes today's share counts across the whole window.
     */
    private suspend fun loadChart(held: List<Asset>, range: ChartRange) {
        val perAsset = coroutineScope {
            held.map { asset ->
                async { asset to runCatching { repo.history(asset, range) }.getOrDefault(emptyList()).sortedBy { it.epochMs } }
            }.awaitAll()
        }
        val dayMs = 86_400_000L
        val allDays = perAsset.flatMap { (_, pts) -> pts.map { it.epochMs / dayMs } }.toSortedSet()
        val idx = IntArray(perAsset.size)
        val last = DoubleArray(perAsset.size) { Double.NaN }
        val series = ArrayList<PricePoint>(allDays.size)
        for (day in allDays) {
            var total = 0.0
            var any = false
            perAsset.forEachIndexed { i, (asset, pts) ->
                while (idx[i] < pts.size && pts[idx[i]].epochMs / dayMs <= day) {
                    last[i] = pts[idx[i]].price
                    idx[i]++
                }
                if (!last[i].isNaN()) {
                    total += (asset.shares ?: 0.0) * last[i]
                    any = true
                }
            }
            if (any) series.add(PricePoint(day * dayMs, total))
        }
        _state.update { it.copy(chart = series, loadingChart = false) }
    }
}
