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
 * The quote, sparkline and 52-week range parsed from ONE Yahoo chart payload (DATA-6). Any of the
 * three may be null/empty on their own terms — a snapshot for a symbol Yahoo has no data for is
 * `ChartSnapshot(null, emptyList(), null)`, not an exception.
 */
data class ChartSnapshot(
    val quote: Quote?,
    val sparkline: List<PricePoint>,
    val fiftyTwoWeek: Pair<Double, Double>?,
)

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
        return pricePointsFrom(result, prePost)
    }

    /**
     * The bars of one chart [result] as [PricePoint]s. Pulled out of [history] so [chartSnapshot]
     * can build its sparkline from the exact same logic without a second network call.
     *
     * Classifies each point by its time-of-day in the exchange timezone when [prePost] is set:
     * regular session = 09:30–16:00; anything else within the returned data is pre/post-market.
     * Using a real ZoneId per timestamp keeps this correct across a DST transition within the 1W
     * view. [prePost] false (the sparkline / [chartSnapshot] case, since Yahoo was asked for
     * regular-session bars only) always yields `extended = false`.
     */
    private fun pricePointsFrom(result: YahooResult, prePost: Boolean): List<PricePoint> {
        val timestamps = result.timestamp ?: return emptyList()
        val quote0 = result.indicators?.quote?.firstOrNull()
        val closes = quote0?.close ?: return emptyList()
        val volumes = quote0.volume
        // Carried so the high/low markers can read the bar's real extremes instead of its close —
        // see PricePoint.high. Yahoo has always returned these; they were simply dropped here.
        val highs = quote0.high
        val lows = quote0.low
        val opens = quote0.open

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
            out.add(PricePoint(
                tsSec * 1000L, close, extended, volumes?.getOrNull(i)?.toDouble(),
                high = highs?.getOrNull(i), low = lows?.getOrNull(i),
                open = opens?.getOrNull(i),
            ))
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
    /**
     * The Yahoo window + interval for a crypto chart of [range], as query params.
     *
     * Yahoo has no "3y" range literal and its `range=max` silently truncates crypto to ~3 years, so
     * these windows are pinned with an explicit `period1` rather than a range keyword. Pinned windows
     * are TRAILING, which is the right shape for an asset that never closes: "1D" on a 24/7 market
     * means the last 24 hours, not "since midnight UTC".
     *
     * Each window is exactly its own width, and that is the whole point of this function existing.
     * They used to carry slack — 2 days for DAY, 8 for WEEK, 32/95/370 for the rest — copied from the
     * reasoning the STOCK path needs, where a Monday morning must reach back over a weekend to find
     * the last session. Crypto has no weekend to reach over, so the slack was simply extra chart:
     * measured against live BTC-USD on 2026-09-03, the "1D" chart spanned 48.0 hours and read
     * -0.40% while the true trailing 24 hours was +1.32%. Not merely a wrong magnitude — the
     * opposite sign, under a label that says one day. The same series feeds the watchlist and widget
     * sparklines, so their previous-close baseline sat mid-line instead of at the left edge.
     */
    internal fun cryptoChartParams(range: ChartRange, now: Long): String {
        fun trailing(days: Long, interval: String) =
            "period1=${now - days * 86_400}&period2=$now&interval=$interval"
        return when (range) {
            ChartRange.DAY -> trailing(1, "5m")
            ChartRange.WEEK -> trailing(7, "30m")
            ChartRange.MONTH -> trailing(30, "1d")
            ChartRange.QUARTER -> trailing(91, "1d")     // a quarter, not 95 days
            ChartRange.YEAR -> trailing(365, "1d")
            ChartRange.THREE_YEAR -> trailing(3 * 365, "1d")
            // range=max&interval=1wk silently truncates crypto to ~3y (verified), so pin to 2010 —
            // before any of these coins traded, so it is a real "everything" rather than a window.
            ChartRange.ALL -> "period1=1262304000&period2=$now&interval=1wk"
        }
    }

    suspend fun cryptoHistory(ticker: String, range: ChartRange): List<PricePoint> {
        val enc = yahooSymbol("$ticker-USD")
        val now = System.currentTimeMillis() / 1000
        val path = "v8/finance/chart/$enc?${cryptoChartParams(range, now)}"
        val result = fetchChart(path).chart.result?.firstOrNull() ?: return emptyList()
        val timestamps = result.timestamp ?: return emptyList()
        val quote0 = result.indicators?.quote?.firstOrNull()
        val closes = quote0?.close ?: return emptyList()
        val volumes = quote0.volume
        val highs = quote0.high
        val lows = quote0.low
        val opens = quote0.open
        val out = ArrayList<PricePoint>(timestamps.size)
        for (i in timestamps.indices) {
            val close = closes.getOrNull(i) ?: continue // Yahoo pads gaps with null
            out.add(PricePoint(
                timestamps[i] * 1000L, close, false, volumes?.getOrNull(i)?.toDouble(),
                high = highs?.getOrNull(i), low = lows?.getOrNull(i),
                open = opens?.getOrNull(i),
            ))
        }
        return out
    }

    /**
     * GET a chart-endpoint [path] and parse it, failing over query1 → query2. The failover also
     * triggers when query1 returns a 200 whose body isn't the JSON we expect (Yahoo serves HTML
     * consent / rate-limit pages that way), because the parse happens *inside* the failover. A
     * Yahoo `error` object is likewise treated as a failure, so a garbled/erroring response fails
     * over (and, if both hosts fail, throws) instead of being mistaken for "no data" — EXCEPT a 429,
     * which does not fail over (see [RetryPolicy.shouldFailoverToOtherHost]): rate limiting is a
     * property of the caller, not of query1 specifically, so retrying the identical ladder against
     * query2 would only double the request volume at the moment Yahoo is asking for less of it.
     *
     * Checks [Http.throwIfBreakerOpen] for query1 *before* touching [gate], so once query1 is known
     * to be rate-limiting, further calls fail immediately instead of queueing for one of its 2
     * permits only to hit the same wall.
     */
    private suspend fun fetchChart(path: String): YahooChartResponse {
        val primaryUrl = "https://query1.finance.yahoo.com/$path"
        Http.throwIfBreakerOpen(primaryUrl)
        return gate.withPermit {
            try {
                parseChart(Http.getString(primaryUrl))
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                throw ce
            } catch (e: HttpStatusException) {
                if (!RetryPolicy.shouldFailoverToOtherHost(e.code)) throw e
                failoverToQuery2(path)
            } catch (_: Throwable) {
                // query1 failed (transport error, garbled/HTML body, or a Yahoo error object) — fail over.
                failoverToQuery2(path)
            }
        }
    }

    /** The query2 half of [fetchChart]'s failover, also breaker-gated. */
    private suspend fun failoverToQuery2(path: String): YahooChartResponse {
        val secondaryUrl = "https://query2.finance.yahoo.com/$path"
        Http.throwIfBreakerOpen(secondaryUrl)
        return try {
            parseChart(Http.getString(secondaryUrl))
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

    /** Ex-dividend dates + amounts within [range], from the chart endpoint's dividend events.
     *  Also requests split events on the same call (MONEY-4) — one Yahoo hit gets both — but a
     *  range as narrow as 1D/1W will not carry an old split, so this is NOT split detection; use
     *  [splitsSince] for that. */
    suspend fun dividends(symbol: String, range: ChartRange): List<Pair<Long, Double>> {
        val enc = yahooSymbol(symbol)
        val path = "v8/finance/chart/$enc?${rangeParams(range)}&events=div,splits"
        // Decorative overlay — a failure here should quietly yield no markers, not surface an error.
        val result = runCatching { fetchChart(path) }.getOrNull()?.chart?.result?.firstOrNull()
            ?: return emptyList()
        val divs = result.events?.dividends ?: return emptyList()
        return divs.values.map { it.date * 1000L to it.amount }.sortedBy { it.first }
    }

    /**
     * Stock splits on or after [sinceEpochMs] (MONEY-4). Always fetched over the symbol's FULL
     * history (`range=max`) rather than whatever range a chart happens to be showing — a held lot's
     * acquisition date is routinely far older than the visible chart window, and a split just
     * outside that window is exactly the one that corrupts the share count silently.
     */
    suspend fun splitsSince(symbol: String, sinceEpochMs: Long): List<SplitEvent> {
        val enc = yahooSymbol(symbol)
        val path = "v8/finance/chart/$enc?range=max&interval=1mo&events=splits"
        val result = runCatching { fetchChart(path) }.getOrNull()?.chart?.result?.firstOrNull()
            ?: return emptyList()
        val raw = result.events?.splits ?: return emptyList()
        return raw.values.mapNotNull { s ->
            if (s.numerator <= 0.0 || s.denominator <= 0.0) return@mapNotNull null
            val epochMs = s.date * 1000L
            if (epochMs < sinceEpochMs) return@mapNotNull null
            SplitEvent(epochMs, s.numerator / s.denominator, s.splitRatio ?: formatRatio(s.numerator, s.denominator))
        }.sortedBy { it.epochMs }
    }

    private fun formatRatio(numerator: Double, denominator: Double): String {
        fun trim(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        return "${trim(numerator)}:${trim(denominator)}"
    }

    /**
     * DATA-6 — quote, sparkline and 52-week range from ONE chart fetch.
     *
     * These used to be three independent requests (`quote()`, `history(DAY)`, `fiftyTwoWeek()`),
     * each asking Yahoo's chart endpoint for the same symbol on the same day — the meta block Yahoo
     * attaches (live price, previous close, day high/low, 52-week high/low) is identical regardless
     * of which range/interval is requested, and the DAY-range bars a sparkline needs already carry
     * everything the other two calls were re-downloading it to get. A watchlist refresh that wants
     * all three for N symbols made ~3N requests; this makes N.
     *
     * `includePrePost=false` matches the sparkline's own historical params exactly (a sparkline is
     * decorative shape and must stay regular-session-only — see [MarketRepository.sparkline]), so
     * merging costs the sparkline nothing. It costs the quote its (already-dead) post-market meta
     * fields: Yahoo stopped populating `postMarketPrice`/`postMarketChangePercent` regardless of the
     * prePost flag (see [YahooMeta]), so dropping the flag changes nothing that still worked.
     */
    suspend fun chartSnapshot(symbol: String): ChartSnapshot {
        val enc = yahooSymbol(symbol)
        val path = "v8/finance/chart/$enc?${rangeParams(ChartRange.DAY)}&includePrePost=false"
        // fetchChart throws on a transient failure (so the repo can serve a stale snapshot) and
        // returns a null result only when Yahoo genuinely has no data for the symbol.
        return parseChartSnapshot(symbol, fetchChart(path))
    }

    /** Pure parse behind [chartSnapshot] — split out so a fixture payload can exercise it directly
     *  without a network call (DATA-6). */
    internal fun parseChartSnapshot(symbol: String, response: YahooChartResponse): ChartSnapshot {
        val result = response.chart.result?.firstOrNull()
            ?: return ChartSnapshot(quote = null, sparkline = emptyList(), fiftyTwoWeek = null)
        val sparkline = pricePointsFrom(result, prePost = false)
        return ChartSnapshot(
            quote = quoteFrom(symbol, result),
            sparkline = sparkline,
            fiftyTwoWeek = fiftyTwoWeekFrom(result.meta),
        )
    }

    private fun fiftyTwoWeekFrom(meta: YahooMeta?): Pair<Double, Double>? {
        val hi = meta?.fiftyTwoWeekHigh
        val lo = meta?.fiftyTwoWeekLow
        return if (hi != null && lo != null) hi to lo else null
    }

    /**
     * Live stock/ETF quote straight from a chart result's meta — no API key, so this is the app's
     * primary quote source (Finnhub is an optional fallback). Returns null if Yahoo has nothing.
     */
    private fun quoteFrom(symbol: String, result: YahooResult): Quote? {
        val meta = result.meta ?: return null
        val price = meta.regularMarketPrice ?: return null
        val prev = meta.chartPreviousClose ?: meta.previousClose
        val q0 = result.indicators?.quote?.firstOrNull()
        val change = if (prev != null) price - prev else 0.0
        val pct = if (prev != null && prev != 0.0) change / prev * 100.0 else 0.0
        // After-hours move is measured against the regular-session close (regularMarketPrice). Prefer
        // computing it ourselves for consistent units; the meta's postMarketChangePercent is only a
        // fallback when the price is somehow absent.
        val postPrice = meta.postMarketPrice
        val postPct = when {
            postPrice != null && price != 0.0 -> (postPrice - price) / price * 100.0
            else -> meta.postMarketChangePercent
        }
        return Quote(
            symbol = symbol.uppercase(),
            price = price,
            change = change,
            changePercent = pct,
            // The first bar with an actual open — not just the first bar, which [chartSnapshot]'s
            // minute-resolution payload can pad with a null when the session's first tick is a gap.
            open = q0?.open?.firstNotNullOfOrNull { it },
            // Day high/low: prefer the live meta (correct regardless of bar granularity); fall back
            // to the max/min OF THE WHOLE ARRAY rather than its first element. With [chartSnapshot]'s
            // one-minute bars, the first element is only the opening minute's high — not the day's.
            high = meta.regularMarketDayHigh ?: q0?.high?.filterNotNull()?.maxOrNull(),
            low = meta.regularMarketDayLow ?: q0?.low?.filterNotNull()?.minOrNull(),
            prevClose = prev,
            volume = meta.regularMarketVolume?.toDouble(),
            currency = meta.currency ?: "USD",
            asOfEpochMs = System.currentTimeMillis(),
            isEtf = meta.instrumentType.equals("ETF", ignoreCase = true),
            postMarketPrice = postPrice,
            postMarketChangePercent = postPct,
            marketState = meta.marketState,
        )
    }

    /**
     * The after-hours move as a % of the regular-session close, derived from INTRADAY BARS.
     *
     * Returns null when it cannot be determined — no post-market bars, a failed fetch, an unusable
     * regular close. Null means UNKNOWN, never "flat": the caller has to be able to tell "this stock
     * did not trade after the close" from "we could not read the data", because reporting the second
     * as the first is how an after-hours recap ends up announcing a quiet session on a night one of
     * your holdings moved 17%.
     *
     * Why this exists at all: the move used to come from `meta.postMarketPrice`, and Yahoo removed
     * the pre/post fields from the chart meta. Nothing errored — the field just became null forever,
     * so every after-hours recap reported "Quiet after-hours" regardless of what happened. Measured
     * 2026-08-03: BLZE closed at $15.60 and traded to $18.25 after hours (+17.0%, high $19.06) while
     * the recap said nothing had moved.
     *
     * Uses 5-minute bars: enough resolution for a session-level move at a fraction of the ~640-bar
     * payload a 1-minute range would cost across a whole watchlist.
     */
    suspend fun postMarketMove(symbol: String): Double? {
        val enc = yahooSymbol(symbol)
        val path = "v8/finance/chart/$enc?range=1d&interval=5m&includePrePost=true"
        val result = runCatching { fetchChart(path) }.getOrNull()?.chart?.result?.firstOrNull() ?: return null
        val meta = result.meta ?: return null
        val regularEnd = meta.currentTradingPeriod?.regular?.end ?: return null
        val stamps = result.timestamp ?: return null
        val closes = result.indicators?.quote?.firstOrNull()?.close ?: return null

        var lastRegular: Double? = null
        var lastPost: Double? = null
        for (i in stamps.indices) {
            val px = closes.getOrNull(i) ?: continue
            if (stamps[i] < regularEnd) lastRegular = px else lastPost = px
        }
        val base = lastRegular ?: meta.regularMarketPrice ?: return null
        val post = lastPost ?: return null
        if (base <= 0.0) return null
        return (post - base) / base * 100.0
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

        // Yahoo throttles by IP; cap concurrent chart requests so one detail-screen open (the merged
        // quote+52-week snapshot, DATA-6 + history + dividends + benchmark) doesn't fan simultaneous
        // calls into a 429.
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
data class YahooEvents(
    val dividends: Map<String, YahooDividend>? = null,
    val splits: Map<String, YahooSplitDto>? = null,
)

@Serializable
data class YahooDividend(val amount: Double = 0.0, val date: Long = 0L)

/** Raw split event from Yahoo's chart `events=splits`: [numerator] new shares per [denominator] old
 *  ones (4/1 = a 4-for-1; 1/10 = a 1-for-10 reverse split). */
@Serializable
data class YahooSplitDto(
    val date: Long = 0L,
    val numerator: Double = 0.0,
    val denominator: Double = 0.0,
    val splitRatio: String? = null,
)

/** A stock split (MONEY-4). [ratio] is new shares per old share — multiply shares by it, divide
 *  cost-per-share by it. 4.0 = a 4-for-1 split; 0.1 = a 1-for-10 reverse split. */
data class SplitEvent(val epochMs: Long, val ratio: Double, val label: String)

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
    // Post-market (after-hours) fields. Yahoo USED to return these in the chart meta with
    // includePrePost=true — they are now absent from the response entirely (verified 2026-08-03: the
    // meta carries no postMarketPrice, postMarketChangePercent, preMarketPrice or marketState key at
    // all). Kept because the parse is tolerant and they may return, but nothing should DEPEND on
    // them: `postMarketMove()` derives the after-hours move from the intraday bars instead.
    val postMarketPrice: Double? = null,
    val postMarketChangePercent: Double? = null,
    val marketState: String? = null, // "PRE" | "REGULAR" | "POST" | "POSTPOST" | "CLOSED" | …
    /** True when the symbol has pre/post bars available on an intraday interval. */
    val hasPrePostMarketData: Boolean? = null,
    val currentTradingPeriod: YahooTradingPeriods? = null,
)

@Serializable
data class YahooTradingPeriods(val regular: YahooTradingPeriod? = null)

@Serializable
data class YahooTradingPeriod(val start: Long? = null, val end: Long? = null)

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
