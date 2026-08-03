package com.stocktracker.app.ui.sandbox

import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.remote.SandboxSettings
import com.stocktracker.app.data.remote.SandboxSettingsPatch
import com.stocktracker.app.data.remote.SandboxState
import com.stocktracker.app.data.remote.SandboxTrade
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

data class SandboxUiState(
    val configured: Boolean = true,   // a Signals URL is set
    val loading: Boolean = true,
    val state: SandboxState? = null,
    val nav: List<PricePoint> = emptyList(),
    /** S&P shadow value aligned index-for-index to [nav] (null where a point lacks a benchmark). */
    val benchmarkValues: List<Double?> = emptyList(),
    /** Least-squares fit of the equity curve — the underlying trajectory, aligned to [nav]. */
    val trendValues: List<Double?> = emptyList(),
    /** Out/under-performance vs the benchmark in percentage points, aligned to [nav]. >0 = beating it. */
    val vsBenchmarkSeries: List<Double?> = emptyList(),
    /** Trend slope expressed as % of starting equity per 30 days (null until there are 3+ points). */
    val trendPctPerMonth: Double? = null,
    val trades: List<SandboxTrade> = emptyList(),
    val ticking: Boolean = false,
    // The AI's own scorecard (GET /memory/stats) — null until enough decisions have been graded.
    val memory: com.stocktracker.app.data.remote.MemoryStats? = null,
    /** The macro backdrop the trader reasons against (GET /macro/catalysts). Null = couldn't load,
     *  which the card renders as "couldn't load", never as a calm market. */
    val macro: com.stocktracker.app.data.remote.MacroState? = null,
    val message: String? = null,      // transient toast-style feedback
    val error: String? = null,
)

/** Drives the Sandbox tab — the read-only view of the server-side autonomous paper trader plus the
 *  hands-on controls (fund, settings, run-a-tick). All state lives on the backend; this just mirrors it. */
class SandboxViewModel(private val app: android.app.Application) : androidx.lifecycle.AndroidViewModel(app) {

    private val api = SignalsApiService()
    private val settings = ServiceLocator.settingsStore

    private val _state = MutableStateFlow(SandboxUiState())
    val state = _state.asStateFlow()

    init { refresh() }

    /** Bumped per refresh; a slower earlier response must not overwrite a newer one — that is how a
     *  completed reset got the pre-reset book restored underneath it. */
    private var refreshGeneration = 0

