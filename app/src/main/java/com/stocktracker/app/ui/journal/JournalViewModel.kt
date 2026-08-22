package com.stocktracker.app.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.app.data.model.JournalReplay
import com.stocktracker.app.data.model.TakenState
import com.stocktracker.app.data.model.VerdictJournalEntry
import com.stocktracker.app.data.remote.HttpStatusException
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
import java.time.LocalDate

/**
 * The verdict journal, screen-side (SWT-8).
 *
 * The entries themselves are LOCAL and always available — the journal is a record of your own
 * decisions and must be readable, editable and above all DECLINABLE with no backend at all. Only the
 * mechanical curve needs the signals service, and its absence subtracts one series from a chart
 * rather than disabling the feature.
 */
data class JournalUiState(
    val entries: List<VerdictJournalEntry> = emptyList(),
    /** True once the store has emitted, so "no verdicts logged" and "not read yet" can differ. */
    val loaded: Boolean = false,
    /** Null until the Signals URL has been read — which is not the same as "not set". */
    val configured: Boolean? = null,
    val replaying: Boolean = false,
    /** Entries finished / attempted in the pass now running, for a progress line that means something. */
    val replayDone: Int = 0,
    val replayTotal: Int = 0,
    /** Set on failure. The replays already recorded stay — they are facts, not a cache. */
    val replayError: String? = null,
) {
    /**
     * Entries a replay could still change.
     *
     * A resolved outcome and a refusal are both FINAL — the bars have already traded, and a plan the
     * server refused will be refused identically forever — so re-asking spends a two-year bar fetch to
     * learn nothing. Only "nothing has traded yet" and "still running" can advance.
     */
    val needingReplay: List<VerdictJournalEntry> get() = entries.filter { needsReplay(it) }
}

/** See [JournalUiState.needingReplay]. Free function so the screen and the pass agree on the rule. */
fun needsReplay(entry: VerdictJournalEntry): Boolean {
    val r = entry.replay ?: return true
    if (r.refused) return false
    return r.outcome == null || r.outcome == JournalReplay.OPEN
}

class JournalViewModel : ViewModel() {

    private val store = ServiceLocator.verdictJournalStore
    private val settings = ServiceLocator.settingsStore
    private val api = SignalsApiService()

