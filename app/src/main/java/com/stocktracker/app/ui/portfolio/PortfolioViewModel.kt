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
import com.stocktracker.app.data.remote.RebalanceResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.ideas.formatCashPlain
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

/** State for the on-demand AI rebalance-plan dialog (Theme C). [targetPct] is the max single-position
 *  weight the user wants after rebalancing. */
data class RebalanceUi(
    val open: Boolean = false,
    val loading: Boolean = false,
    val result: RebalanceResponse? = null,
    val error: String? = null,
    val targetPct: Int = 25,
)

/** A cached quote older than this is reported as stale rather than rendered as current. */
const val STALE_QUOTE_MS = 6L * 60 * 60 * 1000   // 6 hours — comfortably inside one trading session

data class PortfolioUiState(
    val totalValue: Double = 0.0,
    val dayChange: Double = 0.0,
    val dayChangePercent: Double = 0.0,
    val totalCost: Double = 0.0,
    val totalGain: Double = 0.0,
    val totalGainPercent: Double = 0.0,
    val hasCostBasis: Boolean = false,
    /** True only when every priced holding has a cost basis. The chart's cost line is the sum over
     *  holdings WITH a cost, while the curve is the value of ALL holdings — so when those two
     *  memberships differ the line sits below the curve for a reason unrelated to performance, and
     *  reads as a gain that isn't there. */
    val allHaveCostBasis: Boolean = false,
    /** Holdings excluded from every total because no live or cached price was available. Non-empty
     *  means the figures above cover only part of the portfolio and must be labelled as such. */
    val unpricedSymbols: List<String> = emptyList(),
    /** Holdings priced from a cache entry older than [STALE_QUOTE_MS] — their day change is not today's. */
    val staleSymbols: List<String> = emptyList(),
    /** Non-USD currencies present alongside USD holdings. The totals sum them one-for-one because
     *  the app has no FX rates, so a non-empty list means the total is NOT a real currency amount. */
    val mixedCurrencies: List<String> = emptyList(),
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
    val rebalance: RebalanceUi = RebalanceUi(),
    /** Free cash the user has to invest — fed to the AI review + rebalance so they distribute it.
     *  Persisted in [investableCash] (shared with the Ideas screen + detail entry plans). */
    val cashText: String = "",
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
    private var lastHoldingsIdent: String? = null

    init {
        // Seed the cash field from the shared investable-cash setting (set on Ideas / entry plans).
        viewModelScope.launch {
            val c = settings.investableCash.first()
            if (c > 0) _state.update { it.copy(cashText = formatCashPlain(c)) }
        }
        viewModelScope.launch {
            // Recompute when the set of holdings (or their share counts) changes.
            store.watchlist
                .map { list -> list.filter { (it.shares ?: 0.0) > 0.0 } }
                .distinctUntilChanged()
                .collect { held ->
                    // A cached review/rebalance is only valid for the book it was computed from.
                    // setCash already drops them when the cash changes; nothing did so when the
                    // HOLDINGS changed, so the dialog could re-serve a plan naming a position the
                    // user had since sold. Key on identity (symbol/shares/cost), not on price, so a
                    // routine quote refresh does not throw the plan away.
                    val ident = held.sortedBy { it.symbol }
                        .joinToString("|") { "${it.symbol}:${it.shares}:${it.avgCost}" }
                    if (ident != lastHoldingsIdent) {
                        lastHoldingsIdent = ident
                        _state.update {
                            it.copy(review = it.review.copy(result = null),
                                    rebalance = it.rebalance.copy(result = null))
                        }
                    }
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

    /** Update the "cash to invest" field and persist the parsed amount (shared with Ideas + entry plans).
     *  The AI review + rebalance read this so their suggestions distribute the available cash. */
    fun setCash(text: String) {
        _state.update {
            it.copy(
                cashText = text,
                // The cached review/rebalance were computed for the old cash — drop them so the next
                // open re-runs with the new amount (no LLM call fires until the user opens a dialog).
                review = it.review.copy(result = null),
                rebalance = it.rebalance.copy(result = null),
            )
        }
        viewModelScope.launch { settings.setInvestableCash(cashValue()) }
    }

    /** The cash-to-invest amount parsed from the field ($/comma-tolerant, never negative). */
    private fun cashValue(): Double =
        _state.value.cashText.replace(",", "").removePrefix("$").trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

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

    /**
     * The payload for the AI endpoints, built from the RAW holdings — not from [PortfolioUiState.holdings],
     * which drops anything the app could not quote.
     *
     * Dropping before sending made the backend compute total_value and every weight over a silent
     * subset, and it reported unpriced=[] because it never heard about the position at all — which
     * defeats the backend's own carry-at-cost handling. The backend prices via a different provider
     * and usually succeeds where the app failed; when it cannot, it now says so honestly.
     */
    private fun syncPayload(): List<HoldingSync> = holdings.map { a ->
        val sym = if (a.type == AssetType.CRYPTO) "${a.symbol.uppercase()}-USD" else a.symbol.uppercase()
        HoldingSync(sym, a.shares ?: 0.0, a.avgCost ?: 0.0)
    }

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
            // A failed refresh keeps the previous result AND sets error, so both are non-null. The
            // early return used to fire before the error reset below, so every later open skipped it:
            // the dialog's `when` puts error ahead of result, so it showed a stale error and hid a
            // perfectly good plan indefinitely. Only retry-on-error escapes that.
            if (!force && _state.value.review.result != null && _state.value.review.error == null) return@launch
            _state.update { it.copy(review = it.review.copy(loading = true, error = null)) }
            val syncs = syncPayload()
            val res = runCatching { signalsApi.portfolioReview(base, cashValue(), syncs, refresh = force) }
            _state.update { st ->
                st.copy(review = st.review.copy(
                    loading = false,
                    result = res.getOrNull() ?: st.review.result,
                    error = res.exceptionOrNull()?.let { it.message ?: "Couldn't load the review." },
                ))
            }
        }
    }

    /** Open the AI rebalance-plan dialog and load it (a cached plan is reused until refreshed). */
    fun openRebalance() {
        _state.update { it.copy(rebalance = it.rebalance.copy(open = true)) }
        loadRebalance(force = false)
    }

    fun dismissRebalance() {
        _state.update { it.copy(rebalance = it.rebalance.copy(open = false)) }
    }

    /** Change the target max single-position weight and re-run the plan. */
    fun setRebalanceTarget(pct: Int) {
        if (pct == _state.value.rebalance.targetPct) return
        _state.update { it.copy(rebalance = it.rebalance.copy(targetPct = pct)) }
        loadRebalance(force = true)
    }

    /** One structured LLM call producing concrete sized moves. Gated on a Signals URL + the AI switch. */
    fun loadRebalance(force: Boolean) {
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            val on = settings.aiAnalystEnabled.first()
            val held = _state.value.holdings
            if (base.isBlank() || !on) {
                _state.update { st ->
                    st.copy(rebalance = st.rebalance.copy(loading = false,
                        error = if (base.isBlank()) "Set the Signals service URL in Settings to use this."
                        else "The AI analyst is off — turn it on in Settings."))
                }
                return@launch
            }
            if (held.isEmpty()) {
                _state.update { it.copy(rebalance = it.rebalance.copy(loading = false, error = "No holdings to rebalance.")) }
                return@launch
            }
            // See loadReview: a stale error must not both survive the reopen and block the retry.
            if (!force && _state.value.rebalance.result != null && _state.value.rebalance.error == null) return@launch
            _state.update { it.copy(rebalance = it.rebalance.copy(loading = true, error = null)) }
            val syncs = syncPayload()
            val target = _state.value.rebalance.targetPct
            val res = runCatching { signalsApi.rebalance(base, cashValue(), target, syncs, refresh = force) }
            _state.update { st ->
                st.copy(rebalance = st.rebalance.copy(
                    loading = false,
                    result = res.getOrNull() ?: st.rebalance.result,
                    error = res.exceptionOrNull()?.let { it.message ?: "Couldn't load the rebalance plan." },
                ))
            }
        }
    }

    /** Bumped on every range switch; a load whose generation is stale must not publish. */
    private var chartGeneration = 0

    fun selectRange(range: ChartRange) {
        _state.update { it.copy(range = range, loadingChart = true) }
        // Tapping 1M then 1Y fired two loads with no supersede guard, so whichever finished LAST
        // won — and a slow earlier range routinely overwrote the newer one, leaving the chart
        // showing a window the selector says you are not looking at.
        val gen = ++chartGeneration
        if (holdings.isNotEmpty()) viewModelScope.launch { loadChart(holdings, range, gen) }
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
        // A holding whose quote failed AND has no cached price used to be dropped from `rows`
        // entirely — so `totalValue` became a sum over an arbitrary SUBSET of the portfolio and was
        // still presented as "the" total. A number that is quietly missing a position is worse than
        // an obviously incomplete one, so track them and say so.
        val unpriced = quotes.filter { (_, q) -> q == null }.map { (a, _) -> a.symbol }
        // The app holds no FX rates, so a GBP or EUR holding was summed into the USD total
        // one-for-one — a silently wrong number, not a rounding issue. Converting properly needs a
        // rate source; until then, name the mismatch rather than present the sum as if it were
        // meaningful. Per-row prices already render in their own currency.
        val currencies = quotes.mapNotNull { (_, q) -> q?.currency?.uppercase()?.takeIf { it.isNotBlank() } }
            .distinct()
        val foreign = currencies.filterNot { it == "USD" }
        val rows = quotes.mapNotNull { (asset, q) ->
            if (q == null) null else {
                val shares = asset.shares ?: 0.0
                val costBasis = asset.avgCost?.let { it * shares }
                Holding(asset, shares, q.price, shares * q.price, shares * q.change, costBasis)
            }
        }.sortedByDescending { it.value }

        // Quotes served from PriceCache carry the timestamp of their ORIGINAL fetch, which nothing
        // read — so a day-change from last Thursday rendered under a "Today" label. Anything older
        // than this is reported as stale rather than passed off as current.
        val now = System.currentTimeMillis()
        val staleSymbols = quotes.mapNotNull { (a, q) ->
            a.symbol.takeIf { q != null && q.asOfEpochMs > 0L && now - q.asOfEpochMs > STALE_QUOTE_MS }
        }

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
                // The cost line may only be drawn when EVERY charted holding has a cost — see below.
                allHaveCostBasis = rows.isNotEmpty() && withCost.size == rows.size,
                unpricedSymbols = unpriced, staleSymbols = staleSymbols,
                mixedCurrencies = if (currencies.size > 1) foreign else emptyList(),
                loading = false,
            )
        }
    }

    /**
     * Reconstruct the portfolio value over time = Σ (current shares × that asset's daily price),
     * summed per calendar day with each asset's last-known price forward-filled. Approximation:
     * assumes today's share counts across the whole window.
     */
    private suspend fun loadChart(held: List<Asset>, range: ChartRange, gen: Int = chartGeneration) {
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
        if (gen != chartGeneration) return   // a newer range was selected while this was loading
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
