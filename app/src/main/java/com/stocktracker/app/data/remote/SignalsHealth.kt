package com.stocktracker.app.data.remote

import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Reachability of the self-hosted Signals backend. */
enum class BackendState {
    /** No Signals URL saved in Settings — the AI features are simply off, not broken. */
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
    val lastOkAt: Long = 0L,          // epoch ms of the last success (0 = never)
    val lastError: String? = null,    // short human-readable reason for the last failure
    val checking: Boolean = false,
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

    private val _state = MutableStateFlow(SignalsHealthState())
    val state = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    /** Nudges the poll loop awake. Without this, an OFFLINE flip reported by a real API call would sit
     *  until the loop's (up to 5 minute) ONLINE-cadence sleep expired before the fast 30s retry engaged. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Start the background poll. Idempotent — a second call is a no-op rather than a second loop. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                runCatching { check() }   // a probe crash must never kill the loop
                val naptime = if (_state.value.isOffline) POLL_WHEN_OFFLINE_MS else POLL_WHEN_ONLINE_MS
                select<Unit> {
                    wake.onReceive { }             // a failure was just reported — re-probe now
                    onTimeout(naptime) { }
                }
            }
        }
    }

    /** Probe `/health` once and update the state. Returns true when reachable. */
    suspend fun check(): Boolean {
        val base = runCatching { ServiceLocator.settingsStore.signalsApiUrl.first() }.getOrDefault("")
        if (base.isBlank()) {
            _state.update { it.copy(state = BackendState.NOT_CONFIGURED, lastError = null, checking = false) }
            return false
        }
        val startedAt = System.currentTimeMillis()
        _state.update { it.copy(checking = true) }
        try {
            // Keep the REAL failure: a synthetic exception loses the cause AND defeats the
            // HttpStatusException carve-out below (a backend that 404s /health is still reachable).
            val result: Result<String>? = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                runCatching { Http.getString("${base.trimEnd('/')}/health") }
            }
            return when {
                result == null -> { reportFailure(IOException("timed out"), startedAt); false }
                result.isSuccess -> { reportSuccess(); true }
                else -> {
                    val e = result.exceptionOrNull()
                    reportFailure(e, startedAt)
                    // An HTTP response (even 404) proves something answered — reportFailure treats that
                    // as reachable, so mirror its verdict rather than hard-coding false.
                    e is HttpStatusException
                }
            }
        } finally {
            // Without this, a cancellation mid-probe (e.g. the composition that launched it is disposed)
            // leaves `checking` stuck true, which permanently disables tap-to-retry.
            _state.update { it.copy(checking = false) }
        }
    }

    /** Called by the API wrappers after a successful request — cheaper and more current than a poll. */
    fun reportSuccess() {
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
     * - [observedAt] guards against a slow in-flight call (the analyst client allows 180s) landing after
     *   newer evidence and overwriting a more recent success.
     */
    fun reportFailure(e: Throwable?, observedAt: Long = System.currentTimeMillis()) {
        if (e is CancellationException) return
        if (e is HttpStatusException) {
            reportSuccess()
            return
        }
        val cur = _state.value
        if (cur.state == BackendState.NOT_CONFIGURED) return
        if (cur.lastOkAt > observedAt) return   // a success landed after this request began — ignore
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
