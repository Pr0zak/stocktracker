package com.stocktracker.app.data

import com.stocktracker.app.data.model.VixQuote
import com.stocktracker.app.data.remote.ScanLatest
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.watchlist.DipRadar
import com.stocktracker.app.ui.watchlist.DipRadarState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the whole app currently believes about the market, in one place.
 *
 * Three screens were each holding their own copy of this. The watchlist's strip fetched the nightly
 * scan into its view model; the dip radar screen fetched the same scan again into a `remember` of
 * its own; and the Markets hub, which is supposed to be the front door to both, held nothing and so
 * could only describe its rows in the abstract. The comment on the strip's state even says "one
 * state machine, shared — two would drift and the two screens would eventually disagree about
 * whether the market is calm", which was true of the TYPE and not of the INSTANCE: there were two
 * instances, fetching separately, and they could and did disagree.
 *
 * This is the instance. It outlives any one screen (it hangs off the service locator, not a
 * view-model scope), so opening the radar after glancing at the strip costs nothing and shows the
 * same reading, and the Markets rows can say a true thing about what is behind each door.
 */
class MarketContextStore(private val scope: CoroutineScope) {

    /**
     * @property scan the raw payload, kept so callers can read fields this class has no opinion
     *   about (the 200-week flags, the date alerts) without a second fetch.
     * @property dipStale set when a refresh FAILED and [dipRadar] is therefore the previous,
     *   still-displayed scan — the rule lives in [DipRadar.holdThroughBlip].
     * @property vixFailed true when the last VIX fetch reached nothing. Separate from a null [vix],
     *   which also covers "never asked": an absent gauge and a failed one are different sentences.
     */
    data class State(
        val dipRadar: DipRadarState = DipRadarState.Loading,
        val dipStale: String? = null,
        val scan: ScanLatest? = null,
        val scanFetchedAtMs: Long = 0L,
        val vix: VixQuote? = null,
        val vixFailed: Boolean = false,
        val vixFetchedAtMs: Long = 0L,
    ) {
        /** Symbol (scan form) → below its 200-week line. Empty until a real scan has landed. */
        val belowLine: Map<String, Boolean>
            get() = scan?.results.orEmpty()
                .mapNotNull { r -> r.below200wma?.let { r.symbol.uppercase() to it } }
                .toMap()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val signalsApi = SignalsApiService()
    private val scanLock = Mutex()
    private val vixLock = Mutex()

    /**
     * Fetch the nightly scan, unless a recent enough one is already held.
     *
     * The nightly scan changes once a day; re-fetching it every time a screen opens buys nothing and
     * costs the self-hosted service a burst. [maxAgeMs] is what "recent enough" means, and a forced
     * refresh passes zero.
     */
    fun refreshScan(maxAgeMs: Long = SCAN_TTL_MS) {
        scope.launch {
            scanLock.withLock {
                val held = _state.value
                if (held.scanFetchedAtMs > 0 &&
                    System.currentTimeMillis() - held.scanFetchedAtMs < maxAgeMs
                ) {
                    return@withLock
                }
                val base = ServiceLocator.settingsStore.signalsApiUrl.first()
                val configured = base.isNotBlank()
                val res = if (configured) {
                    runCatching { signalsApi.latestScan(base) }
                } else {
                    Result.success(null)
                }
                val fresh = DipRadar.state(
                    scan = res.getOrNull(),
                    error = res.exceptionOrNull(),
                    configured = configured,
                )
                _state.update { st ->
                    // Keep a good scan through a blip — the rule is in DipRadar.holdThroughBlip so
                    // it stays testable and so the reasons NotConfigured and NoScan are excluded
                    // from it are written down beside the rule rather than here.
                    val upd = DipRadar.holdThroughBlip(st.dipRadar, fresh)
                    st.copy(
                        dipRadar = upd.state,
                        dipStale = upd.stale,
                        // A failed fetch must not clear a scan we already hold and are still
                        // showing. Same reason the 200-week flags survived a blip before this.
                        scan = res.getOrNull()?.takeIf { it.hasScan } ?: st.scan,
                        scanFetchedAtMs = if (res.isSuccess) System.currentTimeMillis() else st.scanFetchedAtMs,
                    )
                }
            }
        }
    }

    /** The current VIX, for the fear gauge and the Markets row that leads to it. */
    fun refreshVix(maxAgeMs: Long = VIX_TTL_MS) {
        scope.launch {
            vixLock.withLock {
                val held = _state.value
                if (held.vix != null &&
                    System.currentTimeMillis() - held.vixFetchedAtMs < maxAgeMs
                ) {
                    return@withLock
                }
                val res = runCatching { ServiceLocator.repository.vix() }
                val q = res.getOrNull()
                _state.update {
                    it.copy(
                        // Keep the last good reading; only the flag changes on a failure, so the
                        // gauge shows a number with an age rather than going blank.
                        vix = q ?: it.vix,
                        vixFailed = q == null,
                        vixFetchedAtMs = if (q != null) System.currentTimeMillis() else it.vixFetchedAtMs,
                    )
                }
            }
        }
    }

    private companion object {
        /** The scan runs nightly; half an hour is plenty fresh and spares the service a burst. */
        const val SCAN_TTL_MS = 30 * 60 * 1000L
        /** The VIX moves all session. Two minutes, the same order as the watchlist's own prices. */
        const val VIX_TTL_MS = 2 * 60 * 1000L
    }
}
