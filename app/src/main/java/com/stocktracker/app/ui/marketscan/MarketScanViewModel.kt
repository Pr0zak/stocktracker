package com.stocktracker.app.ui.marketscan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.remote.MarketBreadth
import com.stocktracker.app.data.remote.MarketScanRow
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.data.remote.analystErrorDetail
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * SWT-1 — the nightly whole-market scan, screen-side.
 *
 * Deliberately NOT wired into WidgetRefreshWorker. That worker wakes every 15 minutes, often on
 * cellular, and this is a once-a-night cross-section: polling it 96 times a day would spend the
 * user's data re-downloading a number that changes while they sleep. Loading is screen-scoped, and
 * [runScan] — minutes of server work — is user-initiated only.
 */
data class MarketScanUiState(
    /** Null while the Signals URL has not been read yet — which is not the same as "not set". */
    val configured: Boolean? = null,
    val loading: Boolean = false,
    val running: Boolean = false,
    val breadthLoading: Boolean = false,
    /** Set on failure WHILE the previous rows stay on screen — old data with no error beside it is
     *  the failure mode this whole file exists to avoid. */
    val error: String? = null,
    val breadthError: String? = null,
    val runError: String? = null,
    /** What we asked the server to sort by; null means "server's choice". */
    val sort: String? = null,
    /** What the server says it actually sorted by. Shown instead of [sort]: the two can differ, and
     *  the ranking on screen belongs to this one. */
    val appliedSort: String? = null,
    val limit: Int = 50,
    val rows: List<MarketScanRow> = emptyList(),

    // ---- provenance. Every one of these is nullable: absent is a state the UI must render. ----
    val asOf: String? = null,
    val generatedAt: Double? = null,
    val universeSize: Int? = null,
    val scanned: Int? = null,
    /** Kept separate from [tooShort] all the way to the screen. Never add them together. */
    val fetchFailed: Int? = null,
    val tooShort: Int? = null,
    val universeStale: Boolean? = null,
    val totalMatching: Int? = null,
    /**
     * SWT-4 — how many names the rows' percentiles were ranked against: the WHOLE night, never the
     * slice on screen. Null when the server did not say, and [rankFooter] then says so in words
     * rather than reaching for [totalMatching] or `rows.size`, either of which would label a rank
     * with a population it was not computed over.
     */
    val percentilesOver: Int? = null,
    val note: String = "",
    val cachedAgeSeconds: Long? = null,

    val breadth: MarketBreadth? = null,
    /** The last run's outcome ("ok" | "empty" | "refused"), for the button's result line. */
    val lastRunStatus: String? = null,
    val lastRunReason: String? = null,
) {
    /** True once a load has produced rows — so "nothing found" and "nothing asked" can differ. */
    val loaded: Boolean get() = asOf != null || rows.isNotEmpty()

    /**
     * The one line that keeps a column of percentiles from reading as a column of grades.
     *
     * Every rank on the screen is a position in ONE night's cross-section, so the night and the size
     * of that cross-section are said once, plainly, instead of being repeated on every row. Degrades
     * through what is actually known — a missing count becomes "that night's scan", never a number
     * borrowed from somewhere else — and never claims a rank means anything about what happens next.
     */
    val rankFooter: String
        get() {
            val night = MarketScanProvenance.asOfLabel(asOf)
            val over = percentilesOver?.takeIf { it > 0 }?.let { String.format(Locale.US, "%,d", it) }
            val where = when {
                night != null && over != null -> "the $night scan of $over names"
                night != null -> "the $night scan"
                over != null -> "that night's scan of $over names"
                else -> "that night's scan"
            }
            return "Percentiles are ranks within $where — not scores, and not a buy signal."
        }

    /** "Nightly scan · 2026-08-21 · 3h ago · 3,113 of 3,147 scanned · …" — never blank. */
    fun provenance(nowMs: Long = System.currentTimeMillis()): String =
        MarketScanProvenance.summary(
            asOf = asOf,
            generatedAtEpochSeconds = generatedAt,
            scanned = scanned,
            universeSize = universeSize,
            fetchFailed = fetchFailed,
            tooShort = tooShort,
            universeStale = universeStale,
            nowMs = nowMs,
        )
}

