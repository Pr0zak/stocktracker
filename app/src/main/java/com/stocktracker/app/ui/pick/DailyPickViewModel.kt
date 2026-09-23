package com.stocktracker.app.ui.pick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.JournalPlan
import com.stocktracker.app.data.model.TakenState
import com.stocktracker.app.data.model.VerdictJournalEntry
import com.stocktracker.app.data.remote.DailyPickFit
import com.stocktracker.app.data.remote.DailyPickHistory
import com.stocktracker.app.data.remote.DailyPickHolding
import com.stocktracker.app.data.remote.DailyPickResponse
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DailyPickUiState(
    val configured: Boolean = true,
    // True from construction: the first load starts in init, and until it has answered there is
    // no response to show. Starting false made the card claim "No response from the Signals
    // service" for the moment before the request was even sent (seen on a cold start).
    val loading: Boolean = true,
    /** The newest successful load. Cleared on a failed load — a failure never shows the old pick. */
    val resp: DailyPickResponse? = null,
    val error: String? = null,
    val fetchedAtMs: Long = 0L,
    val history: DailyPickHistory? = null,
    val historyError: String? = null,
    val historyLoading: Boolean = false,
    val fit: DailyPickFit? = null,
    val fitError: String? = null,
    /** Journal ids already logged for (date|symbol), so the button reads "Logged" after a tap. */
    val loggedKey: String? = null,
    val journalNote: String? = null,
    val rechecking: Boolean = false,
    val recheckError: String? = null,
    /** "Re-checked 4 min ago — next re-check in 6 min" when the cooldown answered instead. */
    val recheckNote: String? = null,
) {
    val shape: DailyPickRead.Shape get() = DailyPickRead.shape(configured, loading, resp, error)
}

/**
 * The Daily Pick card's state (DP-5). One load path, and it follows the rule every AI card in this app
 * now follows: a failed load REPLACES the previous pick with the failure. The card never keeps showing a
 * pick it could not confirm, because a stale pick with fresh-looking price levels is exactly the screen
 * that gets copied onto a broker ticket.
 */
class DailyPickViewModel : ViewModel() {

    private val api = SignalsApiService()
    private val _state = MutableStateFlow(DailyPickUiState())
    val state: StateFlow<DailyPickUiState> = _state

    private var loadJob: Job? = null

    init {
        load()
    }

