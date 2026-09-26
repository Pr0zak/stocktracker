package com.stocktracker.app.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.remote.Report
import com.stocktracker.app.data.remote.ReportSummary
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReportUiState(
    val configured: Boolean = true,
    val loading: Boolean = true,
    /** Cleared on a failed load: a failure is shown as a failure, never as the previous report. */
    val report: Report? = null,
    val error: String? = null,
    /** The on-phone portfolio section. Null with [noHoldings] false means "not computed yet". */
    val portfolio: ReportPortfolio? = null,
    val portfolioLoading: Boolean = false,
    val noHoldings: Boolean = false,
)

/** One report (RPT-1): the backend's market + sandbox sections, plus the phone's portfolio section. */
class ReportViewModel : ViewModel() {

    private val api = SignalsApiService()
    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state
    private var job: Job? = null
    private var loadedId: String? = null

    fun load(id: String, force: Boolean = false) {
        if (!force && id == loadedId && _state.value.report != null) return
        loadedId = id
        job?.cancel()
        job = viewModelScope.launch {
            val base = ServiceLocator.settingsStore.signalsApiUrl.first()
            if (base.isBlank()) {
                _state.update { ReportUiState(configured = false, loading = false) }
                return@launch
            }
            _state.update { it.copy(configured = true, loading = true, error = null) }
            val r = runCatching { api.report(base, id) }
            val rep = r.getOrNull()
            if (rep == null || !rep.available) {
                _state.update {
                    ReportUiState(loading = false, error = rep?.reason ?: "Couldn't load this report. Check the connection and try again.")
                }
                return@launch
            }
            _state.update { it.copy(loading = false, report = rep, error = null, portfolioLoading = true) }
            val p = runCatching { ReportPortfolioStore.getOrCompute(rep) }.getOrNull()
            val none = p == null && ServiceLocator.watchlistStore.snapshot().none { (it.shares ?: 0.0) > 0.0 }
            _state.update { it.copy(portfolio = p, portfolioLoading = false, noHoldings = none) }
        }
    }

    /** Re-price the portfolio section with today's holdings (the only thing that overwrites it). */
    fun recalculatePortfolio() {
        val rep = _state.value.report ?: return
        viewModelScope.launch {
            _state.update { it.copy(portfolioLoading = true) }
            val p = runCatching { ReportPortfolioStore.compute(rep) }.getOrNull()
            _state.update { it.copy(portfolio = p ?: it.portfolio, portfolioLoading = false) }
        }
    }
}

data class ReportsUiState(
    val configured: Boolean = true,
    val loading: Boolean = true,
    val rows: List<ReportSummary> = emptyList(),
    val error: String? = null,
    /** Stored portfolio % per report id, for the "You" figure on each row. */
    val youPct: Map<String, Double> = emptyMap(),
)

/** The Reports list. */
class ReportsViewModel : ViewModel() {

    private val api = SignalsApiService()
    private val _state = MutableStateFlow(ReportsUiState())
    val state: StateFlow<ReportsUiState> = _state

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val base = ServiceLocator.settingsStore.signalsApiUrl.first()
            if (base.isBlank()) {
                _state.update { ReportsUiState(configured = false, loading = false) }
                return@launch
            }
            _state.update { it.copy(loading = true) }
            val r = runCatching { api.reports(base, limit = 60) }
            val you = runCatching { ReportPortfolioStore.all() }.getOrDefault(emptyMap())
                .mapNotNull { (k, v) -> v.changePct?.let { k to it } }.toMap()
            _state.update {
                r.fold(
                    onSuccess = { list -> it.copy(loading = false, rows = list?.reports.orEmpty(), error = null, youPct = you) },
                    onFailure = { _ -> it.copy(loading = false, rows = emptyList(), error = "Couldn't load the reports.", youPct = you) },
                )
            }
        }
    }
}
