package com.stocktracker.app.data.remote

import android.os.SystemClock
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Reachability of the self-hosted Signals backend. */
enum class BackendState {
    /** No Signals URL saved — the service is simply not in use, which is not an error and must never
     *  surface as one. (The AI master switch is NOT part of this: it pauses Claude calls, but the
     *  service still serves free non-LLM data and the sandbox.) */
    NOT_CONFIGURED,

    /** Reachable as of [SignalsHealthState.lastOkAt]. */
    ONLINE,

    /** Configured but the last probe or call failed — AI features will not work right now. */
    OFFLINE,

    /** Configured, nothing tried yet. */
    UNKNOWN,
}

data class SignalsHealthState(
    val state: BackendState = BackendState.UNKNOWN,
    val lastOkAt: Long = 0L,          // epoch ms of the last success (0 = never) — for display only
    val lastError: String? = null,    // short human-readable reason for the last failure
    val checking: Boolean = false,    // a USER-initiated retry is in flight (not the background poll)
) {
    val isOffline: Boolean get() = state == BackendState.OFFLINE
    val isConfigured: Boolean get() = state != BackendState.NOT_CONFIGURED
}

/**
 * Single source of truth for "can we reach the Signals service right now?".
 *
 * The phone is often on a different network than the homelab (mobile data, away from home, VPN off), so
 * every AI feature can fail for a completely benign reason. Previously each call just returned null and
 * the screens showed nothing, which read like the feature was broken.
 *
 * Probes the cheap `/health` endpoint and exposes one StateFlow the UI renders a single banner from.
 * Real API calls also report their outcome via [reportSuccess]/[reportFailure] so status reflects
 * actual usage without waiting for a poll.
 */
object SignalsHealth {

    private const val PROBE_TIMEOUT_MS = 6_000L
    private const val POLL_WHEN_OFFLINE_MS = 30_000L   // retry quickly while down so recovery is fast
    private const val POLL_WHEN_ONLINE_MS = 5 * 60_000L

    /** A failure reported within this long after a success is treated as a lost race, not new news.
     *  Calls run concurrently and the analyst client allows 180s, so a slow request can land right
     *  after a fast one succeeded; the next poll re-checks anyway, so a short window is safe. */
    private const val CONCURRENT_WINDOW_MS = 2_000L

    private val _state = MutableStateFlow(SignalsHealthState())
    val state = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    /** Nudges the poll loop awake. Without this, an OFFLINE flip reported by a real API call would sit
     *  until the loop's (up to 5 minute) ONLINE-cadence sleep expired before the fast 30s retry engaged. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** How many user-initiated retries are in flight. A plain boolean let the background poll clear a
     *  user's spinner (and vice-versa) whenever the two overlapped. */
    private val manualProbes = AtomicInteger(0)

    /**
     * Ordering clock for "which observation is newer".
     *
     * Deliberately [SystemClock.elapsedRealtime] and not the wall clock: `System.currentTimeMillis()`
     * jumps backwards on NTP correction after a bad RTC, on a carrier time push, or when the user edits
     * the date. A backwards jump left the previous wall-clock guard permanently satisfied, which pinned
     * the state to ONLINE for the rest of the process's life and silently suppressed the banner.
     */
    @Volatile
    private var lastOkElapsed = Long.MIN_VALUE

