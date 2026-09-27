package com.stocktracker.app.ui.funds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.remote.FundExploreResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * FUND-8 — Explore: well-known funds the user may not own yet, by type, return, worst drop and fee,
 * each with its cheaper copy and the "does it repeat what you own" check.
 */
class FundExploreViewModel : ViewModel() {

    data class UiState(
        val configured: Boolean = true,
        val loading: Boolean = true,
        val failed: Boolean = false,
        val resp: FundExploreResponse? = null,
        /** An ExploreCategory id; null = every type. */
        val category: String? = null,
        val query: String = "",
        val period: String = "5y",
        val sort: RankSort = RankSort.RETURN,
        val check: FundsViewModel.Check? = null,
    )

    private val settings = ServiceLocator.settingsStore
    private val api = SignalsApiService()

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private var loadJob: Job? = null
    private var checkJob: Job? = null

    init { load() }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) {
                _state.update { it.copy(configured = false, loading = false) }
                return@launch
            }
            _state.update { it.copy(configured = true, loading = true, failed = false) }
            var resp = runCatching { api.fundExplore(base) }.getOrNull()
            ensureActive()
            publish(resp)
            // The server answered from an old build and is making a new one (a few seconds): fetch
            // once more so the screen does not sit on figures the server already knows are old.
            if (resp?.refreshing == true) {
                delay(20_000)
                resp = runCatching { api.fundExplore(base) }.getOrNull()
                ensureActive()
                if (resp != null) publish(resp)
            }
        }
    }

    private fun publish(resp: FundExploreResponse?) {
        _state.update {
            val first = it.resp == null && resp != null
            it.copy(
                loading = false,
                failed = resp == null,
                resp = resp ?: it.resp,
                period = if (first) ExploreLogic.defaultPeriod(ExploreLogic.filter(resp!!.funds, it.category, "")) else it.period,
            )
        }
    }

    /** A new type also picks the longest window most of its funds can show (crypto has no 5 years). */
    fun setCategory(category: String?) {
        _state.update {
            val funds = it.resp?.funds.orEmpty()
            it.copy(category = category, period = ExploreLogic.defaultPeriod(ExploreLogic.filter(funds, category, "")))
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q.take(24)) }

    fun setPeriod(p: String) = _state.update { it.copy(period = p) }

    fun setSort(s: RankSort) = _state.update { it.copy(sort = s) }

    fun check(symbol: String) {
        val sym = symbol.trim().uppercase()
        if (sym.isBlank()) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _state.update { it.copy(check = FundsViewModel.Check(sym, loading = true)) }
            val c = checkAgainstHoldings(api, settings.signalsApiUrl.first(), sym)
            _state.update { it.copy(check = c) }
        }
    }

    fun clearCheck() {
        checkJob?.cancel()
        _state.update { it.copy(check = null) }
    }
}
