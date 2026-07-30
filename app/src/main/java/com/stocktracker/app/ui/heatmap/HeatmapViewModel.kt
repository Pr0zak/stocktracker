package com.stocktracker.app.ui.heatmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.data.remote.HeatmapTile
import com.stocktracker.app.data.remote.SignalsApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HeatmapUiState(
    val mode: String = "market",
    val tiles: List<HeatmapTile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val advancing: Int? = null,
    val declining: Int? = null,
    /** Names we could not price — distinct from names that did not move. */
    val unpriced: List<String> = emptyList(),
    val universeStale: Boolean? = null,
    val cachedAgeSeconds: Long? = null,
)

class HeatmapViewModel : ViewModel() {

    private val settings = ServiceLocator.settingsStore
    private val api = SignalsApiService()
    private val _state = MutableStateFlow(HeatmapUiState())
    val state = _state.asStateFlow()

    init { load(refresh = false) }

    fun setMode(mode: String) {
        if (mode == _state.value.mode) return
        // The tiles belong to the OLD mode and mean something different under the new one — a
        // market tile's colour is a price move, a signal tile's is a dip tier. Showing the old set
        // under the new legend would mislabel every tile on screen.
        _state.update { it.copy(mode = mode, tiles = emptyList(), error = null) }
        load(refresh = false)
    }

    fun load(refresh: Boolean) {
        // Claim the slot BEFORE suspending, or two taps both pass the check.
        val before = _state.value
        if (before.loading) return
        if (!_state.compareAndSet(before, before.copy(loading = true, error = null))) return
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first()
            if (base.isBlank()) {
                _state.update {
                    it.copy(loading = false, error = "Set the Signals service URL in Settings.")
                }
                return@launch
            }
            val mode = _state.value.mode
            val res = runCatching { api.heatmap(base, mode = mode, refresh = refresh) }
            val r = res.getOrNull()
            _state.update { st ->
                // A response that arrived after the user switched modes must not be applied — its
                // tiles carry the other mode's meaning.
                if (r != null && r.mode != st.mode) st.copy(loading = false)
                else st.copy(
                    loading = false,
                    tiles = r?.tiles ?: st.tiles,
                    advancing = r?.advancing, declining = r?.declining,
                    unpriced = r?.unpriced ?: emptyList(),
                    universeStale = r?.universeStale,
                    cachedAgeSeconds = if (r?.cached == true) r.cachedAgeSeconds else null,
                    error = res.exceptionOrNull()?.let { it.message ?: "Couldn't load the heat map." },
                )
            }
        }
    }
}