    fun refresh() {
        val gen = ++refreshGeneration
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) {
                _state.update { it.copy(configured = false, loading = false) }
                return@launch
            }
            _state.update { it.copy(configured = true, loading = it.state == null, error = null) }
            val st = api.sandboxState(base)
            val navRows = api.sandboxNav(base, days = 180)
            val trades = api.sandboxTrades(base, limit = 120)
            val mem = api.memoryStats(base)
            val mac = api.macroCatalysts(base)
            if (gen != refreshGeneration) return@launch   // superseded by a newer refresh
            _state.update {
                it.copy(
                    loading = false,
                    state = st ?: it.state,
                    // A null list means the CALL failed — keep what we had rather than rendering an
                    // empty curve and "No trades yet." beside a confident (stale) equity figure.
                    nav = navRows?.map { p -> PricePoint((p.ts * 1000).toLong(), p.equity) } ?: it.nav,
                    benchmarkValues = navRows?.map { p -> p.benchmarkValue } ?: it.benchmarkValues,
                    trendValues = navRows?.let { r -> trendOnEquityAxis(r) } ?: it.trendValues,
                    vsBenchmarkSeries = navRows?.let { r -> relativePerformance(r) } ?: it.vsBenchmarkSeries,
                    trendPctPerMonth = navRows?.let { r -> trendPerMonth(r) } ?: it.trendPctPerMonth,
                    trades = trades ?: it.trades,
                    memory = mem ?: it.memory,
                    macro = mac ?: it.macro,
                    error = when {
                        st == null -> "Couldn't reach the sandbox service."
                        navRows == null || trades == null -> "Some sandbox data couldn't be loaded — showing the last known values."
                        else -> null
                    },
                )
            }
        }
    }

    /** Guards the money-shaped actions. Fund/withdraw and reset are not idempotent — two taps meant
     *  two deposits, with no undo — and the dialog/button stays live while the request is in flight. */
    private val actionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun fund(amount: Double) {
        if (amount == 0.0) return
        if (!actionInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            _state.update { it.copy(message = "Updating funds…") }
            val failure = try {
                api.sandboxFund(base, amount)
            } finally {
                actionInFlight.set(false)
            }
            // The server names the real numbers ("withdrawal exceeds cash on hand ($X)") — that is
            // exactly what the user needs and it was being thrown away.
            _state.update { it.copy(message = failure ?: "Funds updated") }
            refresh()
        }
    }

    fun patchSettings(patch: SandboxSettingsPatch, note: String? = null) {
        viewModelScope.launch { applyPatch(patch, note) }
    }

    /** The write itself, awaitable — the exclusion mutator must hold its lock across the round trip,
     *  otherwise two quick adds still both read the pre-write list. */
    private suspend fun applyPatch(patch: SandboxSettingsPatch, note: String? = null) {
        run {
            val base = settings.signalsApiUrl.first()
            val res = api.sandboxUpdateSettings(base, patch)
            _state.update { st ->
                st.copy(
                    state = st.state?.let { s -> res?.let { s.copy(settings = it, enabled = it.masterEnabled) } ?: s },
                    // On failure the server settings are unchanged, so the control silently snaps
                    // back — announcing the intended change as done was actively misleading about
                    // the state of an account that trades on its own.
                    message = if (res != null) note else "Couldn't save that setting — nothing changed",
                )
            }
        }
    }

    fun setEnabled(on: Boolean) = patchSettings(SandboxSettingsPatch(masterEnabled = on),
        note = if (on) "Auto-trading on" else "Auto-trading paused")

    fun setRisk(risk: String) = patchSettings(SandboxSettingsPatch(riskTolerance = risk))

    fun setRetirementDate(iso: String?) = patchSettings(SandboxSettingsPatch(retirementDate = iso ?: ""))

    fun setExitDate(iso: String?) = patchSettings(SandboxSettingsPatch(exitDate = iso ?: ""))

    fun setMaxPositionPct(pct: Double) = patchSettings(SandboxSettingsPatch(maxPositionPct = pct))

    fun setCashFloorPct(pct: Double) = patchSettings(SandboxSettingsPatch(cashFloorPct = pct))

    fun setAllowCrypto(on: Boolean) = patchSettings(SandboxSettingsPatch(allowCrypto = on))

    fun setAllowEtf(on: Boolean) = patchSettings(SandboxSettingsPatch(allowEtf = on))

    fun setAllowCryptoEtf(on: Boolean) = patchSettings(SandboxSettingsPatch(allowCryptoEtf = on))

    /** Which spot-bitcoin ETF the trader buys for BTC exposure. They all hold the same asset, so this
     *  is a custody/liquidity/fee choice rather than an investment one — hence a user setting rather
     *  than something the model decides per trade. */
    fun setPreferredBtcEtf(ticker: String) =
        patchSettings(SandboxSettingsPatch(preferredBtcEtf = ticker), note = "Bitcoin ETF set to $ticker")

    fun setGoal(amount: Double?, date: String?) =
        patchSettings(SandboxSettingsPatch(goalAmount = amount ?: 0.0, goalDate = date ?: ""), note = "Goal updated")

    fun setMonthlyDeposit(amount: Double) =
        patchSettings(SandboxSettingsPatch(monthlyDeposit = amount),
            note = if (amount > 0) "Recurring deposit set" else "Recurring deposit off")

    fun setExclusions(list: List<String>) =
        patchSettings(SandboxSettingsPatch(exclusions = list), note = "Exclusions updated")

    /** Serialises the exclusion read-modify-write. Two quick adds both read the same base list and
     *  the second replaced the first, because the server REPLACES the whole list on each patch. */
    private val exclusionLock = kotlinx.coroutines.sync.Mutex()

    fun addExclusion(ticker: String) {
        val t = ticker.trim().uppercase()
        if (t.isBlank()) return
        mutateExclusions { (it + t).distinct() }
    }

    fun removeExclusion(ticker: String) = mutateExclusions { list ->
        list.filterNot { it.equals(ticker, true) }
    }

    private fun mutateExclusions(transform: (List<String>) -> List<String>) {
        viewModelScope.launch {
            exclusionLock.withLock {
                // Only ever compute from the list the SERVER actually has. `currentSettings` falls
                // back to a default SandboxSettings() with an EMPTY exclusion list when state hasn't
                // loaded (first paint, or after a failed refresh) — so adding one ticker then sent
                // just that ticker and wiped every other exclusion the user had set.
                val known = _state.value.state?.settings?.exclusions ?: run {
                    _state.update { it.copy(message = "Couldn't update exclusions — settings not loaded yet") }
                    return@withLock
                }
                applyPatch(
                    SandboxSettingsPatch(exclusions = transform(known)),
                    note = "Exclusions updated",
                )
            }
        }
    }

    fun setCadence(c: String) = patchSettings(SandboxSettingsPatch(cadence = c))

    fun setTurnoverPct(pct: Double) = patchSettings(SandboxSettingsPatch(maxTurnoverPct = pct))

    fun setNotifyOnTrade(on: Boolean) = patchSettings(SandboxSettingsPatch(notifyOnTrade = on))

    fun setAllowAfterHours(on: Boolean) = patchSettings(SandboxSettingsPatch(allowAfterHours = on))

    fun setMinConviction(v: Int) = patchSettings(SandboxSettingsPatch(minConvictionToTrade = v))

    fun setRespectEntryZones(on: Boolean) = patchSettings(SandboxSettingsPatch(respectEntryZones = on))

    fun setAges(current: Int?, retire: Int?) =
        patchSettings(SandboxSettingsPatch(currentAge = current ?: 0, retirementAge = retire ?: 0), note = "Ages updated")

    fun setAccountType(t: String) = patchSettings(SandboxSettingsPatch(accountType = t))

    fun setAvoidWashSales(on: Boolean) = patchSettings(SandboxSettingsPatch(avoidWashSales = on))

    /** Manually run one decision cycle now (bypasses the once-a-day + session gates). */
    fun runTick() {
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            _state.update { it.copy(ticking = true, message = "Running a decision cycle…") }
            val res = api.sandboxTick(base, force = true)
            val msg = when {
                res == null -> "Tick failed — couldn't reach the service"
                res.status != "ok" -> "Skipped: ${res.status.replace('_', ' ')}"
                res.ordersFilled.isEmpty() -> "Ran — no trades this cycle (held)"
                else -> "Ran — ${res.ordersFilled.size} trade(s) executed"
            }
            _state.update { it.copy(ticking = false, message = msg) }
            // Announce any fills immediately (the 15-min worker would otherwise catch them later). Run
            // unconditionally so the notifier's watermark also gets primed on a no-trade cycle.
            runCatching { com.stocktracker.app.notify.SandboxTradeNotifier.check(app) }
            refresh()
        }
    }

    fun reset() {
        if (!actionInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            _state.update { it.copy(message = "Resetting…") }
            // Report what actually happened. Announcing "Sandbox reset" unconditionally meant a
            // failed reset looked identical to a successful one, on the single most destructive
            // action in the app.
            val ok = try {
                api.sandboxReset(base)
            } finally {
                actionInFlight.set(false)
            }
            _state.update { it.copy(message = if (ok) "Sandbox reset" else "Reset failed — nothing was changed") }
            refresh()
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---- performance-over-time math (pure; all series align index-for-index with `nav`) ----

    /** Ordinary least-squares fit y = a + b·i over the equity curve — the trajectory through the noise.
     *  Null-filled when there are too few points to fit a meaningful line. */
    private fun trendLine(values: List<Double>): List<Double?> {
        val n = values.size
        if (n < 3) return List(n) { null }
        val meanX = (n - 1) / 2.0
        val meanY = values.average()
        var num = 0.0
        var den = 0.0
        values.forEachIndexed { i, y ->
            val dx = i - meanX
            num += dx * (y - meanY)
            den += dx * dx
        }
        if (den == 0.0) return List(n) { null }
        val slope = num / den
        val intercept = meanY - slope * meanX
        return List(n) { i -> intercept + slope * i }
    }

    /**
     * The series the trend is fitted on: equity per dollar contributed, NOT raw equity.
     *
     * A recurring deposit adds money without earning anything. Fitting raw equity counts that as a
     * rally — a $500/mo deposit into a $10k account is ~+5%/month of pure contribution, which would
     * be reported as "Trending +5% / month" by an account that made nothing. Measured 2026-08-03:
     * equity rose $581 on the day, $500 of which was the monthly deposit; real performance was $81.
     *
     * `equity / funded_total` is an index that starts at 1.0 and moves only on performance, and it is
     * the same basis the backend's `total_return_pct` already uses, so the headline figure and the
     * trend agree. Falls back to raw equity only for rows written before funded_total was recorded.
     */
    private fun perDollarSeries(rows: List<com.stocktracker.app.data.remote.SandboxNavPoint>): List<Double> =
        rows.map { it.perDollar ?: it.equity }

    /** The fitted slope re-expressed as "% per 30 days" — a plain-language read of the trajectory
     *  that doesn't depend on how many ticks have accumulated, and is immune to deposits. */
    private fun trendPerMonth(rows: List<com.stocktracker.app.data.remote.SandboxNavPoint>): Double? {
        if (rows.size < 3) return null
        val fit = trendLine(perDollarSeries(rows))
        val first = fit.firstOrNull() ?: return null
        val last = fit.lastOrNull() ?: return null
        if (first == null || last == null || first <= 0.0) return null
        val days = (rows.last().ts - rows.first().ts) / 86_400.0
        if (days < 0.5) return null
        return (last - first) / first * 100.0 * (30.0 / days)
    }

    /**
     * The trend line drawn ON the equity chart.
     *
     * Fitted on the contribution-adjusted index (so its SLOPE is performance), then scaled back by
     * each point's `funded_total` so it sits on the same axis as the equity curve. Without the
     * re-scaling the overlay would be an index near 1.0 plotted against dollars and vanish off the
     * bottom of the chart; without the adjusted fit its slope would just be the deposits again.
     */
    private fun trendOnEquityAxis(
        rows: List<com.stocktracker.app.data.remote.SandboxNavPoint>,
    ): List<Double?> {
        val fit = trendLine(perDollarSeries(rows))
        return rows.mapIndexed { i, p ->
            val v = fit.getOrNull(i) ?: return@mapIndexed null
            val funded = p.fundedTotal
            if (funded != null && funded > 0) v * funded else v
        }
    }

    /** Portfolio return minus benchmark return since inception, per point, in percentage points.
     *  Rises when the sandbox is beating the S&P; crosses zero when it gives the lead back.
     *
     *  Deliberately NOT contribution-adjusted, because it does not need to be: the benchmark shadow
     *  is seeded with the initial funding and buys SPY with every deposit on the same day, so both
     *  legs take the identical dollar inflow and both are indexed to the same starting value — the
     *  deposit's effect cancels exactly in the subtraction. This is the money-weighted question you
     *  actually want ("did my dollars beat the same dollars in SPY"), so leave it alone. */
    private fun relativePerformance(
        rows: List<com.stocktracker.app.data.remote.SandboxNavPoint>,
    ): List<Double?> {
        val base = rows.firstOrNull() ?: return emptyList()
        val baseEq = base.equity
        val baseBm = base.benchmarkValue
        if (baseEq <= 0.0 || baseBm == null || baseBm <= 0.0) return List(rows.size) { null }
        return rows.map { r ->
            val bm = r.benchmarkValue
            if (bm == null || bm <= 0.0) null
            else (r.equity / baseEq - 1.0) * 100.0 - (bm / baseBm - 1.0) * 100.0
        }
    }

    val currentSettings: SandboxSettings get() = _state.value.state?.settings ?: SandboxSettings()
}
