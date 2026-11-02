package com.stocktracker.app.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.AskHolding
import com.stocktracker.app.data.remote.AskResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.data.remote.analystErrorDetail
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shown until the server's own list loads, and if it never does. Same wording as app/ask.py. */
internal val FALLBACK_EXAMPLES = listOf(
    "Find an ETF to invest in today",
    "Which ticker should I invest in today?",
    "Based on today's market dips, which ticker or ETF should I buy?",
    "Which ETF has a decent dip to shore up my holdings?",
)

data class AskUiState(
    /** False when no Signals URL is set or the AI analyst switch is off — this costs a model call. */
    val enabled: Boolean = true,
    val question: String = "",
    val examples: List<String> = FALLBACK_EXAMPLES,
    val includeHoldings: Boolean = true,
    val loading: Boolean = false,
    /** Newest first. Kept for the life of the screen so an earlier answer is not lost to a new one. */
    val answers: List<AskResponse> = emptyList(),
    val error: String? = null,
)

class AskViewModel : ViewModel() {
    private val settings = ServiceLocator.settingsStore
    private val store = ServiceLocator.watchlistStore
    private val api = SignalsApiService()

    private val _state = MutableStateFlow(AskUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(settings.signalsApiUrl, settings.aiAnalystEnabled) { url, on -> url.isNotBlank() && on }
                .collect { on -> _state.update { it.copy(enabled = on) } }
        }
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            runCatching { api.askExamples(base) }.getOrNull()?.examples
                ?.takeIf { it.isNotEmpty() }
                ?.let { ex -> _state.update { it.copy(examples = ex) } }
        }
    }

    fun setQuestion(q: String) = _state.update { it.copy(question = q) }
    fun setIncludeHoldings(on: Boolean) = _state.update { it.copy(includeHoldings = on) }

    fun ask(text: String = _state.value.question) {
        val q = text.trim()
        if (q.isEmpty()) return
        // Claim before suspending, so two quick taps cannot both send.
        if (!_state.compareAndSet(_state.value, _state.value.let {
                if (it.loading) return
                it.copy(loading = true, error = null, question = q)
            })) return
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            val holdings = if (_state.value.includeHoldings) holdingWeights() else emptyList()
            val res = runCatching { api.ask(base, q, holdings) }
            val resp = res.getOrNull()
            _state.update { st ->
                st.copy(
                    loading = false,
                    answers = if (resp != null) listOf(resp) + st.answers else st.answers,
                    question = if (resp != null) "" else st.question,
                    error = if (resp == null) {
                        analystErrorDetail(res.exceptionOrNull()) ?: "Couldn't reach the analyst — try again."
                    } else null,
                )
            }
        }
    }

    /**
     * Each holding's share of the portfolio AT COST (shares × average cost). The watchlist store holds
     * no live prices, and a weight that is a few points off still tells the analyst what the investor
     * already owns, which is all it is used for. Symbol and weight only — no share counts or costs.
     */
    private suspend fun holdingWeights(): List<AskHolding> {
        val held = store.snapshot().mapNotNull { a ->
            val cost = (a.shares ?: 0.0) * (a.avgCost ?: 0.0)
            if (cost <= 0) null
            else (if (a.type == AssetType.CRYPTO) "${a.symbol.uppercase()}-USD" else a.symbol.uppercase()) to cost
        }
        val total = held.sumOf { it.second }
        if (total <= 0) return emptyList()
        return held.map { (sym, cost) -> AskHolding(sym, cost / total * 100.0) }
    }
}
