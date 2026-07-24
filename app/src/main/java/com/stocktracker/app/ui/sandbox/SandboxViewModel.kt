package com.stocktracker.app.ui.sandbox

import androidx.lifecycle.ViewModel
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

data class SandboxUiState(
    val configured: Boolean = true,   // a Signals URL is set
    val loading: Boolean = true,
    val state: SandboxState? = null,
    val nav: List<PricePoint> = emptyList(),
    /** S&P shadow value aligned index-for-index to [nav] (null where a point lacks a benchmark). */
    val benchmarkValues: List<Double?> = emptyList(),
    val trades: List<SandboxTrade> = emptyList(),
    val ticking: Boolean = false,
    val message: String? = null,      // transient toast-style feedback
    val error: String? = null,
)

/** Drives the Sandbox tab — the read-only view of the server-side autonomous paper trader plus the
 *  hands-on controls (fund, settings, run-a-tick). All state lives on the backend; this just mirrors it. */
class SandboxViewModel : ViewModel() {

    private val api = SignalsApiService()
    private val settings = ServiceLocator.settingsStore

    private val _state = MutableStateFlow(SandboxUiState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
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
            _state.update {
                it.copy(
                    loading = false,
                    state = st ?: it.state,
                    nav = navRows.map { p -> PricePoint((p.ts * 1000).toLong(), p.equity) },
                    benchmarkValues = navRows.map { p -> p.benchmarkValue },
                    trades = trades,
                    error = if (st == null) "Couldn't reach the sandbox service." else null,
                )
            }
        }
    }

    fun fund(amount: Double) {
        if (amount == 0.0) return
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            _state.update { it.copy(message = "Updating funds…") }
            val ok = api.sandboxFund(base, amount)
            _state.update { it.copy(message = if (ok) "Funds updated" else "Couldn't update funds") }
            refresh()
        }
    }

    fun patchSettings(patch: SandboxSettingsPatch, note: String? = null) {
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            val res = api.sandboxUpdateSettings(base, patch)
            _state.update { st ->
                st.copy(
                    state = st.state?.let { s -> res?.let { s.copy(settings = it, enabled = it.masterEnabled) } ?: s },
                    message = note,
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
            refresh()
        }
    }

    fun reset() {
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            _state.update { it.copy(message = "Resetting…") }
            api.sandboxReset(base)
            _state.update { it.copy(message = "Sandbox reset") }
            refresh()
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    val currentSettings: SandboxSettings get() = _state.value.state?.settings ?: SandboxSettings()
}