class MarketScanViewModel : ViewModel() {

    private val settings = ServiceLocator.settingsStore
    private val api = SignalsApiService()
    private val _state = MutableStateFlow(MarketScanUiState())
    val state = _state.asStateFlow()

    /**
     * Which request the rows on screen belong to.
     *
     * The ordering guard is a sequence number rather than a comparison against the response's echoed
     * `sort`, on purpose: the server is free to normalise a sort name ("rel_strength" →
     * "rel_strength_3mo"), and a strict string match against a normalised echo would discard every
     * response forever and leave an empty list reading "nothing found" — the system saying it looked
     * and saw nothing, when in truth every answer was thrown away.
     */
    private val seq = AtomicInteger(0)
    private var applied = 0

    /** Set when the sort changes mid-flight, so the completing load re-issues for the new one. */
    private var pendingSort: String? = null
    private var pendingSortSet = false

    init {
        // Reactive: this ViewModel outlives tab switches, so a URL entered in Settings after the
        // first visit has to take effect without a process restart.
        viewModelScope.launch {
            settings.signalsApiUrl
                .map { it.trim() }
                .distinctUntilChanged()
                .collect { url ->
                    if (url.isNotBlank()) {
                        _state.update { it.copy(configured = true) }
                        load()
                        loadBreadth()
                    } else {
                        // With no service behind them these rows cannot be refreshed, re-sorted or
                        // vouched for. Leaving them up would present an unreachable backend's last
                        // answer as the current state of the market, beside a "not configured"
                        // message that flatly contradicts it.
                        _state.update {
                            it.copy(
                                configured = false, rows = emptyList(), breadth = null,
                                appliedSort = null, asOf = null, generatedAt = null,
                                universeSize = null, scanned = null, fetchFailed = null,
                                tooShort = null, universeStale = null, totalMatching = null,
                                percentilesOver = null, note = "", cachedAgeSeconds = null,
                            )
                        }
                    }
                }
        }
    }

    fun setLimit(limit: Int) {
        val clamped = limit.coerceIn(1, 500)
        if (clamped == _state.value.limit) return
        _state.update { it.copy(limit = clamped) }
        load()
    }

    fun setSort(sort: String?) {
        val want = sort?.trim()?.takeIf { it.isNotEmpty() }
        if (want == _state.value.sort) return
        // The rows on screen are the OLD ranking. Leaving them under the new sort's label would
        // present an arbitrary list as "the top 50 by <new metric>", which is a claim we would be
        // making up. Clear them and say we are loading.
        _state.update { it.copy(sort = want, rows = emptyList(), appliedSort = null, error = null) }
        if (_state.value.loading) {
            // Two individually-correct guards can otherwise lose the request between them: load()
            // returns early while a fetch is in flight, and that fetch's result is then discarded
            // for being the wrong sequence. Remember the intent and re-issue on completion.
            pendingSort = want
            pendingSortSet = true
        } else {
            load()
        }
    }

