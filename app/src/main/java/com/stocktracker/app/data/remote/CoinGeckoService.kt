package com.stocktracker.app.data.remote

import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.model.SearchResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.IOException

/**
 * One 24h move from CoinGecko's two fields, made to agree.
 *
 * /coins/markets returns `price_change_24h` and `price_change_percentage_24h` as separate numbers,
 * and they are not always computed from the same snapshot: on 2026-09-28 the ETH row read
 * "▼ -0.44 (+0.05%)" — a falling arrow (the arrow follows the dollar change) beside a rising
 * percentage. The percentage is kept as the source of truth, since it is what the single-coin
 * [CoinGeckoService.quote] path already derives its change from, and the dollar change is
 * recomputed from it against the current price. With only the dollar change present, the
 * percentage is derived from it instead. Neither present: (0, 0), as before.
 */
internal fun consistentChange(price: Double, change: Double?, pct: Double?): Pair<Double, Double> = when {
    pct != null && pct.isFinite() && pct > -100.0 -> (price - price / (1.0 + pct / 100.0)) to pct
    change != null && change.isFinite() && price - change > 0.0 -> change to (change / (price - change) * 100.0)
    else -> 0.0 to 0.0
}

/** CoinGecko free API: crypto prices, market data (with 7d sparkline), history, and search. No key. */
class CoinGeckoService {

    private val base = "https://api.coingecko.com/api/v3"

    suspend fun quote(coinId: String, symbol: String): Quote {
        val body = Http.getString(
            "$base/simple/price?ids=$coinId&vs_currencies=usd&include_24hr_change=true&include_24hr_vol=true"
        )
        val map = Http.json.decodeFromString<Map<String, CoinGeckoPriceDto>>(body)
        val d = map[coinId] ?: throw IOException("No CoinGecko price for '$coinId'")
        val price = d.usd
        if (price == null || price <= 0.0) {
            throw IOException("No CoinGecko price for '$coinId' (invalid or missing price)")
        }
        val pct = d.usd24hChange ?: 0.0
        val prev = if (pct != -100.0) price / (1.0 + pct / 100.0) else price
        return Quote(
            symbol = symbol.uppercase(),
            price = price,
            change = price - prev,
            changePercent = pct,
            prevClose = prev,
            volume = d.usd24hVol,
            currency = "USD",
            asOfEpochMs = System.currentTimeMillis(),
        )
    }

    /** Batched market data for several coins in one call — powers watchlist rows + sparklines. */
    suspend fun markets(coinIds: List<String>): List<CoinMarket> {
        if (coinIds.isEmpty()) return emptyList()
        val ids = coinIds.joinToString(",")
        val url = "$base/coins/markets?vs_currency=usd&ids=$ids&sparkline=true&price_change_percentage=24h"
        val dto = Http.json.decodeFromString<List<CoinMarketDto>>(Http.getString(url))
        return dto.mapNotNull {
            val price = it.currentPrice
            if (price == null || price <= 0.0) null else {
                val (change, pct) = consistentChange(price, it.priceChange24h, it.priceChangePercentage24h)
                CoinMarket(
                    id = it.id,
                    symbol = it.symbol.uppercase(),
                    name = it.name,
                    price = price,
                    change = change,
                    changePercent = pct,
                    sparkline = it.sparkline?.price ?: emptyList(),
                )
            }
        }
    }

    suspend fun history(coinId: String, days: String): List<PricePoint> {
        val url = "$base/coins/$coinId/market_chart?vs_currency=usd&days=$days"
        val dto = Http.json.decodeFromString<CoinGeckoChartDto>(Http.getString(url))
        val vols = dto.totalVolumes
        return dto.prices.mapIndexedNotNull { i, p ->
            if (p.size >= 2) PricePoint(p[0].toLong(), p[1], volume = vols.getOrNull(i)?.getOrNull(1)) else null
        }
    }

    suspend fun search(query: String): List<SearchResult> {
        val dto = Http.json.decodeFromString<CoinGeckoSearchDto>(
            Http.getString("$base/search?query=${query.urlEncode()}")
        )
        return dto.coins.map {
            SearchResult(it.symbol.uppercase(), it.name, AssetType.CRYPTO, coinGeckoId = it.id)
        }.take(25)
    }
}

data class CoinMarket(
    val id: String,
    val symbol: String,
    val name: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val sparkline: List<Double>,
)

@Serializable
data class CoinGeckoPriceDto(
    val usd: Double?,
    @SerialName("usd_24h_change") val usd24hChange: Double? = null,
    @SerialName("usd_24h_vol") val usd24hVol: Double? = null,
)

@Serializable
data class CoinGeckoChartDto(
    val prices: List<List<Double>> = emptyList(),
    @SerialName("total_volumes") val totalVolumes: List<List<Double>> = emptyList(),
)

@Serializable
data class CoinMarketDto(
    val id: String = "",
    val symbol: String = "",
    val name: String = "",
    @SerialName("current_price") val currentPrice: Double?,
    @SerialName("price_change_24h") val priceChange24h: Double? = null,
    @SerialName("price_change_percentage_24h") val priceChangePercentage24h: Double? = null,
    @SerialName("sparkline_in_7d") val sparkline: SparklineDto? = null,
)

@Serializable
data class SparklineDto(val price: List<Double> = emptyList())

@Serializable
data class CoinGeckoSearchDto(val coins: List<CoinGeckoCoin> = emptyList())

@Serializable
data class CoinGeckoCoin(
    val id: String = "",
    val symbol: String = "",
    val name: String = "",
)