    private suspend fun url(): String = ServiceLocator.settingsStore.signalsApiUrl.first()

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val base = url()
            if (base.isBlank()) {
                _state.update { it.copy(configured = false, loading = false, resp = null, error = null) }
                return@launch
            }
            _state.update { it.copy(configured = true, loading = true) }
            val result = runCatching { api.dailyPick(base) }
            _state.update { s ->
                result.fold(
                    onSuccess = { r ->
                        val symbolChanged = r?.pick?.symbol != s.resp?.pick?.symbol || r?.date != s.resp?.date
                        s.copy(
                            loading = false, resp = r, error = null, fetchedAtMs = System.currentTimeMillis(),
                            fit = if (symbolChanged) null else s.fit,
                            fitError = if (symbolChanged) null else s.fitError,
                        )
                    },
                    onFailure = { e ->
                        s.copy(loading = false, resp = null, fit = null, error = "Couldn't load today's pick — ${e.message ?: "the Signals service did not answer"}.")
                    },
                )
            }
            val picked = _state.value.resp?.takeIf { it.isPick }
            if (picked != null) {
                val key = "${picked.date}|${picked.pick?.symbol}"
                val already = ServiceLocator.verdictJournalStore.snapshot().any {
                    it.symbol == picked.pick?.symbol && it.verdictDateIso == picked.date && it.isTaken
                }
                _state.update { it.copy(loggedKey = if (already) key else it.loggedKey.takeIf { k -> k == key }) }
                if (_state.value.fit == null) loadFit()
            }
        }
    }

    fun loadHistory() {
        if (_state.value.historyLoading) return
        viewModelScope.launch {
            val base = url()
            if (base.isBlank()) return@launch
            _state.update { it.copy(historyLoading = true) }
            val r = runCatching { api.dailyPickHistory(base, limit = 20) }
            _state.update {
                it.copy(
                    historyLoading = false,
                    history = r.getOrNull(),
                    historyError = r.exceptionOrNull()?.let { e -> "Couldn't load past picks — ${e.message ?: "no answer"}." },
                )
            }
        }
    }

    /**
     * DP-12. Holdings are valued from the app's cached quotes; a holding with no cached quote is sent
     * with a null value, and the server then reports the fit as unknown rather than computing weights
     * over a partial book.
     */
    private fun loadFit() {
        viewModelScope.launch {
            val base = url()
            val sym = _state.value.resp?.pick?.symbol ?: return@launch
            val assets = ServiceLocator.watchlistStore.snapshot().filter { (it.shares ?: 0.0) > 0.0 }
            if (assets.isEmpty()) {
                _state.update { it.copy(fit = null, fitError = null) }
                return@launch
            }
            val quotes = ServiceLocator.priceCache.snapshotQuotes()
            val holdings = assets.map { a ->
                val s = if (a.type == AssetType.CRYPTO) "${a.symbol.uppercase()}-USD" else a.symbol.uppercase()
                val px = quotes[a.id]?.price?.takeIf { it > 0 }
                DailyPickHolding(s, px?.let { it * (a.shares ?: 0.0) })
            }
            val r = runCatching { api.dailyPickFit(base, holdings, sym) }
            _state.update {
                it.copy(
                    fit = r.getOrNull(),
                    fitError = r.exceptionOrNull()?.let { "Couldn't check the fit with your portfolio." },
                )
            }
        }
    }

    /**
     * DP-13 — "I bought it". Logs a TAKEN journal entry with the pick's plan SNAPSHOTTED (a copy, never a
     * reference) and the fill the user confirms. The intraday alerts then speak of "your position".
     */
    fun logBought(shares: Double?, fillPrice: Double?) {
        val resp = _state.value.resp ?: return
        val p = resp.pick ?: return
        val sym = p.symbol ?: return
        val date = resp.date ?: return
        viewModelScope.launch {
            val entry = VerdictJournalEntry(
                symbol = sym,
                verdictDateIso = date,
                plan = JournalPlan(
                    action = "buy_now",
                    entryLow = p.levels?.entryLow,
                    entryHigh = p.levels?.entryHigh,
                    stop = p.levels?.stop,
                    target = p.levels?.target,
                    conviction = p.conviction,
                    thesis = listOfNotNull("Daily pick", p.thesis).joinToString(": "),
                ),
                taken = TakenState.TAKEN,
                fillPrice = fillPrice?.takeIf { it > 0 },
                shares = shares?.takeIf { it > 0 },
                fillDateIso = if (fillPrice != null) java.time.LocalDate.now().toString() else null,
                notes = com.stocktracker.app.notify.DAILY_PICK_JOURNAL_NOTE,
            )
            ServiceLocator.verdictJournalStore.add(entry)
            _state.update {
                it.copy(
                    loggedKey = "$date|$sym",
                    journalNote = if (fillPrice == null || shares == null) {
                        "Logged in your journal. Add the fill there so it can be scored."
                    } else "Logged in your journal with your fill.",
                )
            }
        }
    }

    fun clearJournalNote() = _state.update { it.copy(journalNote = null) }

    /**
     * Ask the server to re-check this morning's pick against live prices. The result arrives as the
     * card's `recheck` on the next load; a failure is shown as a failure beside the button.
     */
    fun recheck() {
        if (_state.value.rechecking) return
        viewModelScope.launch {
            val base = url()
            if (base.isBlank()) return@launch
            _state.update { it.copy(rechecking = true, recheckError = null, recheckNote = null) }
            val r = runCatching { api.recheckDailyPick(base) }
            val rc = r.getOrNull()
            _state.update {
                it.copy(
                    rechecking = false,
                    recheckError = r.exceptionOrNull()?.let { e -> "Couldn't re-check — ${e.message ?: "no answer"}." }
                        ?: rc?.takeIf { x -> x.status == "failed" }?.let { x -> "Re-check failed: ${x.error ?: "no reason given"}." },
                    recheckNote = rc?.cooldownSeconds?.takeIf { s -> s > 0 }
                        ?.let { s -> "Showing the last re-check — the next one can run in ${(s + 59) / 60} min." },
                )
            }
            load()
        }
    }
}