    /** Start the background poll. Idempotent — a second call is a no-op rather than a second loop. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                runCatching { check(manual = false) }   // a probe crash must never kill the loop
                val naptime = if (_state.value.isOffline) POLL_WHEN_OFFLINE_MS else POLL_WHEN_ONLINE_MS
                // Drain first. `wake` is CONFLATED, so a trySend with no receiver parked BUFFERS the
                // element — and check() itself signals it on every failure. Without this drain the
                // select below finds a wake already pending, returns instantly, probes, fails, re-arms,
                // and the loop spins with no delay at all: measured at ~11M iterations in 3 seconds
                // against a refused connection, burning a core for as long as the backend is down.
                while (wake.tryReceive().isSuccess) { /* discard stale wakes */ }
                select<Unit> {
                    wake.onReceive { }             // a failure was reported while we slept — re-probe now
                    onTimeout(naptime) { }
                }
            }
        }
        // React to configuration changes instead of waiting out a 5-minute nap. Saving a URL used to
        // leave the state at NOT_CONFIGURED, and reportFailure discards everything in that state, so a
        // freshly-typed wrong URL could not report offline until the next poll happened to run.
        scope.launch {
            val s = ServiceLocator.settingsStore
            combine(s.signalsApiUrl, s.aiAnalystEnabled) { url, ai -> url to ai }
                .distinctUntilChanged()
                .drop(1)   // the loop above already probes the initial config — don't double up
                .collect { runCatching { check(manual = false) } }
        }
    }

    /**
     * Probe `/health` once and update the state. Returns true when reachable.
     *
     * [manual] marks a user-initiated retry: only those raise `checking`, so the background poll can't
     * spontaneously flip the banner to a spinner or steal its tap target.
     */
    suspend fun check(manual: Boolean = true): Boolean {
        val settings = ServiceLocator.settingsStore
        val base = runCatching { settings.signalsApiUrl.first() }.getOrDefault("")
        // Gated on the URL ALONE, deliberately. Tying this to the AI master switch was wrong: that
        // switch only pauses Claude calls to save tokens, while the service also serves a lot of
        // free, non-LLM data (short interest, seasonality, quality, movers, the whole sandbox). With
        // AI off, an outage in any of those became invisible. Screens where AI-off is the relevant
        // explanation suppress the banner themselves — health answers one question, and it is
        // "can we reach the service", not "should we be calling it".
        if (base.isBlank()) {
            _state.update { it.copy(state = BackendState.NOT_CONFIGURED, lastError = null) }
            return false
        }
        // Leaving NOT_CONFIGURED behind the moment a URL exists is what lets reportFailure work at all.
        _state.update {
            if (it.state == BackendState.NOT_CONFIGURED) it.copy(state = BackendState.UNKNOWN) else it
        }
        if (manual && manualProbes.incrementAndGet() == 1) _state.update { it.copy(checking = true) }
        try {
            // Keep the REAL failure: a synthetic exception loses the cause AND defeats the
            // HttpStatusException carve-out below (a backend that 404s /health is still reachable).
            val result: Result<String>? = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                runCatching { Http.getString("${base.trimEnd('/')}/health") }
            }
            return when {
                result == null -> { reportFailure(IOException("timed out")); false }
                result.isSuccess -> { reportSuccess(); true }
                else -> {
                    val e = result.exceptionOrNull()
                    reportFailure(e)
                    // A 4xx answer proves the service is alive — reportFailure treats it as reachable,
                    // so mirror that verdict. A 502/504 does NOT: that is usually the reverse proxy
                    // reporting it cannot reach the upstream.
                    e is HttpStatusException && !e.meansBackendDown
                }
            }
        } finally {
            // Without this, a cancellation mid-probe (e.g. the composition that launched it is disposed)
            // leaves `checking` stuck true, which permanently disables tap-to-retry.
            if (manual && manualProbes.decrementAndGet() == 0) _state.update { it.copy(checking = false) }
        }
    }

    /** Called by the API wrappers after a successful request — cheaper and more current than a poll. */
    fun reportSuccess() {
        lastOkElapsed = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(state = BackendState.ONLINE, lastOkAt = System.currentTimeMillis(), lastError = null)
        }
    }

    /**
     * Called by the API wrappers when a request fails.
     *
     * - A [CancellationException] is NOT a connectivity failure — it means the caller went away (user
     *   navigated off a screen mid-request). Reporting it would flash a false "offline".
     * - An [HttpStatusException] proves the service IS reachable; a 502 from the analyst is a
     *   feature-level problem, so it must not flip the banner.
     * - Otherwise the newest observation wins. An earlier design compared this failure's START time to
     *   the last success's COMPLETION time, which are not comparable quantities: a 240s analyst call
     *   that began before a 1s call succeeded would be thrown away even though its failure is the
     *   strictly later evidence, leaving the banner up to 5 minutes stale on exactly the screens where
     *   the long call just died.
     */
    fun reportFailure(e: Throwable?) {
        if (e is CancellationException) return
        if (e is HttpStatusException && !e.meansBackendDown) {
            // A 4xx proves the SERVICE answered — a 404 on an endpoint, or a 422 from the analyst, is
            // a feature-level problem, not unreachability, so it must not flip the banner.
            reportSuccess()
            return
        }
        if (_state.value.state == BackendState.NOT_CONFIGURED) return
        if (SystemClock.elapsedRealtime() - lastOkElapsed < CONCURRENT_WINDOW_MS) return
        _state.update { it.copy(state = BackendState.OFFLINE, lastError = shortReason(e)) }
        wake.trySend(Unit)                      // engage the fast retry cadence immediately
    }

    private fun shortReason(e: Throwable?): String = when {
        e == null -> "unreachable"
        e is java.net.SocketTimeoutException -> "timed out"
        e is java.net.UnknownHostException -> "host not found — check the URL or your network"
        e is java.net.ConnectException -> "connection refused — is the service running?"
        e is IOException -> e.message?.take(90) ?: "network error"
        else -> e.message?.take(90) ?: "unreachable"
    }
}
