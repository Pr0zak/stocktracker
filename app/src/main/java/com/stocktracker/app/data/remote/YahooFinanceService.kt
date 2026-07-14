package com.stocktracker.app.data.remote

import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/**
 * Yahoo Finance chart endpoint — free, no key, and supports intraday.
 * Used for stock history because Finnhub's free tier has no candles and Stooq now blocks
 * non-browser clients. Unofficial endpoint; failures degrade gracefully to an empty chart.
 */
class YahooFinanceService {

    suspend fun history(symbol: String, range: ChartRange): List<PricePoint> {
        val (r, interval) = params(range)
        val path = "v8/finance/chart/${symbol.uppercase()}?range=$r&interval=$interval"
        val body = runCatching { Http.getString("https://query1.finance.yahoo.com/$path") }
            .getOrElse { Http.getString("https://query2.finance.yahoo.com/$path") }

        val dto = Http.json.decodeFromString<YahooChartResponse>(body)
        val result = dto.chart.result?.firstOrNull() ?: return emptyList()
        val timestamps = result.timestamp ?: return emptyList()
        val closes = result.indicators?.quote?.firstOrNull()?.close ?: return emptyList()

        val out = ArrayList<PricePoint>(timestamps.size)
        for (i in timestamps.indices) {
            val close = closes.getOrNull(i) ?: continue // Yahoo pads gaps with null
            out.add(PricePoint(timestamps[i] * 1000L, close))
        }
        return out
    }

    private fun params(range: ChartRange): Pair<String, String> = when (range) {
        ChartRange.DAY -> "1d" to "5m"
        ChartRange.WEEK -> "5d" to "30m"
        ChartRange.MONTH -> "1mo" to "1d"
        ChartRange.QUARTER -> "3mo" to "1d"
        ChartRange.YEAR -> "1y" to "1d"
        ChartRange.ALL -> "max" to "1wk"
    }
}

@Serializable
data class YahooChartResponse(val chart: YahooChart = YahooChart())

@Serializable
data class YahooChart(val result: List<YahooResult>? = null)

@Serializable
data class YahooResult(
    val timestamp: List<Long>? = null,
    val indicators: YahooIndicators? = null,
)

@Serializable
data class YahooIndicators(val quote: List<YahooQuote>? = null)

@Serializable
data class YahooQuote(val close: List<Double?>? = null)
