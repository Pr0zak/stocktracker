package com.stocktracker.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetAlerts
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.remote.AiUsage
import com.stocktracker.app.data.remote.AiVerdict
import com.stocktracker.app.data.remote.CoveredCallResponse
import com.stocktracker.app.data.remote.CongressBlock
import com.stocktracker.app.data.remote.CycleResponse
import com.stocktracker.app.data.remote.NewsMovesBlock
import com.stocktracker.app.data.remote.SeasonalityBlock
import com.stocktracker.app.data.remote.EntryPlan
import com.stocktracker.app.data.remote.HttpStatusException
import com.stocktracker.app.data.remote.InsiderResponse
import com.stocktracker.app.data.remote.OptionsResponse
import com.stocktracker.app.data.remote.PutsResponse
import com.stocktracker.app.data.remote.QualityResponse
import com.stocktracker.app.data.remote.ShortPressureResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.data.remote.TouchStudyResponse
import com.stocktracker.app.data.remote.TrendResponse
import com.stocktracker.app.data.remote.analystErrorDetail
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.signals.Backtest
import com.stocktracker.app.signals.BacktestResult
import com.stocktracker.app.signals.SignalEngine
import com.stocktracker.app.signals.SignalResult
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
    /** True when the last fetch *failed transiently* (rate-limit/network) — drives the retry UI. */
    val chartError: Boolean = false,
    /** True when this symbol has no historical data at all (404/empty) — a retry can't help. */
    val chartNoData: Boolean = false,
    /** Where the chart data came from — "yahoo" normally, "webull" for the warrant/OTC fallback. */
    val chartSource: String = "yahoo",
    val inWatchlist: Boolean = false,
    val shares: Double? = null,
    val avgCost: Double? = null,
    val alerts: AssetAlerts = AssetAlerts(),
    val groups: List<String> = emptyList(),
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    /** Tier-1 rule-based buy/sell signal, computed from daily bars (null until loaded / if too new). */
    val signal: SignalResult? = null,
    /** Walk-forward backtest of the rule signal on this symbol — the honesty check (vs buy & hold). */
    val backtest: BacktestResult? = null,
    /** Tier-2 Claude analyst verdict (only when a Signals API URL is configured in Settings). */
    val aiVerdict: AiVerdict? = null,
    val aiModel: String = "",
    val aiUsage: AiUsage? = null,
    val aiCached: Boolean = false,
    val aiEnabled: Boolean = false,
    val aiLoading: Boolean = false,
    val aiError: String? = null,
    /** True when a Signals service URL is configured — gates the free (no-LLM) cards regardless of
     *  the AI kill-switch. The "Play with calls" card is one of these. */
    val signalsConfigured: Boolean = false,
    /** "Play with calls" suggester result (on-demand, no LLM). Null until the user taps Suggest. */
    val options: OptionsResponse? = null,
    val optionsLoading: Boolean = false,
    val optionsError: String? = null,
    /** Deep-dive (Opus) re-fetch of the SAME calls suggestion — costs an LLM call, gated on the AI
     *  kill-switch. Merges the returned analyst paragraph into [options]. */
    val optionsDeepLoading: Boolean = false,
    val optionsDeepError: String? = null,
    /** "Get paid to buy" cash-secured put suggester result (OC-8, on-demand, no LLM). */
    val puts: PutsResponse? = null,
    val putsLoading: Boolean = false,
    val putsError: String? = null,
    /** "Sell covered calls" income suggester result (OC-8, on-demand, no LLM; needs ≥100 shares held). */
    val coveredCall: CoveredCallResponse? = null,
    val coveredCallLoading: Boolean = false,
    val coveredCallError: String? = null,
    /** "What if I put $cash into this?" entry plan (on-demand, from the signals service). */
    val plan: EntryPlan? = null,
    val planLoading: Boolean = false,
    val planError: String? = null,
    /** Short-pressure read (SI/short-volume/FTDs) — free data, auto-fetched for stocks. */
    val shortPressure: ShortPressureResponse? = null,
    /** Insider buying (Form 4 open-market purchases) — free, auto-fetched for stocks (only set when >0). */
    val insider: InsiderResponse? = null,
    /** Congressional / political trades — free, auto-fetched for stocks (only set when any disclosed). */
    val congress: CongressBlock? = null,
    /** Seasonality — typical per-month price action (~10y), free, auto-fetched for stocks. */
    val seasonality: SeasonalityBlock? = null,
    /** "Why it moved" (AIE-4) — notable recent moves correlated with news. On-demand (an LLM call). */
    val newsMoves: NewsMovesBlock? = null,
    val newsMovesNote: String? = null,     // e.g. "not available for crypto"
    val newsMovesLoading: Boolean = false,
    val newsMovesError: String? = null,
    val newsMovesLoaded: Boolean = false,  // true once a fetch has completed (drives the empty state)
    /** Quality tags (ROE/margins/D-E + Buffett/wide-moat/aristocrat flags) — free, auto-fetched for stocks. */
    val quality: QualityResponse? = null,
    /** Halving-cycle + multi-year trend — free data, auto-fetched for crypto. */
    val cycleInfo: CycleResponse? = null,
    /** Below-the-200-week-line trend (SMA, zone, direction, weekly RSI) — free, auto-fetched for stocks. */
    val stockTrend: TrendResponse? = null,
    /** Historical touch / forward-return study — free, auto-fetched for stocks below-line-eligible. */
    val touchStudy: TouchStudyResponse? = null,
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

    private val signalsApi = SignalsApiService()
    private var aiJob: Job? = null

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
            // 52-week first: for crypto this warms the 1-year daily history the signal reuses (so the
            // signal doesn't fire a duplicate CoinGecko call); for stocks it's a cheap meta lookup.
            val hl = runCatching { repo.fiftyTwoWeek(asset) }.getOrNull()
            if (hl != null) _state.update { it.copy(fiftyTwoWeekHigh = hl.first, fiftyTwoWeekLow = hl.second) }
            loadSignal()
        }
        // Deliberately NO auto-fetch of the AI verdict — analysis is user-triggered from the card
        // (one tap = one model call), so idly browsing symbols burns no tokens. Just compute
        // whether the card should show at all.
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            val on = settings.aiAnalystEnabled.first()
            // signalsConfigured gates the free/no-LLM cards (calls suggester) on just the URL; aiEnabled
            // additionally requires the AI kill-switch for the token-spending Claude cards.
            _state.update { it.copy(aiEnabled = base.isNotBlank() && on, signalsConfigured = base.isNotBlank()) }
        }
        // Short-pressure data IS auto-fetched for stocks: it's free (FINRA/SEC data, no model call)
        // and gated only on the service URL, not the AI switch.
        if (asset.type == AssetType.STOCK) {
            viewModelScope.launch {
                val base = settings.signalsApiUrl.first()
                if (base.isBlank()) return@launch
                val sp = runCatching { signalsApi.shortPressure(base, asset.symbol) }.getOrNull()
                if (sp != null) {
                    _state.update { it.copy(shortPressure = sp) }
                    applyDtcTilt(sp.daysToCover) // fold high days-to-cover into the rule score
                }
                // Insider buying — the bullish informed-money mirror; only surface when buys exist.
                val ins = runCatching { signalsApi.insider(base, asset.symbol) }.getOrNull()
                if (ins != null && ins.buyCount12m > 0) _state.update { it.copy(insider = ins) }
                // Congressional trades — public-official smart money; lagging/weak, shown as context.
                val cg = runCatching { signalsApi.congress(base, asset.symbol) }.getOrNull()
                if (cg != null && cg.tradeCount > 0) _state.update { it.copy(congress = cg) }
                // Seasonality — typical per-month price action (weak tilt).
                val sea = runCatching { signalsApi.seasonality(base, asset.symbol) }.getOrNull()
                if (sea?.currentMonth != null) _state.update { it.copy(seasonality = sea) }
                // Business-quality descriptors — stance-neutral context; show when there's anything to show.
                val q = runCatching { signalsApi.quality(base, asset.symbol) }.getOrNull()
                if (q != null && (q.hasAnyFlag || q.hasMetrics)) _state.update { it.copy(quality = q) }
            }
            // Below-the-200-week-line context (the equity mirror of crypto's cycle card) + the touch
            // study — both free, no LLM. 404s for names with under ~4 years of weekly history → stay null.
            viewModelScope.launch {
                val base = settings.signalsApiUrl.first()
                if (base.isBlank()) return@launch
                val tr = runCatching { signalsApi.trend(base, asset.symbol) }.getOrNull()
                if (tr != null) _state.update { it.copy(stockTrend = tr) }
                val ts = runCatching { signalsApi.touchStudy(base, asset.symbol) }.getOrNull()
                if (ts != null) _state.update { it.copy(touchStudy = ts) }
            }
        }
        // Crypto gets the halving-cycle / long-term-trend context instead (also free).
        if (asset.type == AssetType.CRYPTO) {
            viewModelScope.launch {
                val base = settings.signalsApiUrl.first()
                if (base.isBlank()) return@launch
                val ci = runCatching { signalsApi.cycleInfo(base, asset.symbol) }.getOrNull()
                if (ci != null) _state.update { it.copy(cycleInfo = ci) }
            }
        }
    }

    /** Fetch the Tier-2 Claude analyst verdict, if a Signals API URL is configured in Settings. */
    fun requestAiVerdict(deep: Boolean, refresh: Boolean = false) {
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            val on = settings.aiAnalystEnabled.first() // master switch — off = no Claude calls/cost
            _state.update { it.copy(aiEnabled = base.isNotBlank() && on) }
            if (base.isBlank() || !on) return@launch
            _state.update { it.copy(aiLoading = true, aiError = null) }
            val s = _state.value // include the current holding so the verdict is add/hold/trim-aware
            val res = runCatching {
                signalsApi.verdict(
                    base, asset.symbol, crypto = asset.type == AssetType.CRYPTO, deep = deep,
                    shares = s.shares, avgCost = s.avgCost,
                    ruleScore = s.signal?.score, // analyst reconciles with the on-device read
                    refresh = refresh,
                )
            }
            ensureActive() // runCatching swallows cancellation; don't apply a superseded result
            val resp = res.getOrNull()
            _state.update {
                it.copy(
                    aiVerdict = resp?.verdict ?: it.aiVerdict, // keep the prior verdict on a failed refresh
                    aiModel = resp?.model ?: it.aiModel,
                    aiUsage = resp?.usage ?: it.aiUsage,
                    aiCached = resp?.cached ?: it.aiCached,
                    aiLoading = false,
                    aiError = if (resp == null) {
                        analystErrorDetail(res.exceptionOrNull()) ?: "Couldn't reach the analyst service"
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * Refresh EVERY AI analysis section for this ticker in one tap (the detail top-bar refresh button):
     * the headline verdict always re-runs; the opt-in cards (news→move, entry plan) re-run only if the
     * user has already generated them. Each call passes refresh=true to bypass the server-side cache, so
     * the analyst genuinely re-reads. The button's spinner is driven by the section loading flags.
     */
    fun refreshAllAi() {
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            val on = settings.aiAnalystEnabled.first()
            if (base.isBlank() || !on) {
                _state.update {
                    it.copy(
                        aiEnabled = base.isNotBlank() && on,
                        aiError = if (base.isBlank()) "Set the Signals service URL in Settings."
                        else "Turn on the AI analyst in Settings.",
                    )
                }
                return@launch
            }
            requestAiVerdict(deep = false, refresh = true)
            if (_state.value.newsMovesLoaded) requestNewsMoves(refresh = true)
            val cash = settings.investableCash.first()
            if (_state.value.plan != null && cash > 0) requestPlan(cash, refresh = true)
        }
    }

    private var newsMovesJob: Job? = null

    /** AIE-4 — "why it moved": correlate the stock's notable recent moves with dated headlines. On-demand
     *  (it's an LLM call), gated on a Signals URL + the AI switch, same as the verdict. */
    fun requestNewsMoves(deep: Boolean = false, refresh: Boolean = false) {
        newsMovesJob?.cancel()
        newsMovesJob = viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            val on = settings.aiAnalystEnabled.first()
            if (base.isBlank() || !on) {
                _state.update {
                    it.copy(newsMovesError = if (base.isBlank())
                        "Set the Signals service URL in Settings." else "Turn on the AI analyst in Settings.")
                }
                return@launch
            }
            _state.update { it.copy(newsMovesLoading = true, newsMovesError = null) }
            val res = runCatching { signalsApi.newsMoves(base, asset.symbol, refresh = refresh) }
            ensureActive()
            val resp = res.getOrNull()
            _state.update {
                it.copy(
                    newsMoves = resp?.newsMoves ?: it.newsMoves,
                    newsMovesNote = resp?.note,
                    newsMovesLoading = false,
                    newsMovesLoaded = true,
                    newsMovesError = if (resp == null)
                        (analystErrorDetail(res.exceptionOrNull()) ?: "Couldn't reach the analyst service")
                    else null,
                )
            }
        }
    }

    private var planJob: Job? = null

    /** "What if I deployed [cash] into this symbol?" — fetch an entry plan from the signals service.
     *  Persists the cash amount so the Ideas screen and future plans reuse it. */
    fun requestPlan(cash: Double, deep: Boolean = false, refresh: Boolean = false) {
        if (cash <= 0) {
            _state.update { it.copy(planError = "Enter a cash amount first") }
            return
        }
        planJob?.cancel()
        planJob = viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) return@launch
            settings.setInvestableCash(cash)
            _state.update { it.copy(planLoading = true, planError = null) }
            val s = _state.value
            val res = runCatching {
                signalsApi.planEntry(
                    base, asset.symbol, crypto = asset.type == AssetType.CRYPTO, cash = cash,
                    deep = deep, shares = s.shares, avgCost = s.avgCost, refresh = refresh,
                )
            }
            ensureActive() // runCatching swallows cancellation; don't apply a superseded result
            val resp = res.getOrNull()
            _state.update {
                it.copy(
                    plan = resp?.plan ?: it.plan, // keep the prior plan on a failed refresh
                    planLoading = false,
                    planError = if (resp == null) {
                        analystErrorDetail(res.exceptionOrNull()) ?: "Couldn't reach the analyst service"
                    } else {
                        null
                    },
                )
            }
        }
    }

    private var optionsJob: Job? = null
    private var optionsDeepJob: Job? = null
    // Remember the last suggest params so the deep-dive re-fetch targets the SAME contract set.
    private var lastOptionsBudget: Double = 0.0
    private var lastOptionsStyle: String = "balanced"

    /**
     * "Play with calls" (OC-2): fetch a suggested long-call contract for this stock/ETF. [budget] is
     * the MAX LOSS the user is OK with; [style] is safer | balanced | cheaper. No LLM — works even
     * with the AI kill-switch off. Lazy: only runs when the user taps Suggest. Crypto / no-chain
     * symbols come back HTTP 400 → surface the server's detail message.
     */
    fun requestOptions(budget: Double, style: String) {
        if (budget <= 0) {
            _state.update { it.copy(optionsError = "Enter a risk budget first") }
            return
        }
        lastOptionsBudget = budget
        lastOptionsStyle = style
        optionsDeepJob?.cancel() // a fresh scan supersedes any in-flight deep dive
        optionsJob?.cancel()
        optionsJob = viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) return@launch
            _state.update {
                it.copy(optionsLoading = true, optionsError = null, optionsDeepLoading = false, optionsDeepError = null)
            }
            val res = runCatching { signalsApi.options(base, asset.symbol, budget = budget, style = style) }
            ensureActive() // runCatching swallows cancellation; don't apply a superseded result
            val resp = res.getOrNull()
            _state.update {
                it.copy(
                    options = resp ?: it.options, // keep the prior suggestion on a failed refresh
                    optionsLoading = false,
                    optionsError = if (resp == null) {
                        analystErrorDetail(res.exceptionOrNull()) ?: "No options chain for this symbol"
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * Deep dive (OC-6): re-fetch the SAME calls suggestion (symbol/budget/style) with deep=true so the
     * backend attaches an Opus-authored explanation, then merge just that [analyst][OptionsResponse.analyst]
     * paragraph into the displayed suggestion. A real LLM call, so it respects the AI kill-switch — a
     * no-op when the switch is off. Only meaningful after [requestOptions] has produced a suggestion.
     */
    fun requestOptionsDeep() {
        val budget = lastOptionsBudget
        val style = lastOptionsStyle
        if (budget <= 0) return
        optionsDeepJob?.cancel()
        optionsDeepJob = viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            val on = settings.aiAnalystEnabled.first() // real Opus call — honor the kill-switch
            if (base.isBlank() || !on) return@launch
            _state.update { it.copy(optionsDeepLoading = true, optionsDeepError = null) }
            val res = runCatching { signalsApi.options(base, asset.symbol, budget = budget, style = style, deep = true) }
            ensureActive() // runCatching swallows cancellation; don't apply a superseded result
            val resp = res.getOrNull()
            val analyst = resp?.analyst
            _state.update {
                it.copy(
                    // Merge only the analyst paragraph so the shown contract numbers don't visibly shift.
                    options = if (!analyst.isNullOrBlank()) (it.options?.copy(analyst = analyst) ?: resp) else it.options,
                    optionsDeepLoading = false,
                    optionsDeepError = if (analyst.isNullOrBlank()) {
                        analystErrorDetail(res.exceptionOrNull()) ?: "Couldn't get the deep dive"
                    } else {
                        null
                    },
                )
            }
        }
    }

    private var putsJob: Job? = null

    /**
     * "Get paid to buy" cash-secured put suggester (OC-8): the acquire-shares-cheaply half of the
     * wheel. [cash] is the collateral the user will set aside; [style] is aggressive | balanced |
     * conservative. No LLM — works with the AI kill-switch off. Lazy: only runs on tap. Crypto /
     * no-chain symbols come back HTTP 400 → surface the server's detail message.
     */
    fun requestPuts(cash: Double, style: String) {
        if (cash <= 0) {
            _state.update { it.copy(putsError = "Enter the cash to set aside first") }
            return
        }
        putsJob?.cancel()
        putsJob = viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) return@launch
            _state.update { it.copy(putsLoading = true, putsError = null) }
            val res = runCatching { signalsApi.puts(base, asset.symbol, cash = cash, style = style) }
            ensureActive() // runCatching swallows cancellation; don't apply a superseded result
            val resp = res.getOrNull()
            _state.update {
                it.copy(
                    puts = resp ?: it.puts, // keep the prior suggestion on a failed refresh
                    putsLoading = false,
                    putsError = if (resp == null) {
                        analystErrorDetail(res.exceptionOrNull()) ?: "No options chain for this symbol"
                    } else {
                        null
                    },
                )
            }
        }
    }

    private var coveredCallJob: Job? = null

    /**
     * "Sell covered calls" income suggester (OC-8): the income-on-shares-you-hold half of the wheel.
     * [shares] comes from the user's holdings (gated on ≥100 in the UI); [target] is an optional
     * target sell price (null → the server picks ~0.30 delta). No LLM. Lazy: only runs on tap. The
     * server returns HTTP 400 for crypto / no chain / under 100 shares → surface the detail message.
     */
    fun requestCoveredCall(shares: Int, target: Double? = null) {
        if (shares < 100) {
            _state.update { it.copy(coveredCallError = "You need at least 100 shares to sell a covered call") }
            return
        }
        coveredCallJob?.cancel()
        coveredCallJob = viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) return@launch
            _state.update { it.copy(coveredCallLoading = true, coveredCallError = null) }
            val res = runCatching { signalsApi.coveredCall(base, asset.symbol, shares = shares, target = target) }
            ensureActive() // runCatching swallows cancellation; don't apply a superseded result
            val resp = res.getOrNull()
            _state.update {
                it.copy(
                    coveredCall = resp ?: it.coveredCall, // keep the prior suggestion on a failed refresh
                    coveredCallLoading = false,
                    coveredCallError = if (resp == null) {
                        analystErrorDetail(res.exceptionOrNull()) ?: "Couldn't build a covered call for this symbol"
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * Compute the Tier-1 signal from DAILY bars (a fixed 1-year window), independent of the chart
     * range the user is viewing — swing signals only make sense on daily data. Relative strength
     * uses the S&P benchmark for stocks only (crypto trades a different calendar). Best-effort.
     */
    // Cached rule-signal inputs so short-pressure can re-tilt the signal without re-fetching.
    private var signalInputs: Triple<List<PricePoint>, List<PricePoint>?, Double?>? = null

    private suspend fun loadSignal() {
        var daily = runCatching { repo.history(asset, ChartRange.YEAR) }.getOrDefault(emptyList())
        // Warrant/OTC symbols Yahoo can't serve → pull daily bars from the signals service's
        // Webull-backed /history so the rule signal + backtest still compute.
        if (daily.size < 30 && asset.type == AssetType.STOCK) {
            val base = settings.signalsApiUrl.first()
            if (base.isNotBlank()) {
                val fb = runCatching { signalsApi.history(base, asset.symbol) }.getOrNull()?.bars.orEmpty()
                if (fb.size >= 30) daily = fb.map { PricePoint(it.t, it.c, volume = it.v) }
            }
        }
        if (daily.size < 30) return
        // Benchmark and VIX are equity-market context — scope both to stocks (crypto trades a
        // different calendar and isn't governed by the S&P's volatility index).
        val bench = if (asset.type == AssetType.STOCK) {
            runCatching { repo.benchmark(ChartRange.YEAR) }.getOrNull()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val vix = if (asset.type == AssetType.STOCK) runCatching { repo.vix() }.getOrNull()?.value else null
        signalInputs = Triple(daily, bench, vix)
        val dtc = _state.value.shortPressure?.daysToCover // may already be loaded
        val sig = SignalEngine().evaluate(daily, benchmark = bench, vix = vix, daysToCover = dtc)
        // Walk-forward backtest is pure TA (no DTC) — the honesty check on the mechanical signal.
        val bt = runCatching { Backtest.run(daily, benchmark = bench) }.getOrNull()
        _state.update { it.copy(signal = sig ?: it.signal, backtest = bt ?: it.backtest) }
    }

    /** Re-run the rule signal with a days-to-cover tilt once short-pressure has loaded. */
    private fun applyDtcTilt(dtc: Double?) {
        val (daily, bench, vix) = signalInputs ?: return
        if (dtc == null || dtc < SignalEngine().weights.highDaysToCover) return
        val sig = SignalEngine().evaluate(daily, benchmark = bench, vix = vix, daysToCover = dtc)
        if (sig != null) _state.update { it.copy(signal = sig) }
    }

    private fun loadQuote() {
        viewModelScope.launch {
            val q = runCatching { repo.quote(asset) }.getOrNull()
                ?: ServiceLocator.priceCache.getQuote(asset.id)
            _state.update { it.copy(quote = q) }
        }
    }

    fun selectRange(range: ChartRange) {
        _state.update { it.copy(range = range, loadingChart = true, chartError = false, chartNoData = false) }
        chartJob?.cancel() // supersede any in-flight load for a previous range
        chartJob = viewModelScope.launch {
            val result = runCatching { repo.history(asset, range, includeExtended = showExtended) }
            // runCatching also swallows CancellationException, so a cancelled (superseded) load would
            // otherwise fall through and write stale state; ensureActive() bails it out first.
            ensureActive()
            var data = result.getOrDefault(emptyList())
            var source = "yahoo"
            // A 404 (symbol has no chart on Yahoo, e.g. a warrant) or an empty success is permanent
            // FOR YAHOO — but the signals service can still fall back to Webull for warrants/OTC.
            val permanent = (result.exceptionOrNull() as? HttpStatusException)?.code in setOf(400, 404, 410)
            if (data.isEmpty() && asset.type == AssetType.STOCK) {
                val base = settings.signalsApiUrl.first()
                if (base.isNotBlank()) {
                    val fb = runCatching { signalsApi.history(base, asset.symbol) }.getOrNull()
                    val bars = fb?.bars.orEmpty()
                    if (bars.isNotEmpty()) {
                        val cutoff = System.currentTimeMillis() - rangeDays(range) * 86_400_000L
                        val windowed = bars.filter { it.t >= cutoff }.ifEmpty { bars.takeLast(2) }
                        data = windowed.map { PricePoint(it.t, it.c, volume = it.v) }
                        source = fb?.source ?: "webull"
                    }
                }
            }
            _state.update {
                it.copy(
                    chart = data,
                    chartSource = source,
                    loadingChart = false,
                    chartError = result.isFailure && !permanent && data.isEmpty(),
                    chartNoData = data.isEmpty() && (permanent || result.isSuccess),
                )
            }
        }
    }

    /** Approx. calendar-day window for a range — used to slice the daily fallback bars. */
    private fun rangeDays(r: ChartRange): Long = when (r) {
        ChartRange.DAY -> 2
        ChartRange.WEEK -> 8
        ChartRange.MONTH -> 32
        ChartRange.QUARTER -> 95
        ChartRange.YEAR -> 370
        ChartRange.THREE_YEAR -> 1100
        ChartRange.ALL -> 100_000
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
