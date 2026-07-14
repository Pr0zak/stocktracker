package com.stocktracker.app.data

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.model.SearchResult
import com.stocktracker.app.data.remote.CoinGeckoService
import com.stocktracker.app.data.remote.CoinMarket
import com.stocktracker.app.data.remote.FinnhubService
import com.stocktracker.app.data.remote.YahooFinanceService
import java.util.concurrent.ConcurrentHashMap

/** Single entry point the app + widgets use to read market data. */
class MarketRepository(
    private val finnhub: FinnhubService,
    private val coinGecko: CoinGeckoService,
    private val yahoo: YahooFinanceService,
) {
    val stocksEnabled: Boolean get() = finnhub.hasKey

    // Short in-memory TTL cache: coalesces the watchlist + portfolio + widget calls that would
    // otherwise hammer the APIs (and hit 429 rate limits) when switching tabs / refreshing.
    private class Entry(val at: Long, val value: Any?)
    private val cache = ConcurrentHashMap<String, Entry>()

    private suspend fun <T> cached(key: String, ttlMs: Long, compute: suspend () -> T): T {
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now - it.at < ttlMs) { @Suppress("UNCHECKED_CAST") return it.value as T } }
        val value = compute()
        cache[key] = Entry(System.currentTimeMillis(), value)
        return value
    }

    suspend fun quote(asset: Asset): Quote = cached("q:${asset.id}", QUOTE_TTL) {
        when (asset.type) {
            AssetType.STOCK -> finnhub.quote(asset.symbol)
            AssetType.CRYPTO -> coinGecko.quote(asset.coinGeckoId ?: asset.symbol.lowercase(), asset.symbol)
        }
    }

    /** Batched crypto market data (price + change + 7d sparkline) in one call. */
    suspend fun cryptoMarkets(assets: List<Asset>): Map<String, CoinMarket> {
        val ids = assets.filter { it.type == AssetType.CRYPTO }.mapNotNull { it.coinGeckoId }
        if (ids.isEmpty()) return emptyMap()
        return cached("m:${ids.sorted().joinToString(",")}", QUOTE_TTL) {
            coinGecko.markets(ids).associateBy { it.id }
        }
    }

    suspend fun history(
        asset: Asset,
        range: ChartRange,
        includeExtended: Boolean = false,
    ): List<PricePoint> {
        // Intraday moves more; longer ranges are essentially fixed for the session.
        val ttl = if (range == ChartRange.DAY || range == ChartRange.WEEK) INTRADAY_HISTORY_TTL else HISTORY_TTL
        return cached("h:${asset.id}:$range:$includeExtended", ttl) {
            when (asset.type) {
                AssetType.CRYPTO ->
                    coinGecko.history(asset.coinGeckoId ?: asset.symbol.lowercase(), range.toCoinGeckoDays())
                AssetType.STOCK -> yahoo.history(asset.symbol, range, includeExtended)
            }
        }
    }

    suspend fun search(query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val stocks = runCatching { if (finnhub.hasKey) finnhub.search(query) else emptyList() }
            .getOrDefault(emptyList()).take(15)
        val crypto = runCatching { coinGecko.search(query) }.getOrDefault(emptyList()).take(15)
        return interleave(stocks, crypto)
    }

    private fun interleave(a: List<SearchResult>, b: List<SearchResult>): List<SearchResult> {
        val out = ArrayList<SearchResult>(a.size + b.size)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            if (i < a.size) out.add(a[i])
            if (i < b.size) out.add(b[i])
        }
        return out
    }

    private companion object {
        const val QUOTE_TTL = 15_000L
        const val INTRADAY_HISTORY_TTL = 60_000L
        const val HISTORY_TTL = 5 * 60_000L
    }
}

private fun ChartRange.toCoinGeckoDays(): String = when (this) {
    ChartRange.DAY -> "1"
    ChartRange.WEEK -> "7"
    ChartRange.MONTH -> "30"
    ChartRange.QUARTER -> "90"
    ChartRange.YEAR -> "365"
    ChartRange.ALL -> "max"
}
