package com.stocktracker.app.di

import android.content.Context
import com.stocktracker.app.BuildConfig
import com.stocktracker.app.data.MarketRepository
import com.stocktracker.app.data.prefs.PriceCache
import com.stocktracker.app.data.prefs.SettingsStore
import com.stocktracker.app.data.prefs.WatchlistStore
import com.stocktracker.app.data.remote.CoinGeckoService
import com.stocktracker.app.data.remote.FinnhubService
import com.stocktracker.app.data.remote.YahooFinanceService

/** Tiny manual DI container. Initialized in [com.stocktracker.app.StockTrackerApp]. */
object ServiceLocator {

    lateinit var repository: MarketRepository
        private set
    lateinit var watchlistStore: WatchlistStore
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var priceCache: PriceCache
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        repository = MarketRepository(
            finnhub = FinnhubService(BuildConfig.FINNHUB_API_KEY),
            coinGecko = CoinGeckoService(),
            yahoo = YahooFinanceService(),
        )
        watchlistStore = WatchlistStore(app)
        settingsStore = SettingsStore(app)
        priceCache = PriceCache(app)
        initialized = true
    }
}
