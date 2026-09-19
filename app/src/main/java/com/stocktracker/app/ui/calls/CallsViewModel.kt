package com.stocktracker.app.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.CallPosition
import com.stocktracker.app.data.model.ClosedCallPosition
import com.stocktracker.app.data.model.Lot
import com.stocktracker.app.data.model.PositionSide
import com.stocktracker.app.data.model.asAssigned
import com.stocktracker.app.data.model.asExercised
import com.stocktracker.app.data.model.asExpiredWorthless
import com.stocktracker.app.data.model.asSold
import com.stocktracker.app.data.remote.OptionQuoteResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
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

    /** Live position value = premium × 100 × contracts — what it would cost to trade right now. */
    val currentValue: Double? get() = currentPrice?.let { it * 100.0 * position.contracts }

    /**
     * Unrealized P/L in dollars. A LONG gains as the contract's value rises above what was paid
     * ([CallPosition.costBasis]); a SHORT gains as it FALLS below what was collected — you'd buy it
     * back for less than the credit you took in, which is why the sign flips (MONEY-3).
     */
    val unrealizedPl: Double?
        get() = currentValue?.let {
            when (position.side) {
                PositionSide.LONG -> it - position.costBasis
                PositionSide.SHORT -> position.costBasis - it
            }
        }

    val unrealizedPlPct: Double?
        get() = unrealizedPl?.let { if (position.costBasis != 0.0) it / position.costBasis * 100.0 else null }

    /**
     * Days to expiry — from the live quote, else computed from the stored expiry (so it shows offline).
     *
     * A STALE quote is not used for this. Every other field a quote carries is a measurement of a
     * moment, and an hour-old measurement is an hour-old measurement; days-to-expiry is a countdown,
     * so an hour-old one is simply wrong, and on the day of expiry it is wrong in the direction that
     * matters. The local arithmetic below cannot go stale, so a failed re-price falls through to it.
     */
    val dte: Int
        get() = quote?.dte?.takeIf { !failed }?.roundToInt()
            // Ceiling, not floor. expiryTs is stored at midnight, so flooring the remaining
            // milliseconds read a full day short for all but the first moments of each day — firing
            // "expires tomorrow" alerts a day early and showing 0 DTE on a contract with a day left.
            ?: kotlin.math.ceil(
                (position.expiryTs * 1000L - System.currentTimeMillis()) / 86_400_000.0,
            ).toInt().coerceAtLeast(0)

    /** In-the-money per the server, else inferred from spot vs strike (a call is ITM when spot ≥ strike). */
    val inTheMoney: Boolean?
        get() = quote?.contract?.inTheMoney ?: quote?.spot?.let { it >= position.strike }
}

data class CallsUiState(
    val rows: List<CallRow> = emptyList(),
    /** Closed-out positions (OC-5), newest first for display. */
    val closed: List<ClosedCallPosition> = emptyList(),
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
    private val closedStore = ServiceLocator.closedCallPositionStore
    private val settings = ServiceLocator.settingsStore
    private val api = SignalsApiService()

    private val _state = MutableStateFlow(CallsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Newest close first — the store appends to the tail.
            closedStore.closed.collect { closed ->
                _state.update { it.copy(closed = closed.asReversed()) }
            }
        }
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

    /** Sold to close: record the realized P/L at [exitPricePerShare] and remove from the open list. */
    fun closeSold(position: CallPosition, exitPricePerShare: Double) {
        viewModelScope.launch {
            closedStore.add(position.asSold(exitPricePerShare, today()))
            store.delete(position.id)
        }
    }

    /**
     * Exercised: record the outcome (no option P/L — value rolls into the shares) and remove from
     * open.
     *
     * Also appends a dated [Lot] to the matching watchlist holding — 100 × contracts shares at
     * strike + premium paid, dated today (MONEY-2). This funnels through
     * [com.stocktracker.app.data.prefs.WatchlistStore.addLot], the exact same path a recorded journal
     * fill uses ([com.stocktracker.app.ui.journal.JournalViewModel.markTaken]), so a real acquisition
     * is recorded the same way regardless of which screen it came from. There is no separate opt-in
     * here: unlike a journal fill, which can be logged with the numbers still unknown, exercising
     * ALWAYS turns the contract into real shares at a known cost, and [ConfirmCloseDialog] already
     * states as much before this is ever called — that dialog IS the one confirmation line.
     */
    fun markExercised(position: CallPosition) {
        viewModelScope.launch {
            closedStore.add(position.asExercised(today()))
            store.delete(position.id)
            ServiceLocator.watchlistStore.addLot(
                position.symbol,
                Lot(
                    shares = 100.0 * position.contracts,
                    costPerShare = position.strike + position.fillPrice,
                    acquiredDateIso = today(),
                ),
            )
        }
    }

    /**
     * Assigned (MONEY-3): the SHORT-side mirror of [markExercised] — the counterparty exercised
     * against you. Records the outcome (no option P/L — same reasoning as EXERCISED) and appends a
     * dated [Lot] to the matching watchlist holding through the same
     * [com.stocktracker.app.data.prefs.WatchlistStore.addLot] path every other real acquisition uses:
     *
     *  - a short PUT assigned BUYS 100 × contracts shares at (strike − premium collected) — a
     *    positive lot, same shape as [markExercised]'s, mirrored because the premium was collected
     *    instead of paid;
     *  - a short CALL assigned SELLS 100 × contracts shares away at the strike — a NEGATIVE lot (a
     *    disposal; see [com.stocktracker.app.data.model.Asset.avgCost] for how that folds into the
     *    holding's average cost without corrupting it).
     *
     * As with [markExercised], there is no separate opt-in: assignment always turns the contract into
     * a real share transaction at a known price, and the confirmation dialog states as much before
     * this is ever called.
     */
    fun markAssigned(position: CallPosition) {
        viewModelScope.launch {
            closedStore.add(position.asAssigned(today()))
            store.delete(position.id)
            val isPut = position.type.equals("put", ignoreCase = true)
            val lot = if (isPut) {
                Lot(
                    shares = 100.0 * position.contracts,
                    costPerShare = position.strike - position.fillPrice,
                    acquiredDateIso = today(),
                )
            } else {
                Lot(
                    shares = -100.0 * position.contracts,
                    costPerShare = position.strike,
                    acquiredDateIso = today(),
                )
            }
            ServiceLocator.watchlistStore.addLot(position.symbol, lot)
        }
    }

    /** Expired worthless: record the full loss (−100%) and remove from open. */
    fun markExpiredWorthless(position: CallPosition) {
        viewModelScope.launch {
            closedStore.add(position.asExpiredWorthless(today()))
            store.delete(position.id)
        }
    }

    private fun today(): String = LocalDate.now().toString()
}
