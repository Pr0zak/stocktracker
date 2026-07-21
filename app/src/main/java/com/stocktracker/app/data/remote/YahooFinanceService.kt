package com.stocktracker.app.data.remote

import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.model.SearchResult
import com.stocktracker.app.data.model.VixQuote
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/**
 * Yahoo Finance chart endpoint — free, no key, supports intraday + pre/post-market.
 * Used for stock history (Finnhub free has no candles; Stooq blocks non-browser clients).
 * Unofficial endpoint; failures degrade to an empty chart.
 */
class YahooFinanceService {

    /**
     * Yahoo's ticker form: share-class / warrant symbols use a DASH, not a dot (BRK-B not BRK.B,
     * GME-WS not GME.WS), and index tickers carry a caret (^VIX) that must be URL-encoded. Watchlist
     * symbols are often stored dot-form (Finnhub's convention), so normalize before every call.
     */
    private fun yahooSymbol(symbol: String): String =
        symbol.uppercase().replace('.', '-').replace("^", "%5E")

    /**
     * @param includeExtended fetch + flag pre/post-market points (only meaningful for the 1D view).
     */
    suspend fun history(
        symbol: String,
        range: ChartRange,
        includeExtended: Boolean = false,
    ): List<PricePoint> {
        // Pre/post-market is only meaningful (and returned) for the intraday views.
        val prePost = includeExtended && (range == ChartRange.DAY || range == ChartRange.WEEK)
        // Index tickers carry a caret (^VIX); pre-encode it so it survives URL construction.
        val enc = yahooSymbol(symbol)
        val path = "v8/finance/chart/$enc?${rangeParams(range)}&includePrePost=$prePost"
        val result = fetchChart(path).chart.result?.firstOrNull() ?: return emptyList()
        val timestamps = result.timestamp ?: return emptyList()
        val quote0 = result.indicators?.quote?.firstOrNull()
        val closes = quote0?.close ?: return emptyList()
        val volumes = quote0.volume

        // Classify each point by its time-of-day in the exchange timezone. Regular session =
        // 09:30–16:00; anything else within the returned data is pre/post-market. Using a real
        // ZoneId per timestamp keeps this correct across a DST transition within the 1W view.
        val zone = result.meta?.exchangeTimezoneName?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: EXCHANGE_ZONE

        val out = ArrayList<PricePoint>(timestamps.size)
        for (i in timestamps.indices) {
            val close = closes.getOrNull(i) ?: continue // Yahoo pads gaps with null
            val tsSec = timestamps[i]
            val extended = if (prePost) {
                val zdt = Instant.ofEpochSecond(tsSec).atZone(zone)
                val secOfDay = zdt.hour * 3600 + zdt.minute * 60 + zdt.second
                secOfDay < REG_START_SEC || secOfDay >= REG_END_SEC
            } else {
                false
            }
            out.add(PricePoint(tsSec * 1000L, close, extended, volumes?.getOrNull(i)?.toDouble()))
        }
        return out
    }

    /**
     * Crypto price history from Yahoo (BTC-USD, ETH-USD, …). Used for the long ranges (3Y / ALL)
     * that CoinGecko's free API now rejects — it returns HTTP 401 for `days` > 365. Yahoo keys
     * crypto as "<TICKER>-USD", carries full history, and needs no key.
     *
     * [ticker] is the crypto ticker (e.g. "BTC"); "-USD" is appended here.
     */
    suspend fun cryptoHistory(ticker: String, range: ChartRange): List<PricePoint> {
        val enc = yahooSymbol("$ticker-USD")
        val now = System.currentTimeMillis() / 1000
        val params = when (range) {
            // Yahoo's range=max&interval=1wk silently truncates crypto to ~3y (verified), so pin
            // period1 well before any coin's inception (2010-01-01) to pull the full weekly series.
            ChartRange.ALL -> "period1=1262304000&period2=$now&interval=1wk"
            // Explicit 3-year daily window (Yahoo has no "3y" range literal).
            else -> "period1=${now - 3L * 365 * 86_400}&period2=$now&interval=1d"
        }
        val path = "v8/finance/chart/$enc?$params"
        val result = fetchChart(path).chart.result?.firstOrNull() ?: return emptyList()
        val timestamps = result.timestamp ?: return emptyList()
        val quote0 = result.indicators?.quote?.firstOrNull()
        val closes = quote0?.close ?: return emptyList()
        val volumes = quote0.volume
        val out = ArrayList<PricePoint>(timestamps.size)
        for (i in timestamps.indices) {
            val close = closes.getOrNull(i) ?: continue // Yahoo pads gaps with null
            out.add(PricePoint(timestamps[i] * 1000L, close, false, volumes?.getOrNull(i)?.toDouble()))
        }
        return out
    }

