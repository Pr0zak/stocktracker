package com.stocktracker.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val asset: Asset,
    val quote: Quote? = null,
    val chart: List<Double> = emptyList(),
    val range: ChartRange = ChartRange.MONTH,
    val loadingChart: Boolean = true,
    val inWatchlist: Boolean = false,
)

class DetailViewModel(private val asset: Asset) : ViewModel() {

    private val repo = ServiceLocator.repository
    private val store = ServiceLocator.watchlistStore

    private val _state = MutableStateFlow(DetailUiState(asset))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.watchlist.collect { list ->
                _state.update { it.copy(inWatchlist = list.any { a -> a.id == asset.id }) }
            }
        }
        loadQuote()
        selectRange(ChartRange.MONTH)
    }

    private fun loadQuote() {
        viewModelScope.launch {
            val q = runCatching { repo.quote(asset) }.getOrNull()
                ?: ServiceLocator.priceCache.getQuote(asset.id)
            _state.update { it.copy(quote = q) }
        }
    }

    fun selectRange(range: ChartRange) {
        _state.update { it.copy(range = range, loadingChart = true) }
        viewModelScope.launch {
            val points = runCatching { repo.history(asset, range) }.getOrDefault(emptyList())
            _state.update { it.copy(chart = points.map { p -> p.price }, loadingChart = false) }
        }
    }

    fun toggleWatchlist() {
        viewModelScope.launch {
            if (_state.value.inWatchlist) store.remove(asset) else store.add(asset)
        }
    }
}
