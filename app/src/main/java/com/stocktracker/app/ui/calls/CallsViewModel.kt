package com.stocktracker.app.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.CallPosition
import com.stocktracker.app.data.remote.OptionQuoteResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A tracked call plus its most recent live re-price (if any), and the load/error status for it. */
data class CallRow(
    val position: CallPosition,
    val quote: OptionQuoteResponse? = null, // last SUCCESSFUL re-price (kept across a failed refresh)
    val loading: Boolean = false,
    val failed: Boolean = false,            // last re-price attempt returned nothing (404 / closed / error)
) {
    /** Live premium per share, from the re-price. Null when we've never priced this contract. */
    val currentPrice: Double? get() = quote?.contract?.currentPrice

    /** Live position value = premium × 100 × contracts. */
    val currentValue: Double? get() = currentPrice?.let { it * 100.0 * position.contracts }

    /** Unrealized P/L in dollars vs the cost basis (premium paid × 100 × contracts). */
    val unrealizedPl: Double? get() = currentValue?.let { it - position.costBasis }

    val unrealizedPlPct: Double?
        get() = unrealizedPl?.let { if (position.costBasis != 0.0) it / position.costBasis * 100.0 else null }

    /** Days to expiry — from the live quote, else computed from the stored expiry (so it shows offline). */
    val dte: Int
        get() = quote?.dte?.roundToInt()
            ?: (((position.expiryTs * 1000L - System.currentTimeMillis()) / 86_400_000L).toInt()).coerceAtLeast(0)

    /** In-the-money per the server, else inferred from spot vs strike (a call is ITM when spot ≥ strike). */
    val inTheMoney: Boolean?
        get() = quote?.contract?.inTheMoney ?: quote?.spot?.let { it >= position.strike }
}

data class CallsUiState(
    val rows: List<CallRow> = emptyList(),
    /** A Signals service URL is configured — required to re-price. Positions still persist without it. */
    val configured: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * Backs the "My Calls" tracker (OC-3): streams the persisted call positions and re-prices each one
 * through the signals service's /option_quote endpoint. A failed quote (market closed / contract gone)
 * keeps the last-known value and flags the row rather than dropping it.
 */
class CallsViewModel : ViewModel() {

    private val store = ServiceLocator.callPositionStore
    private val settings = ServiceLocator.settingsStore
    private val api = SignalsApiService()

    private val _state = MutableStateFlow(CallsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.positions.collect { positions ->
                // Carry any quotes we already have so the list doesn't flash to "—" on an edit/add.
                val prev = _state.value.rows.associateBy { it.position.id }
                val rows = positions.map { p -> prev[p.id]?.copy(position = p) ?: CallRow(p) }
                _state.update { it.copy(rows = rows, loaded = true) }
                repriceAll()
            }
        }
    }

    /** Re-price every tracked contract concurrently. No-op (rows flagged) when no service URL is set. */
    fun repriceAll() {
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            _state.update { it.copy(configured = base.isNotBlank()) }
            if (base.isBlank()) {
                _state.update { st -> st.copy(rows = st.rows.map { it.copy(loading = false) }) }
                return@launch
            }
            _state.update { st -> st.copy(rows = st.rows.map { it.copy(loading = true, failed = false) }) }
            _state.value.rows.map { it.position.id }.forEach { id ->
                launch {
                    val p = _state.value.rows.firstOrNull { it.position.id == id }?.position ?: return@launch
                    val quote = runCatching { api.optionQuote(base, p.symbol, p.expiryTs, p.strike, p.type) }.getOrNull()
                    _state.update { st ->
                        st.copy(
                            rows = st.rows.map { r ->
                                if (r.position.id != id) {
                                    r
                                } else {
                                    r.copy(
                                        quote = quote ?: r.quote, // keep last-known on a failed refresh
                                        loading = false,
                                        failed = quote == null,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    fun add(position: CallPosition) { viewModelScope.launch { store.add(position) } }

    fun delete(id: String) { viewModelScope.launch { store.delete(id) } }
}
