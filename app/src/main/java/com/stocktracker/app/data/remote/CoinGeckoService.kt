package com.stocktracker.app.data.remote

import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.model.SearchResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.IOException

/** CoinGecko free API: crypto prices, market data (with 7d sparkline), history, and search. No key. */
class CoinGeckoService {

    private val base = "https://api.coingecko.com/api/v3"

    suspend fun quote(coinId: String, symbol: String): Quote {
        val body = Http.getString("$base/simple/price?ids=$coinId&vs_currencies=usd&include_24hr_change=true")
        val map = Http.json.decodeFromString<Map<String, CoinGeckoPriceDto>>(body)
        val d = map[coinId] ?: throw IOException("No CoinGecko price for '$coinId'")
        val price = d.usd
        val pct = d.usd24hChange ?: 0.0
        val prev = if (pct != -100.0) price / (1.0 + pct / 100.0) else price
        return Quote(
            symbol = symbol.uppercase(),
            price = price,
            change = price - prev,
            changePercent = pct,
            prevClose = prev,
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
        return dto.map {
            CoinMarket(
                id = it.id,
                symbol = it.symbol.uppercase(),
                name = it.name,
                price = it.currentPrice,
                change = it.priceChange24h ?: 0.0,
                changePercent = it.priceChangePercentage24h ?: 0.0,
                sparkline = it.sparkline?.price ?: emptyList(),
            )
        }
    }

    suspend fun history(coinId: String, days: String): List<PricePoint> {
        val url = "$base/coins/$coinId/market_chart?vs_currency=usd&days=$days"
        val dto = Http.json.decodeFromString<CoinGeckoChartDto>(Http.getString(url))
        return dto.prices.mapNotNull {
            if (it.size >= 2) PricePoint(it[0].toLong(), it[1]) else null
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
    val usd: Double = 0.0,
    @SerialName("usd_24h_change") val usd24hChange: Double? = null,
)

@Serializable
data class CoinGeckoChartDto(
    val prices: List<List<Double>> = emptyList(),
)

@Serializable
data class CoinMarketDto(
    val id: String = "",
    val symbol: String = "",
    val name: String = "",
    @SerialName("current_price") val currentPrice: Double = 0.0,
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