    private val _state = MutableStateFlow(JournalUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.entries.collect { entries ->
                _state.update { it.copy(entries = entries, loaded = true) }
            }
        }
        // Reactive: this ViewModel outlives navigation, so a URL entered in Settings after the first
        // visit has to take effect without a process restart.
        viewModelScope.launch {
            settings.signalsApiUrl
                .map { it.trim().isNotBlank() }
                .distinctUntilChanged()
                .collect { configured -> _state.update { it.copy(configured = configured) } }
        }
    }

    // ---------------------------------------------------------------- recording what YOU did

    /**
     * You passed. ONE CALL, no dialog, no confirmation.
     *
     * This is the load-bearing action of the whole feature. If declining is harder than ignoring, the
     * journal only ever records the trades you took: the denominator quietly becomes "verdicts I liked
     * enough to log", and "you passed on 6 of 10" — the one number here that measures your judgement
     * rather than your arithmetic — is silently wrong in the flattering direction.
     */
    fun markDeclined(entry: VerdictJournalEntry) = write(entry.copy(taken = TakenState.NOT_TAKEN))

    /**
     * You took it, at YOUR fill.
     *
     * [fillPrice] and [shares] are nullable because "I took it and I'll fill the numbers in later" is
     * a real state ([com.stocktracker.app.data.model.JournalStatus.TAKEN_UNFILLED]) and is scored as
     * nothing rather than as a fill of zero.
     */
    fun markTaken(
        entry: VerdictJournalEntry,
        fillPrice: Double?,
        shares: Double?,
        fillDateIso: String?,
    ) = write(
        entry.copy(
            taken = TakenState.TAKEN,
            fillPrice = fillPrice,
            shares = shares,
            fillDateIso = fillDateIso?.takeIf { it.isNotBlank() } ?: today(),
        ),
    )

    /** Back to undecided — the fill and exit go with it, since they described a decision you retracted. */
    fun markUndecided(entry: VerdictJournalEntry) = write(
        entry.copy(
            taken = TakenState.UNDECIDED,
            fillPrice = null, shares = null, fillDateIso = null,
            exitPrice = null, exitDateIso = null,
        ),
    )

    /** You sold. The entry becomes CLOSED and can finally carry an R. */
    fun recordExit(entry: VerdictJournalEntry, exitPrice: Double, exitDateIso: String?) = write(
        entry.copy(
            exitPrice = exitPrice,
            exitDateIso = exitDateIso?.takeIf { it.isNotBlank() } ?: today(),
        ),
    )

    fun setNotes(entry: VerdictJournalEntry, notes: String) =
        write(entry.copy(notes = notes.takeIf { it.isNotBlank() }))

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    private fun write(entry: VerdictJournalEntry) {
        viewModelScope.launch { store.update(entry) }
    }

    private fun today(): String = LocalDate.now().toString()

    // ---------------------------------------------------------------- the mechanical leg

    /**
     * Replay every plan whose mechanical outcome could still change.
     *
     * SEQUENTIAL, on purpose. Each call makes the server fetch and walk up to two years of daily bars
     * for one symbol; fanning twenty of those out at once turns a personal journal into a small
     * denial-of-service against the user's own Raspberry Pi. It is user-initiated, never on a timer,
     * for the same reason.
     */
    fun replayPending() {
        val before = _state.value
        if (before.replaying) return
        val todo = before.needingReplay
        if (todo.isEmpty()) return
        if (!_state.compareAndSet(
                before,
                before.copy(replaying = true, replayError = null, replayDone = 0, replayTotal = todo.size),
            )
        ) {
            return // another pass claimed the slot between the read and the write
        }
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first().trim()
            if (base.isBlank()) {
                _state.update {
                    it.copy(
                        replaying = false, configured = false,
                        replayError = "Set the Signals service URL in Settings to replay plans.",
                    )
                }
                return@launch
            }
            val failures = mutableListOf<String>()
            for (entry in todo) {
                // Re-read from the store: the user may have edited or deleted this entry while the
                // pass was walking. Writing the stale copy back with a replay attached would silently
                // undo their edit.
                val current = store.snapshot().firstOrNull { it.id == entry.id } ?: continue
                if (!needsReplay(current)) continue
                val recorded = replayOnce(base, current)
                if (recorded != null) store.update(current.copy(replay = recorded))
                else failures += current.symbol.uppercase()
                _state.update { it.copy(replayDone = it.replayDone + 1) }
            }
            _state.update {
                it.copy(
                    replaying = false,
                    replayError = when {
                        failures.isEmpty() -> null
                        // Named, not counted: "couldn't replay 3" gives the user nothing to act on,
                        // where the symbols say whether it is one dead ticker or the whole service.
                        else -> "Couldn't replay ${failures.distinct().joinToString(", ")} — " +
                            "their plans are unchanged."
                    },
                )
            }
        }
    }

    /** Replay one entry now, whatever state its stored replay is in (the row's own "Replay" action). */
    fun replayOne(entry: VerdictJournalEntry) {
        val before = _state.value
        if (before.replaying) return
        if (!_state.compareAndSet(
                before,
                before.copy(replaying = true, replayError = null, replayDone = 0, replayTotal = 1),
            )
        ) {
            return
        }
        viewModelScope.launch {
            val base = settings.signalsApiUrl.first().trim()
            if (base.isBlank()) {
                _state.update {
                    it.copy(
                        replaying = false, configured = false,
                        replayError = "Set the Signals service URL in Settings to replay plans.",
                    )
                }
                return@launch
            }
            // Re-read: the row the user tapped may have been edited or deleted while the dialog was
            // open, and writing the stale copy back with a replay attached would undo their edit.
            val current = store.snapshot().firstOrNull { it.id == entry.id }
            val recorded = current?.let { replayOnce(base, it) }
            if (current != null && recorded != null) store.update(current.copy(replay = recorded))
            _state.update {
                it.copy(
                    replaying = false, replayDone = 1,
                    replayError = if (recorded == null) {
                        "Couldn't replay ${entry.symbol.uppercase()} — its plan is unchanged."
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * One replay, or null when nothing should be recorded.
     *
     * A 422 IS RECORDED, as a refusal. It means the plan itself cannot be replayed — an inverted zone,
     * a stop that is not below the entry, a date in the future — which is a permanent fact about what
     * was snapshotted, and storing it stops every later pass from re-asking a question with a settled
     * answer. A 404 (no price history) or a 502/timeout is NOT recorded: those are facts about the
     * vendor and the network, they can come right tomorrow, and writing one into the journal would
     * freeze a transient outage into the entry's permanent record.
     */
    private suspend fun replayOnce(base: String, entry: VerdictJournalEntry): JournalReplay? {
        val now = System.currentTimeMillis()
        @Suppress("SwallowedException")
        return try {
            api.journalReplay(
                baseUrl = base,
                symbol = entry.symbol,
                dateIso = entry.verdictDateIso,
                entryLow = entry.plan.entryLow,
                entryHigh = entry.plan.entryHigh,
                stop = entry.plan.stop,
                target = entry.plan.target,
            ).copy(replayedAtMs = now)
        } catch (e: HttpStatusException) {
            if (e.code == 422) {
                JournalReplay(
                    refused = true,
                    reason = analystErrorDetail(e) ?: "the server could not replay this plan",
                    replayedAtMs = now,
                )
            } else {
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never swallowed into "couldn't replay": the pass was cancelled (the screen left), which
            // is not a failure of this entry and must not stop the coroutine machinery unwinding.
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
