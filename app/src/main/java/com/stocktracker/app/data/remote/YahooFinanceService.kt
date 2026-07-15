package com.stocktracker.app.data.remote

import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.time.Instant
import java.time.ZoneId

/**
 * Yahoo Finance chart endpoint — free, no key, supports intraday + pre/post-market.
 * Used for stock history (Finnhub free has no candles; Stooq blocks non-browser clients).
 * Unofficial endpoint; failures degrade to an empty chart.
 */
class YahooFinanceService {

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
        val path = "v8/finance/chart/${symbol.uppercase()}?${rangeParams(range)}&includePrePost=$prePost"
        val body = runCatching { Http.getString("https://query1.finance.yahoo.com/$path") }
            .getOrElse { Http.getString("https://query2.finance.yahoo.com/$path") }

        val dto = Http.json.decodeFromString<YahooChartResponse>(body)
        val result = dto.chart.result?.firstOrNull() ?: return emptyList()
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

    /** 52-week high/low straight from Yahoo's chart meta (a tiny range=1d request suffices). */
    suspend fun fiftyTwoWeek(symbol: String): Pair<Double, Double>? {
        val path = "v8/finance/chart/${symbol.uppercase()}?range=1d&interval=1d"
        val body = runCatching { Http.getString("https://query1.finance.yahoo.com/$path") }
            .getOrElse { Http.getString("https://query2.finance.yahoo.com/$path") }
        val meta = Http.json.decodeFromString<YahooChartResponse>(body).chart.result?.firstOrNull()?.meta
        val hi = meta?.fiftyTwoWeekHigh
        val lo = meta?.fiftyTwoWeekLow
        return if (hi != null && lo != null) hi to lo else null
    }

    private companion object {
        const val REG_START_SEC = 9L * 3600 + 30 * 60 // 09:30
        const val REG_END_SEC = 16L * 3600            // 16:00
        val EXCHANGE_ZONE: ZoneId = ZoneId.of("America/New_York")
    }
}

@Serializable
data class YahooChartResponse(val chart: YahooChart = YahooChart())

@Serializable
data class YahooChart(val result: List<YahooResult>? = null)

@Serializable
data class YahooResult(
    val meta: YahooMeta? = null,
    val timestamp: List<Long>? = null,
    val indicators: YahooIndicators? = null,
)

@Serializable
data class YahooMeta(
    val exchangeTimezoneName: String? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
)

@Serializable
data class YahooIndicators(val quote: List<YahooQuote>? = null)

@Serializable
data class YahooQuote(
    val close: List<Double?>? = null,
    val volume: List<Long?>? = null,
)
