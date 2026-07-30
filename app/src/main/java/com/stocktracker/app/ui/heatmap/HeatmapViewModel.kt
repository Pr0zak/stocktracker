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
    /** Names the scan could not measure — reported, never silently absent from the map. */
    val skipped: List<String> = emptyList(),
    /** Epoch seconds the data was produced. Signals mode is a nightly scan and is always hours old. */
    val asOf: Double? = null,
    val session: String? = null,
)

class HeatmapViewModel : ViewModel() {

    private val settings = ServiceLocator.settingsStore
    private val api = SignalsApiService()
    private val _state = MutableStateFlow(HeatmapUiState())
    val state = _state.asStateFlow()

    init { load(refresh = false) }

    /** Set when a mode switch arrives mid-flight, so the completing load re-issues for the new mode. */
    private var pendingMode: String? = null

    fun setMode(mode: String) {
        if (mode == _state.value.mode) return
        // The tiles belong to the OLD mode and mean something different under the new one — a
        // market tile's colour is a price move, a signal tile's is a dip tier. Showing the old set
        // under the new legend would mislabel every tile on screen.
        _state.update { it.copy(mode = mode, tiles = emptyList(), error = null) }
        // Two individually-correct guards used to lose the request between them: load() returns
        // early while the first fetch is in flight, and that fetch's response is then discarded for
        // being the wrong mode. Net result was an empty map reading "Nothing to draw yet" — which
        // says the system found nothing, when in truth nothing was ever asked. Remember the intent
        // and re-issue when the in-flight load lands.
        if (_state.value.loading) pendingMode = mode else load(refresh = false)
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
                    skipped = r?.skipped ?: emptyList(),
                    asOf = r?.asOf ?: st.asOf,
                    session = r?.session,
                    universeStale = r?.universeStale,
                    // On a FAILED refresh the previous tiles stay on screen; wiping their age made
                    // stale data look freshly loaded. Keep the age when the tiles are kept.
                    cachedAgeSeconds = when {
                        r?.cached == true -> r.cachedAgeSeconds
                        r != null -> null
                        else -> st.cachedAgeSeconds
                    },
                    error = res.exceptionOrNull()?.let { it.message ?: "Couldn't load the heat map." },
                )
            }
            // Whatever the outcome, honour a switch that arrived while this was running.
            pendingMode?.let { want ->
                pendingMode = null
                if (want == _state.value.mode) load(refresh = false)
            }
        }
    }
}