    fun load() {
        // Claim the slot BEFORE suspending, or two taps both pass the check and both fan out.
        val before = _state.value
        if (before.loading) return
        if (!_state.compareAndSet(before, before.copy(loading = true, error = null))) return
        val mySeq = seq.incrementAndGet()
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first().trim()
            if (base.isBlank()) {
                _state.update {
                    it.copy(loading = false, configured = false,
                        error = "Set the Signals service URL in Settings.")
                }
                reissueIfPending()
                return@launch
            }
            val requested = _state.value.sort
            val res = runCatching { api.marketScan(base, limit = _state.value.limit, sort = requested) }
            val r = res.getOrNull()
            // Decided BEFORE the update block, which a MutableStateFlow may re-run under contention:
            // a side effect in there can happen twice. Two reasons to drop a payload — it is older
            // than one already shown, or the user changed sort while it was in flight and these rows
            // are the previous ranking, which would be presented under the new metric's label.
            val stale = r != null && (mySeq < applied || requested != _state.value.sort)
            if (r != null && !stale) applied = mySeq
            _state.update { st ->
                if (stale) return@update st.copy(loading = false)
                st.copy(
                    loading = false,
                    // Keep the previous rows on a failure — but ALWAYS with the error set, and with
                    // the provenance fields untouched, so the age line still describes the rows that
                    // are actually on screen rather than this failed attempt.
                    rows = r?.results ?: st.rows,
                    appliedSort = r?.sort ?: st.appliedSort,
                    asOf = r?.asOf ?: st.asOf,
                    generatedAt = r?.generatedAt ?: st.generatedAt,
                    universeSize = if (r != null) r.universeSize else st.universeSize,
                    scanned = if (r != null) r.scanned else st.scanned,
                    fetchFailed = if (r != null) r.fetchFailed else st.fetchFailed,
                    tooShort = if (r != null) r.tooShort else st.tooShort,
                    universeStale = if (r != null) r.universeStale else st.universeStale,
                    totalMatching = if (r != null) r.totalMatching else st.totalMatching,
                    // Kept in step with the rows it describes: on a FAILED reload the previous rows
                    // stay, and so must the population their ranks were computed over.
                    percentilesOver = if (r != null) r.percentilesOver else st.percentilesOver,
                    note = r?.note ?: st.note,
                    // On a FAILED reload the previous rows stay; wiping their age would make stale
                    // data look freshly loaded.
                    cachedAgeSeconds = when {
                        r?.cached == true -> r.cachedAgeSeconds
                        r != null -> null
                        else -> st.cachedAgeSeconds
                    },
                    error = res.exceptionOrNull()?.let { e ->
                        analystErrorDetail(e) ?: e.message ?: "Couldn't load the market scan."
                    },
                )
            }
            reissueIfPending()
        }
    }

    fun loadBreadth() {
        val before = _state.value
        if (before.breadthLoading) return
        if (!_state.compareAndSet(before, before.copy(breadthLoading = true, breadthError = null))) return
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first().trim()
            if (base.isBlank()) {
                _state.update { it.copy(breadthLoading = false) }
                return@launch
            }
            val res = runCatching { api.marketBreadth(base) }
            val b = res.getOrNull()
            _state.update { st ->
                st.copy(
                    breadthLoading = false,
                    // A breadth payload with available=false is a real answer ("no scan stored"),
                    // so it replaces the old one; only a FAILED call keeps the previous reading.
                    breadth = b ?: st.breadth,
                    breadthError = res.exceptionOrNull()?.let { e ->
                        analystErrorDetail(e) ?: e.message ?: "Couldn't load breadth."
                    },
                )
            }
        }
    }

    /**
     * Run the scan now. Minutes of server work over ~3,100 symbols — user-initiated only, never on a
     * timer, and never from a background worker.
     */
    fun runScan() {
        val before = _state.value
        if (before.running) return
        if (!_state.compareAndSet(before, before.copy(running = true, runError = null))) return
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first().trim()
            if (base.isBlank()) {
                _state.update {
                    it.copy(running = false, configured = false,
                        runError = "Set the Signals service URL in Settings.")
                }
                return@launch
            }
            val res = runCatching { api.runMarketScan(base) }
            val r = res.getOrNull()
            _state.update { st ->
                st.copy(
                    running = false,
                    // "refused"/"empty" are outcomes, not successes — the reason rides along so the
                    // screen can say why instead of implying a fresh scan landed.
                    lastRunStatus = r?.status ?: st.lastRunStatus,
                    lastRunReason = r?.reason,
                    runError = res.exceptionOrNull()?.let { e ->
                        analystErrorDetail(e) ?: e.message ?: "Couldn't start the scan."
                    },
                )
            }
            // Only re-read on a run that actually stored something. Reloading after a refusal would
            // re-render last night's rows immediately under a "just refreshed" impression.
            if (r?.status == "ok") {
                load()
                loadBreadth()
            }
        }
    }

    private fun reissueIfPending() {
        if (!pendingSortSet) return
        val want = pendingSort
        pendingSortSet = false
        pendingSort = null
        if (want == _state.value.sort) load()
    }
}
