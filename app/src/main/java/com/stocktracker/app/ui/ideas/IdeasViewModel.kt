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
import com.stocktracker.app.data.remote.SmartMoneyResponse
import com.stocktracker.app.data.remote.ValueScreenResponse
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** The 200-week value screen. FREE (no LLM), so it loads regardless of [enabled] — gating it on
     *  the AI switch would hide a feature that costs nothing to run. */
    val screen: ValueScreenResponse? = null,
    val screenLoading: Boolean = false,
    val screenError: String? = null,
    /** Theme C — informed buying across the watchlist. Also free, so also ungated by the AI switch. */
    val smartMoney: SmartMoneyResponse? = null,
    val smartMoneyLoading: Boolean = false,
    val smartMoneyError: String? = null,
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
        // One-shot here would strand the card: loadScreen returns early on a blank URL and nothing
        // retried, so configuring the Signals URL AFTER first opening Ideas left the screen empty
        // until the process died. The comment on the collector above says exactly this; the fix has
        // to follow the same reactive pattern rather than sit beside it.
        viewModelScope.launch {
            settings.signalsApiUrl
                .map { it.trim() }
                .distinctUntilChanged()
                .collect { url ->
                    if (url.isNotBlank()) {
                        loadScreen(refresh = false)
                        loadSmartMoney(refresh = false)
                    }
                }
        }
    }

    /** Load the 200-week value screen. Needs only the Signals URL — no LLM, no AI-switch gate. */
    /** Theme C. Free — needs only the Signals URL, no AI-switch gate. */
    fun loadSmartMoney(refresh: Boolean) {
        // Same claim-before-suspend shape as loadScreen: checking a flag and setting it after a
        // suspension point leaves a window where two taps both pass.
        if (!_state.compareAndSet(
                _state.value,
                _state.value.let {
                    if (it.smartMoneyLoading) return
                    it.copy(smartMoneyLoading = true, smartMoneyError = null)
                },
            )
        ) return
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) {
                _state.update { it.copy(smartMoneyLoading = false) }
                return@launch
            }
            val res = runCatching { api.smartMoney(base, limit = 12, refresh = refresh) }
            _state.update { st ->
                st.copy(
                    smartMoneyLoading = false,
                    smartMoney = res.getOrNull() ?: st.smartMoney,
                    smartMoneyError = res.exceptionOrNull()?.let { it.message ?: "Couldn't load." },
                )
            }
        }
    }

    fun loadScreen(refresh: Boolean) {
        // The guard has to CLAIM the slot before any suspension point. Checking screenLoading here
        // and setting it after `settings.signalsApiUrl.first()` left a window where two fast taps
        // both passed the check and launched two full screens — each a fan-out over the universe.
        if (!_state.compareAndSet(
                _state.value,
                _state.value.let { if (it.screenLoading) return else it.copy(screenLoading = true, screenError = null) },
            )
        ) return
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) {
                _state.update { it.copy(screenLoading = false) }
                return@launch
            }
            val res = runCatching { api.valueScreen(base, limit = 15, refresh = refresh) }
            _state.update { st ->
                st.copy(
                    screenLoading = false,
                    // Keep the previous screen on a failed refresh rather than blanking it, but say
                    // so — a silent revert to older data is the failure mode this app keeps hitting.
                    screen = res.getOrNull() ?: st.screen,
                    screenError = res.exceptionOrNull()?.let { it.message ?: "Couldn't load the screen." },
                )
            }
        }
    }

    // Changing any INPUT invalidates the answer. The picks, their share counts and their dollar
    // allocations are all a function of the cash amount, the scope and the model — leaving them on
    // screen after those change presents an answer to a question the user is no longer asking.
    fun setCash(text: String) = _state.update { it.copy(cashText = text, error = null, result = null) }
    fun setDeep(deep: Boolean) = _state.update { it.copy(deep = deep, result = null, error = null) }
    fun setMarket(market: Boolean) = _state.update { it.copy(market = market, result = null, error = null) }

    /** Guards against a second paid analyst call from a double-tap; also makes the later response
     *  authoritative rather than whichever happened to land last. */
    private var requestGeneration = 0

    fun getIdeas() {
        if (_state.value.loading) return   // already running — a second tap costs real tokens
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
            val gen = ++requestGeneration
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
            if (gen != requestGeneration) return@launch   // superseded by a newer request
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
