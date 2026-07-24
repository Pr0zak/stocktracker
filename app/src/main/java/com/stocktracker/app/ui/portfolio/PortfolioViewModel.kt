package com.stocktracker.app.ui.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.remote.HoldingSync
import com.stocktracker.app.data.remote.PortfolioReviewResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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

/** State for the on-demand AI portfolio review dialog. */
data class PortfolioReviewUi(
    val open: Boolean = false,
    val loading: Boolean = false,
    val result: PortfolioReviewResponse? = null,
    val error: String? = null,
)

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
    /** The same starting value invested in the S&P 500, aligned to the portfolio's days (chart overlay). */
    val benchmarkChart: List<PricePoint> = emptyList(),
    /** Worst peak-to-trough dip of the reconstructed value series, as a (<= 0) percent. */
    val maxDrawdownPct: Double? = null,
    /** Portfolio total return minus the S&P's over the window, in percentage points. */
    val vsSpyPct: Double? = null,
    val range: ChartRange = ChartRange.YEAR,
    val loading: Boolean = true,
    val loadingChart: Boolean = true,
    val hasHoldings: Boolean = true,
    val review: PortfolioReviewUi = PortfolioReviewUi(),
)

/** Ranges offered for the portfolio value graph (daily data). */
val PORTFOLIO_RANGES = listOf(ChartRange.MONTH, ChartRange.QUARTER, ChartRange.YEAR, ChartRange.THREE_YEAR, ChartRange.ALL)

class PortfolioViewModel : ViewModel() {

    private val repo = ServiceLocator.repository
    private val store = ServiceLocator.watchlistStore
    private val signalsApi = SignalsApiService()
    private val settings = ServiceLocator.settingsStore

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

    /** Open the AI portfolio-review dialog and load it (a cached result is reused until refreshed). */
    fun openReview() {
        _state.update { it.copy(review = it.review.copy(open = true)) }
        loadReview(force = false)
    }

    fun dismissReview() {
        _state.update { it.copy(review = it.review.copy(open = false)) }
    }

    /** One structured LLM review over the whole book. Gated on a configured Signals URL + the AI switch. */
    fun loadReview(force: Boolean) {
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            val on = settings.aiAnalystEnabled.first()
            val held = _state.value.holdings
            if (base.isBlank() || !on) {
                _state.update { st ->
                    st.copy(review = st.review.copy(loading = false,
                        error = if (base.isBlank()) "Set the Signals service URL in Settings to use this."
                        else "The AI analyst is off — turn it on in Settings."))
                }
                return@launch
            }
            if (held.isEmpty()) {
                _state.update { it.copy(review = it.review.copy(loading = false, error = "No holdings to review.")) }
                return@launch
            }
            if (!force && _state.value.review.result != null) return@launch
            _state.update { it.copy(review = it.review.copy(loading = true, error = null)) }
            val syncs = held.map { h ->
                val avg = h.costBasis?.takeIf { it > 0 && h.shares > 0 }?.div(h.shares) ?: (h.asset.avgCost ?: 0.0)
                val sym = if (h.asset.type == AssetType.CRYPTO) "${h.asset.symbol.uppercase()}-USD"
                          else h.asset.symbol.uppercase()
                HoldingSync(sym, h.shares, avg)
            }
            val res = runCatching { signalsApi.portfolioReview(base, 0.0, syncs) }
            _state.update { st ->
                st.copy(review = st.review.copy(
                    loading = false,
                    result = res.getOrNull() ?: st.review.result,
                    error = res.exceptionOrNull()?.let { it.message ?: "Couldn't load the review." },
                ))
            }
        }
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
        // A holding with ANY history must have STARTED before we value the book — otherwise the earliest
        // days sum only the holdings whose data begins first (e.g. a crypto/ETF with a longer window),
        // producing a spurious low first point and a vertical spike. Holdings with no history at all are
        // excluded from the reconstruction (their live value still counts in the header total).
        val hasData = BooleanArray(perAsset.size) { perAsset[it].second.isNotEmpty() }
        val idx = IntArray(perAsset.size)
        val last = DoubleArray(perAsset.size) { Double.NaN }
        val series = ArrayList<PricePoint>(allDays.size)
        for (day in allDays) {
            var total = 0.0
            var allStarted = true
            var any = false
            perAsset.forEachIndexed { i, (asset, pts) ->
                while (idx[i] < pts.size && pts[idx[i]].epochMs / dayMs <= day) {
                    last[i] = pts[idx[i]].price
                    idx[i]++
                }
                if (!last[i].isNaN()) {
                    total += (asset.shares ?: 0.0) * last[i]
                    any = true
                } else if (hasData[i]) {
                    allStarted = false   // this holding has history but hasn't begun yet on `day`
                }
            }
            if (any && allStarted) series.add(PricePoint(day * dayMs, total))
        }

        // Benchmark overlay + risk/relative stats: "the same starting value in the S&P 500".
        val benchRaw = runCatching { repo.benchmark(range) }.getOrNull().orEmpty().sortedBy { it.epochMs }
        val benchSeries = if (series.size >= 2 && benchRaw.size >= 2) alignBenchmark(series, benchRaw) else emptyList()
        val maxDd = if (series.size >= 2) maxDrawdownPct(series.map { it.price }) else null
        val vsSpy = if (series.size >= 2 && benchSeries.size >= 2 &&
            series.first().price > 0.0 && benchSeries.first().price > 0.0) {
            val portRet = series.last().price / series.first().price - 1.0
            val spyRet = benchSeries.last().price / benchSeries.first().price - 1.0
            (portRet - spyRet) * 100.0
        } else {
            null
        }
        _state.update {
            it.copy(chart = series, benchmarkChart = benchSeries, maxDrawdownPct = maxDd,
                vsSpyPct = vsSpy, loadingChart = false)
        }
    }

    /** Rebase the S&P series to the portfolio's starting value, sampled on the portfolio's calendar
     *  days (forward-filled) — the "same money in the S&P 500" line for a like-for-like overlay. */
    private fun alignBenchmark(port: List<PricePoint>, bench: List<PricePoint>): List<PricePoint> {
        val dayMs = 86_400_000L
        val startValue = port.first().price
        var j = 0
        var lastClose = Double.NaN
        val filled = ArrayList<Double>(port.size)
        for (p in port) {
            val day = p.epochMs / dayMs
            while (j < bench.size && bench[j].epochMs / dayMs <= day) { lastClose = bench[j].price; j++ }
            filled.add(lastClose)
        }
        val base = filled.firstOrNull { !it.isNaN() } ?: return emptyList()
        return port.mapIndexed { i, p ->
            val c = filled[i]
            PricePoint(p.epochMs, if (c.isNaN()) startValue else startValue * c / base)
        }
    }

    /** Worst peak-to-trough decline of an equity curve, as a (<= 0) percent — same as Backtest's. */
    private fun maxDrawdownPct(values: List<Double>): Double {
        var peak = Double.NEGATIVE_INFINITY
        var maxDd = 0.0
        for (v in values) {
            if (v > peak) peak = v
            if (peak > 0.0) {
                val dd = (v - peak) / peak
                if (dd < maxDd) maxDd = dd
            }
        }
        return maxDd * 100.0
    }
}