    /**
     * GET a chart-endpoint [path] and parse it, failing over query1 → query2. The failover also
     * triggers when query1 returns a 200 whose body isn't the JSON we expect (Yahoo serves HTML
     * consent / rate-limit pages that way), because the parse happens *inside* the failover. A
     * Yahoo `error` object is likewise treated as a failure, so a rate-limited response fails over
     * (and, if both hosts fail, throws) instead of being mistaken for "no data". Serialized through
     * [gate] so one detail-screen open can't fan five simultaneous requests into a 429.
     */
    private suspend fun fetchChart(path: String): YahooChartResponse = gate.withPermit {
        try {
            parseChart(Http.getString("https://query1.finance.yahoo.com/$path"))
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // query1 failed (transport error, garbled/HTML body, or a Yahoo error object) — fail over.
            try {
                parseChart(Http.getString("https://query2.finance.yahoo.com/$path"))
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                throw ce
            } catch (e: HttpStatusException) {
                // A definitive 404 = delisted/unknown symbol = genuine no-data, not a transient
                // failure. Return empty so the UI shows "no data" instead of a Retry that can't
                // succeed; everything else (429/5xx/timeout) propagates so stale-while-error/retry
                // can kick in.
                if (e.code == 404) YahooChartResponse() else throw e
            }
        }
    }

    private fun parseChart(body: String): YahooChartResponse {
        val dto = Http.json.decodeFromString<YahooChartResponse>(body)
        // "Not Found" / delisted is genuine no-data — let it through as an empty (result=null)
        // response. Any other error (rate limit, auth, server) is transient, so throw to fail over.
        dto.chart.error?.takeUnless { it.isNoData }
            ?.let { throw IOException("Yahoo chart error ${it.code}: ${it.description}") }
        return dto
    }

    /** Returns the Yahoo query fragment (range+interval, or an explicit period for 3Y). */
    private fun rangeParams(range: ChartRange): String = when (range) {
        ChartRange.DAY -> "range=1d&interval=1m"
        ChartRange.WEEK -> "range=5d&interval=5m"
        ChartRange.MONTH -> "range=1mo&interval=30m"   // intraday detail
        ChartRange.QUARTER -> "range=3mo&interval=1h"  // hourly detail
        ChartRange.YEAR -> "range=1y&interval=1d"
        ChartRange.THREE_YEAR -> {
            // Yahoo has no "3y" range literal, so request an explicit 3-year window.
            val now = System.currentTimeMillis() / 1000
            "period1=${now - 3L * 365 * 86_400}&period2=$now&interval=1d"
        }
        ChartRange.ALL -> "range=max&interval=1wk"
    }

    /** Ex-dividend dates + amounts within [range], from the chart endpoint's dividend events. */
    suspend fun dividends(symbol: String, range: ChartRange): List<Pair<Long, Double>> {
        val enc = yahooSymbol(symbol)
        val path = "v8/finance/chart/$enc?${rangeParams(range)}&events=div"
        // Decorative overlay — a failure here should quietly yield no markers, not surface an error.
        val result = runCatching { fetchChart(path) }.getOrNull()?.chart?.result?.firstOrNull()
            ?: return emptyList()
        val divs = result.events?.dividends ?: return emptyList()
        return divs.values.map { it.date * 1000L to it.amount }.sortedBy { it.first }
    }

    /** 52-week high/low straight from Yahoo's chart meta (a tiny range=1d request suffices). */
    suspend fun fiftyTwoWeek(symbol: String): Pair<Double, Double>? {
        val enc = yahooSymbol(symbol)
        val path = "v8/finance/chart/$enc?range=1d&interval=1d"
        val meta = fetchChart(path).chart.result?.firstOrNull()?.meta
        val hi = meta?.fiftyTwoWeekHigh
        val lo = meta?.fiftyTwoWeekLow
        return if (hi != null && lo != null) hi to lo else null
    }

    /**
     * Live stock/ETF quote straight from Yahoo's chart meta — no API key, so this is the app's
     * primary quote source (Finnhub is an optional fallback). Returns null if the symbol is unknown.
     */
    suspend fun quote(symbol: String): Quote? {
        val enc = yahooSymbol(symbol)
        val path = "v8/finance/chart/$enc?range=1d&interval=1d"
        // fetchChart throws on a transient failure (so the repo can serve a stale quote) and returns
        // a null result only when Yahoo genuinely has no data for the symbol.
        val result = fetchChart(path).chart.result?.firstOrNull() ?: return null
        val meta = result.meta ?: return null
        val price = meta.regularMarketPrice ?: return null
        val prev = meta.chartPreviousClose ?: meta.previousClose
        val q0 = result.indicators?.quote?.firstOrNull()
        val change = if (prev != null) price - prev else 0.0
        val pct = if (prev != null && prev != 0.0) change / prev * 100.0 else 0.0
        return Quote(
            symbol = symbol.uppercase(),
            price = price,
            change = change,
            changePercent = pct,
            open = q0?.open?.firstOrNull(),
            high = meta.regularMarketDayHigh ?: q0?.high?.firstOrNull(),
            low = meta.regularMarketDayLow ?: q0?.low?.firstOrNull(),
            prevClose = prev,
            volume = meta.regularMarketVolume?.toDouble(),
            currency = meta.currency ?: "USD",
            asOfEpochMs = System.currentTimeMillis(),
            isEtf = meta.instrumentType.equals("ETF", ignoreCase = true),
        )
    }

