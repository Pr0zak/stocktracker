package com.stocktracker.app.di

import android.content.Context
import com.stocktracker.app.BuildConfig
import com.stocktracker.app.data.MarketContextStore
import com.stocktracker.app.data.MarketRepository
import com.stocktracker.app.data.prefs.CallPositionStore
import com.stocktracker.app.data.prefs.ClosedCallPositionStore
import com.stocktracker.app.data.prefs.PriceCache
import com.stocktracker.app.data.prefs.SectorCache
import com.stocktracker.app.data.prefs.SettingsStore
import com.stocktracker.app.data.prefs.VerdictJournalStore
import com.stocktracker.app.data.prefs.WatchlistStore
import com.stocktracker.app.data.remote.CoinGeckoService
import com.stocktracker.app.data.remote.FinnhubService
import com.stocktracker.app.data.remote.YahooFinanceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Tiny manual DI container. Initialized in [com.stocktracker.app.StockTrackerApp]. */
object ServiceLocator {

    lateinit var repository: MarketRepository
        private set
    lateinit var watchlistStore: WatchlistStore
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var priceCache: PriceCache
    lateinit var sectorCache: SectorCache
        private set
    lateinit var callPositionStore: CallPositionStore
        private set
    lateinit var closedCallPositionStore: ClosedCallPositionStore
        private set
    lateinit var verdictJournalStore: VerdictJournalStore
        private set

    /**
     * What the app believes about the market, shared by every screen that shows any of it.
     *
     * Lives here rather than in a view model because three screens need the same reading and a
     * view-model-scoped copy dies with its screen — which is how the watchlist strip and the dip
     * radar ended up fetching the same nightly scan twice and being able to disagree about it.
     */
    lateinit var marketContext: MarketContextStore
        private set

    /** User-entered Finnhub key from Settings; blank means use the build-time key. */
    @Volatile
    var finnhubKeyOverride: String = ""

    /** The key actually used for requests: in-app override wins, else the build-time key. */
    val effectiveFinnhubKey: String
        get() = finnhubKeyOverride.ifBlank { BuildConfig.FINNHUB_API_KEY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        marketContext = MarketContextStore(scope)
        watchlistStore = WatchlistStore(app)
        settingsStore = SettingsStore(app)
        priceCache = PriceCache(app)
        sectorCache = SectorCache(app)
        callPositionStore = CallPositionStore(app)
        closedCallPositionStore = ClosedCallPositionStore(app)
        verdictJournalStore = VerdictJournalStore(app)
        // Poll the self-hosted backend so the UI can show one clear "unreachable" banner rather than
        // every AI feature failing silently when the phone is off the home network.
        com.stocktracker.app.data.remote.SignalsHealth.start()

        // Seed the override synchronously so the first refresh (incl. a widget worker on cold
        // start) already has the persisted key, then keep it in sync with Settings.
        finnhubKeyOverride = runBlocking { runCatching { settingsStore.finnhubApiKey.first() }.getOrDefault("") }
        scope.launch { settingsStore.finnhubApiKey.collect { finnhubKeyOverride = it } }

        repository = MarketRepository(
            finnhub = FinnhubService { effectiveFinnhubKey },
            coinGecko = CoinGeckoService(),
            yahoo = YahooFinanceService(),
        )
        initialized = true
    }
}
