package com.stocktracker.app.data

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.model.SearchResult
import com.stocktracker.app.data.model.VixQuote
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
    // Stocks now work with no key (quotes + search come from Yahoo); Finnhub is an optional extra.
    val stocksEnabled: Boolean get() = true

    // Short in-memory TTL cache: coalesces the watchlist + portfolio + widget calls that would
    // otherwise hammer the APIs (and hit 429 rate limits) when switching tabs / refreshing.
    private class Entry(val at: Long, val value: Any?, val empty: Boolean)
    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Memoize [compute] under [key], but keep a transient failure from turning into a persistent
     * "no data":
     *  - a successful, non-empty result is cached for the full [ttlMs];
     *  - an *empty* result is cached for only [NEGATIVE_TTL], so one bad fetch can't pin a blank
     *    chart on screen for minutes (the default chart range's TTL is 5 min);
     *  - a *thrown* fetch serves the last good value if we have one (stale-while-error) rather than
     *    surfacing the failure — this is what carries the UI through a one-off Yahoo/CoinGecko 429.
     */
    private suspend fun <T> cached(key: String, ttlMs: Long, compute: suspend () -> T): T {
        val now = System.currentTimeMillis()
        cache[key]?.let { e ->
            val ttl = if (e.empty) NEGATIVE_TTL else ttlMs
            if (now - e.at < ttl) { @Suppress("UNCHECKED_CAST") return e.value as T }
        }
        val value = try {
            compute()
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce // never swallow cancellation — that would break structured concurrency
        } catch (t: Throwable) {
            val stale = cache[key]
            if (stale != null && !stale.empty) { @Suppress("UNCHECKED_CAST") return stale.value as T }
            throw t
        }
        cache[key] = Entry(System.currentTimeMillis(), value, isEmptyResult(value))
        return value
    }

    /** Treats null / empty collection / empty map as "empty" so those get only the negative TTL. */
    private fun isEmptyResult(value: Any?): Boolean = when (value) {
        null -> true
        is Collection<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        else -> false
    }

    suspend fun quote(asset: Asset): Quote = cached("q:${asset.id}", QUOTE_TTL) {
        when (asset.type) {
            // Yahoo (no key) is primary; fall back to Finnhub only if a key is set and Yahoo missed.
            AssetType.STOCK -> yahoo.quote(asset.symbol)
                ?: if (finnhub.hasKey) finnhub.quote(asset.symbol)
                else throw java.io.IOException("No quote for ${asset.symbol}")
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
                AssetType.CRYPTO -> {
                    // Crypto charts come from Yahoo (<TICKER>-USD) for EVERY range. CoinGecko's
                    // keyless free API rate-limits (429) aggressively, which was timing out the chart
                    // on whichever timeframes hit the limit; Yahoo is fast and unthrottled and covers
                    // intraday → weekly. Fall back to CoinGecko only when Yahoo has nothing for the
                    // coin (obscure alts don't all list as <TICKER>-USD).
                    val yh = try {
                        yahoo.cryptoHistory(asset.symbol, range)
                    } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    yh.ifEmpty {
                        coinGecko.history(asset.coinGeckoId ?: asset.symbol.lowercase(), range.toCoinGeckoDays())
                    }
                }
                AssetType.STOCK -> yahoo.history(asset.symbol, range, includeExtended)
            }
        }
    }

    /** 52-week high/low. Stocks use Yahoo's meta; crypto derives from 1Y history. */
    suspend fun fiftyTwoWeek(asset: Asset): Pair<Double, Double>? = cached("52:${asset.id}", HISTORY_TTL) {
        when (asset.type) {
            AssetType.STOCK -> yahoo.fiftyTwoWeek(asset.symbol)
            AssetType.CRYPTO -> {
                val year = history(asset, ChartRange.YEAR)
                val hi = year.maxOfOrNull { it.price }
                val lo = year.minOfOrNull { it.price }
                if (hi != null && lo != null) hi to lo else null
            }
        }
    }

    /** Current market-volatility index (^VIX), for the dashboard fear gauge. Yahoo-only. */
    suspend fun vix(): VixQuote? = cached("vix", QUOTE_TTL) { yahoo.vix() }

    /** Ex-dividend dates (epochMs → amount) within [range] for a stock; empty for crypto. */
    suspend fun dividends(asset: Asset, range: ChartRange): List<Pair<Long, Double>> =
        if (asset.type != AssetType.STOCK) emptyList()
        else cached("div:${asset.id}:$range", HISTORY_TTL) { runCatching { yahoo.dividends(asset.symbol, range) }.getOrDefault(emptyList()) }

    /** S&P 500 (^GSPC) price history for the benchmark comparison overlay. */
    suspend fun benchmark(range: ChartRange): List<PricePoint> =
        cached("bench:$range", HISTORY_TTL) { runCatching { yahoo.history("^GSPC", range) }.getOrDefault(emptyList()) }

    suspend fun search(query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        // Yahoo needs no key; supplement with Finnhub (warrants/odd tickers) when a key is present.
        val yahooHits = runCatching { yahoo.search(query) }.getOrDefault(emptyList())
        val finnhubHits = runCatching { if (finnhub.hasKey) finnhub.search(query) else emptyList() }
            .getOrDefault(emptyList())
        val stocks = (yahooHits + finnhubHits)
            .distinctBy { it.symbol.uppercase() }
            .take(15)
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
        const val NEGATIVE_TTL = 10_000L // empty/failed lookups expire fast so a retry actually retries
    }
}

private fun ChartRange.toCoinGeckoDays(): String = when (this) {
    ChartRange.DAY -> "1"
    ChartRange.WEEK -> "7"
    ChartRange.MONTH -> "30"
    ChartRange.QUARTER -> "90"
    ChartRange.YEAR -> "365"
    ChartRange.THREE_YEAR -> "1095"
    ChartRange.ALL -> "max"
}