    /** Symbol search (stocks + ETFs) — no API key. US listings only, foreign suffixes filtered out. */
    suspend fun search(query: String): List<SearchResult> {
        val url = "https://query1.finance.yahoo.com/v1/finance/search?q=${query.urlEncode()}&quotesCount=15&newsCount=0"
        val body = runCatching { Http.getString(url) }
            .getOrElse { Http.getString(url.replace("query1", "query2")) }
        val dto = runCatching { Http.json.decodeFromString<YahooSearchResponse>(body) }.getOrNull() ?: return emptyList()
        return dto.quotes.asSequence()
            .filter { it.quoteType == "EQUITY" || it.quoteType == "ETF" }
            .filter { it.symbol.isNotBlank() && '.' !in it.symbol } // '.' = foreign suffix (.F/.SW/.T)
            .map {
                SearchResult(
                    symbol = it.symbol.uppercase(),
                    name = it.longname ?: it.shortname ?: it.symbol,
                    type = AssetType.STOCK,
                )
            }
            .toList()
    }

    /** Current CBOE Volatility Index (^VIX) with its change vs the prior close. */
    suspend fun vix(): VixQuote? {
        val path = "v8/finance/chart/%5EVIX?range=1d&interval=1d"
        val meta = fetchChart(path).chart.result?.firstOrNull()?.meta
        val value = meta?.regularMarketPrice ?: return null
        val prev = meta.chartPreviousClose ?: meta.previousClose ?: value
        val change = value - prev
        val pct = if (prev != 0.0) change / prev * 100.0 else 0.0
        return VixQuote(value = value, change = change, changePercent = pct)
    }

    private companion object {
        const val REG_START_SEC = 9L * 3600 + 30 * 60 // 09:30
        const val REG_END_SEC = 16L * 3600            // 16:00
        val EXCHANGE_ZONE: ZoneId = ZoneId.of("America/New_York")

        // Yahoo throttles by IP; cap concurrent chart requests so one detail-screen open (quote +
        // history + 52-week + dividends + benchmark) doesn't fan five simultaneous calls into a 429.
        val gate = Semaphore(2)
    }
}

@Serializable
data class YahooChartResponse(val chart: YahooChart = YahooChart())

@Serializable
data class YahooChart(
    val result: List<YahooResult>? = null,
    val error: YahooError? = null,
)

@Serializable
data class YahooError(val code: String? = null, val description: String? = null) {
    /** Yahoo's way of saying the symbol simply has no data (delisted/unknown) vs. a transient fault. */
    val isNoData: Boolean
        get() = code.equals("Not Found", ignoreCase = true) ||
            description?.contains("delisted", ignoreCase = true) == true ||
            description?.contains("No data", ignoreCase = true) == true
}

@Serializable
data class YahooResult(
    val meta: YahooMeta? = null,
    val timestamp: List<Long>? = null,
    val indicators: YahooIndicators? = null,
    val events: YahooEvents? = null,
)

@Serializable
data class YahooEvents(val dividends: Map<String, YahooDividend>? = null)

@Serializable
data class YahooDividend(val amount: Double = 0.0, val date: Long = 0L)

@Serializable
data class YahooMeta(
    val exchangeTimezoneName: String? = null,
    val instrumentType: String? = null, // "EQUITY" | "ETF" | "CRYPTOCURRENCY" | "INDEX"
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val regularMarketPrice: Double? = null,
    val previousClose: Double? = null,
    val chartPreviousClose: Double? = null,
    val regularMarketDayHigh: Double? = null,
    val regularMarketDayLow: Double? = null,
    val regularMarketVolume: Long? = null,
    val currency: String? = null,
)

@Serializable
data class YahooIndicators(val quote: List<YahooQuote>? = null)

@Serializable
data class YahooQuote(
    val close: List<Double?>? = null,
    val volume: List<Long?>? = null,
    val open: List<Double?>? = null,
    val high: List<Double?>? = null,
    val low: List<Double?>? = null,
)

@Serializable
data class YahooSearchResponse(val quotes: List<YahooSearchQuote> = emptyList())

@Serializable
data class YahooSearchQuote(
    val symbol: String = "",
    val shortname: String? = null,
    val longname: String? = null,
    @SerialName("quoteType") val quoteType: String? = null,
)
