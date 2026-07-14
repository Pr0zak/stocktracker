package com.stocktracker.app.data.remote

import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

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
        val (r, interval) = params(range)
        // Pre/post-market is only meaningful (and returned) for the intraday views.
        val prePost = includeExtended && (range == ChartRange.DAY || range == ChartRange.WEEK)
        val path = "v8/finance/chart/${symbol.uppercase()}?range=$r&interval=$interval&includePrePost=$prePost"
        val body = runCatching { Http.getString("https://query1.finance.yahoo.com/$path") }
            .getOrElse { Http.getString("https://query2.finance.yahoo.com/$path") }

        val dto = Http.json.decodeFromString<YahooChartResponse>(body)
        val result = dto.chart.result?.firstOrNull() ?: return emptyList()
        val timestamps = result.timestamp ?: return emptyList()
        val closes = result.indicators?.quote?.firstOrNull()?.close ?: return emptyList()

        // Classify each point by its time-of-day in the exchange timezone (gmtoffset). Regular
        // session = 09:30–16:00; anything else within the returned data is pre/post-market. This
        // works per-day, so extended hours are flagged correctly across the multi-day 1W view too.
        val gmtOffset = result.meta?.gmtoffset
        val classify = prePost && gmtOffset != null

        val out = ArrayList<PricePoint>(timestamps.size)
        for (i in timestamps.indices) {
            val close = closes.getOrNull(i) ?: continue // Yahoo pads gaps with null
            val tsSec = timestamps[i]
            val extended = if (classify) {
                val secOfDay = Math.floorMod(tsSec + gmtOffset!!, 86_400L)
                secOfDay < REG_START_SEC || secOfDay >= REG_END_SEC
            } else {
                false
            }
            out.add(PricePoint(tsSec * 1000L, close, extended))
        }
        return out
    }

    private fun params(range: ChartRange): Pair<String, String> = when (range) {
        ChartRange.DAY -> "1d" to "1m"    // finer than before (was 5m)
        ChartRange.WEEK -> "5d" to "5m"   // finer than before (was 30m)
        ChartRange.MONTH -> "1mo" to "1d"
        ChartRange.QUARTER -> "3mo" to "1d"
        ChartRange.YEAR -> "1y" to "1d"
        ChartRange.ALL -> "max" to "1wk"
    }

    private companion object {
        const val REG_START_SEC = 9L * 3600 + 30 * 60 // 09:30
        const val REG_END_SEC = 16L * 3600            // 16:00
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
data class YahooMeta(val gmtoffset: Long? = null)

@Serializable
data class YahooIndicators(val quote: List<YahooQuote>? = null)

@Serializable
data class YahooQuote(val close: List<Double?>? = null)
