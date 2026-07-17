package com.stocktracker.app.ui.ideas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.HoldingSync
import com.stocktracker.app.data.remote.RecommendationsResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.data.remote.analystErrorDetail
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IdeasUiState(
    /** False when no Signals service URL is configured or the AI analyst switch is off. */
    val enabled: Boolean = true,
    val cashText: String = "",
    val deep: Boolean = false,
    /** True widens the search beyond the watchlist with live market-screen candidates. */
    val market: Boolean = false,
    val loading: Boolean = false,
    val result: RecommendationsResponse? = null,
    val error: String? = null,
)

/**
 * "Where should new money go?" — sends the investable cash (plus current holdings, transiently) to
 * the signals service, which ranks the synced watchlist and spreads the cash across its top picks.
 */
class IdeasViewModel : ViewModel() {

    private val settings = ServiceLocator.settingsStore
    private val store = ServiceLocator.watchlistStore
    private val api = SignalsApiService()

    private val _state = MutableStateFlow(IdeasUiState())
    val state = _state.asStateFlow()

    init {
        // Reactive: the tab's ViewModel outlives tab switches, so a URL configured in Settings
        // after first visit (or the AI switch being flipped) must still take effect here.
        viewModelScope.launch {
            combine(settings.signalsApiUrl, settings.aiAnalystEnabled) { url, on ->
                url.isNotBlank() && on
            }.collect { enabled -> _state.update { it.copy(enabled = enabled) } }
        }
        viewModelScope.launch {
            val cash = settings.investableCash.first()
            if (cash > 0) _state.update { it.copy(cashText = formatCash(cash)) }
        }
    }

    fun setCash(text: String) = _state.update { it.copy(cashText = text, error = null) }
    fun setDeep(deep: Boolean) = _state.update { it.copy(deep = deep) }
    fun setMarket(market: Boolean) = _state.update { it.copy(market = market) }

    fun getIdeas() {
        val cash = _state.value.cashText.replace(",", "").removePrefix("$").trim().toDoubleOrNull()
        if (cash == null || cash <= 0) {
            _state.update { it.copy(error = "Enter the cash amount you want to deploy") }
            return
        }
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) {
                _state.update { it.copy(enabled = false) }
                return@launch
            }
            settings.setInvestableCash(cash) // remembered — also drives detail-screen entry plans
            _state.update { it.copy(loading = true, error = null) }
            // Holdings ride along transiently so the analyst can weigh existing exposure.
            val holdings = store.snapshot()
                .filter { (it.shares ?: 0.0) > 0 && (it.avgCost ?: 0.0) > 0 }
                .map {
                    val sym = if (it.type == AssetType.CRYPTO) "${it.symbol.uppercase()}-USD" else it.symbol.uppercase()
                    HoldingSync(sym, it.shares!!, it.avgCost!!)
                }
            val s0 = _state.value
            val res = runCatching { api.recommendations(base, cash, s0.deep, holdings, market = s0.market) }
            val resp = res.getOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    result = resp ?: it.result, // keep the previous ideas on a failed refresh
                    error = if (resp == null) {
                        analystErrorDetail(res.exceptionOrNull()) ?: "Couldn't reach the analyst service"
                    } else {
                        null
                    },
                )
            }
        }
    }

    private fun formatCash(v: Double): String = formatCashPlain(v)
}

/** Locale-proof cash text: no grouping, '.' decimal — always round-trips through toDoubleOrNull().
 *  (Locale-aware "%,d" grouping can turn 5000 into "5.000", which re-parses as 5.0.) */
internal fun formatCashPlain(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)
