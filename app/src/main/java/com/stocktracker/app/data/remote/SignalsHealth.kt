package com.stocktracker.app.data.remote

import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

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
 * the screens showed nothing, or a per-card message, which read like the feature was broken.
 *
 * This probes the cheap `/health` endpoint (no LLM, no market data) and exposes one [StateFlow] the UI
 * can render a single, consistent banner from. Real API calls also report their outcome here via
 * [reportSuccess]/[reportFailure], so the status reflects actual usage without waiting for a poll.
 */
object SignalsHealth {

    private const val PROBE_TIMEOUT_MS = 6_000L
    private const val POLL_WHEN_OFFLINE_MS = 30_000L   // retry quickly while down so recovery is fast
    private const val POLL_WHEN_ONLINE_MS = 5 * 60_000L

    private val _state = MutableStateFlow(SignalsHealthState())
    val state = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Start the background poll. Safe to call once from app startup. */
    fun start() {
        scope.launch {
            while (true) {
                check()
                delay(if (_state.value.isOffline) POLL_WHEN_OFFLINE_MS else POLL_WHEN_ONLINE_MS)
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
        _state.update { it.copy(checking = true) }
        val ok = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching { Http.getString("${base.trimEnd('/')}/health") }.isSuccess
        } ?: false
        if (ok) reportSuccess() else reportFailure(IOException("no response from $base"))
        _state.update { it.copy(checking = false) }
        return ok
    }

    /** Called by API wrappers after a successful request — cheaper and more current than a poll. */
    fun reportSuccess() {
        _state.update {
            it.copy(state = BackendState.ONLINE, lastOkAt = System.currentTimeMillis(), lastError = null)
        }
    }

    /**
     * Called by API wrappers when a request fails. Only *transport* failures mean "offline" — an HTTP
     * error (e.g. a 502 from the analyst, or a 422) proves the service IS reachable and is a feature-level
     * problem, so it must not flip the banner to offline.
     */
    fun reportFailure(e: Throwable?) {
        if (e is HttpStatusException) {
            reportSuccess()   // we got a response, so the backend is up
            return
        }
        val base = _state.value
        if (base.state == BackendState.NOT_CONFIGURED) return
        _state.update {
            it.copy(state = BackendState.OFFLINE, lastError = shortReason(e))
        }
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
