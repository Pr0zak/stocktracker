package com.stocktracker.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AssetType { STOCK, CRYPTO }

/** A tracked instrument. [coinGeckoId] is set for crypto (e.g. "bitcoin"). */
@Serializable
data class Asset(
    val symbol: String,          // "AAPL", "BTC"
    val type: AssetType,
    val displayName: String,     // "Apple Inc.", "Bitcoin"
    val coinGeckoId: String? = null,
    val shares: Double? = null,          // user-owned quantity (for position value)
    val alerts: AssetAlerts? = null,     // price / percent threshold alerts
) {
    /**
     * Stable identity. Crypto is keyed by CoinGecko id (falling back to ticker) so distinct coins
     * that reuse a ticker symbol don't collide; stocks are keyed by symbol.
     */
    val id: String get() = when (type) {
        AssetType.CRYPTO -> "CRYPTO:${coinGeckoId ?: symbol.uppercase()}"
        AssetType.STOCK -> "STOCK:${symbol.uppercase()}"
    }
}

/** Per-asset notification thresholds. Any field set to null is inactive. */
@Serializable
data class AssetAlerts(
    val priceAbove: Double? = null,   // notify when price >= this
    val priceBelow: Double? = null,   // notify when price <= this
    val percentUp: Double? = null,    // notify when day change % >= this
    val percentDown: Double? = null,  // notify when day change % <= -this
) {
    val isEmpty: Boolean
        get() = priceAbove == null && priceBelow == null && percentUp == null && percentDown == null
}

/** A point-in-time price snapshot. */
@Serializable
data class Quote(
    val symbol: String,
    val price: Double,
    val change: Double,          // absolute change over the day
    val changePercent: Double,   // percent change over the day
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val prevClose: Double? = null,
    val currency: String = "USD",
    val asOfEpochMs: Long = 0L,
) {
    val isUp: Boolean get() = change >= 0.0
}

/** A single (time, price) sample for charts / sparklines. [extended] = pre/post-market. */
@Serializable
data class PricePoint(
    val epochMs: Long,
    val price: Double,
    val extended: Boolean = false,
    val volume: Double? = null,
)

/** Chart time ranges shown on the detail screen. */
enum class ChartRange(val label: String) {
    DAY("1D"), WEEK("1W"), MONTH("1M"), QUARTER("3M"), YEAR("1Y"), THREE_YEAR("3Y"), ALL("ALL")
}

/** A symbol-search hit from Finnhub (stocks) or CoinGecko (crypto). */
data class SearchResult(
    val symbol: String,
    val name: String,
    val type: AssetType,
    val coinGeckoId: String? = null,
) {
    fun toAsset() = Asset(symbol = symbol, type = type, displayName = name, coinGeckoId = coinGeckoId)
}
