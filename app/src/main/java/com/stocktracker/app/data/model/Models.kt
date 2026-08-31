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
    val avgCost: Double? = null,         // average cost per share (for total return)
    val alerts: AssetAlerts? = null,     // price / percent threshold alerts
    val groups: List<String> = emptyList(), // named watchlists this asset belongs to
    /**
     * Starred, so the row is pinned above the sector sections.
     *
     * Defaults to FALSE, and the default is the point: being on the watchlist is not the same claim
     * as being a favourite. Every existing entry deserializes without this key and lands on false,
     * so the feature arrives empty and means something the first time it is used. A migration that
     * starred the whole list to "preserve" it would have produced a favourites section identical to
     * the watchlist -- a filter that filters nothing, which is worse than no filter at all.
     */
    val favorite: Boolean = false,
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

/**
 * A technical condition that can be armed overnight, alongside the price thresholds.
 *
 * The vocabulary is deliberately short. MACD signal crosses and Bollinger-lower-band touches were
 * considered and dropped: they are the highest-frequency and lowest-conviction triggers available,
 * and this project's own 20,768-episode study measured buying general weakness as negative. What
 * survives is the small set that marks a change of state rather than a wiggle.
 *
 * [minBars] is what the condition needs to be answerable at all. It is a count of DAILY bars, which
 * is why the evaluator chooses its own range rather than reusing whatever the chart last showed —
 * ChartRange.ALL is weekly, so an SMA(200) computed there is a 200-WEEK average wearing a 200-day
 * label.
 */
@Serializable
enum class AlertCondition(val key: String, val label: String, val minBars: Int) {
    CLOSE_ABOVE_SMA50("above_sma50", "Closes above its 50-day average", 50),
    CLOSE_BELOW_SMA50("below_sma50", "Closes below its 50-day average", 50),
    CLOSE_ABOVE_SMA200("above_sma200", "Closes above its 200-day average", 200),
    CLOSE_BELOW_SMA200("below_sma200", "Closes below its 200-day average", 200),
    CLOSE_AT_52W_HIGH("at_52w_high", "Closes at a 52-week high", 252),
}

/** Per-asset notification thresholds. Any field set to null is inactive. */
@Serializable
data class AssetAlerts(
    val priceAbove: Double? = null,   // notify when price >= this
    val priceBelow: Double? = null,   // notify when price <= this
    val percentUp: Double? = null,    // notify when day change % >= this
    val percentDown: Double? = null,  // notify when day change % <= -this
    /** Armed technical conditions. Defaulted so older backups and stored watchlists decode. */
    val conditions: Set<AlertCondition> = emptySet(),
) {
    /**
     * True when nothing is armed at all.
     *
     * `conditions` is part of this test, and that is load-bearing rather than tidy: AlertChecker
     * filters the watchlist on `!isEmpty` before evaluating anything, so an asset carrying only a
     * technical condition would have been skipped entirely — armed in the UI, never run, and no
     * error anywhere to say so.
     */
    val isEmpty: Boolean
        get() = priceAbove == null && priceBelow == null && percentUp == null &&
            percentDown == null && conditions.isEmpty()

    /** How many alerts are armed, for the badge. Same reasoning as [isEmpty]. */
    val activeCount: Int
        get() = listOfNotNull(priceAbove, priceBelow, percentUp, percentDown).size + conditions.size
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
    val volume: Double? = null,   // stocks: shares traded today; crypto: 24h USD volume
    val currency: String = "USD",
    val asOfEpochMs: Long = 0L,
    /** Yahoo classifies the symbol as an ETF (meta.instrumentType == "ETF") — drives the row accent. */
    val isEtf: Boolean = false,
    /** Last post-market (after-hours) price; null unless the symbol is in/after the post session. */
    val postMarketPrice: Double? = null,
    /** After-hours % move vs the regular-session close; null outside post-market. */
    val postMarketChangePercent: Double? = null,
    /** Yahoo's session tag ("REGULAR" | "POST" | "POSTPOST" | "CLOSED" | "PRE" | "PREPRE"); null if absent. */
    val marketState: String? = null,
) {
    val isUp: Boolean get() = change >= 0.0
}

/** CBOE Volatility Index snapshot (^VIX). Higher = more expected volatility ("fear"). */
data class VixQuote(
    val value: Double,
    val change: Double,
    val changePercent: Double,
) {
    val zone: VixZone get() = VixZone.forValue(value)
    /** VIX up = more fear (bad); down = calmer (good). Sentiment is inverted vs a normal ticker. */
    val calmer: Boolean get() = change <= 0.0
}

/** Risk bands for the VIX fear gauge. [ceiling] is the band's exclusive upper bound. */
enum class VixZone(val label: String, val ceiling: Double) {
    CALM("Calm", 15.0),
    NORMAL("Normal", 20.0),
    ELEVATED("Elevated", 30.0),
    HIGH("High", 40.0),
    EXTREME("Extreme", Double.MAX_VALUE);

    companion object {
        fun forValue(v: Double): VixZone = entries.first { v < it.ceiling }
    }
}

/** A single (time, price) sample for charts / sparklines. [extended] = pre/post-market. */
@Serializable
data class PricePoint(
    val epochMs: Long,
    val price: Double,
    val extended: Boolean = false,
    val volume: Double? = null,
    /**
     * The bar's true extremes, when the source reports them. Null means closes only (CoinGecko).
     *
     * [price] is the bar's CLOSE, and a close series has no memory of what happened inside the bar.
     * That is invisible on the line itself but wrong for a high/low marker, because each chart range
     * asks Yahoo for a different bar size — 1D in 1-minute bars, 1W in 5-minute, 1M in 30-minute. The
     * same trading day therefore yields a different "low" per range, and the wider view can report a
     * HIGHER low than the narrower one it contains: measured on GME 2026-08-11, 1D showed $18.59 and
     * 1W showed $18.70 for a window that includes it. Bar extremes nest the way closes do not — a
     * 5-minute bar's low IS the lowest of its five 1-minute lows.
     */
    val high: Double? = null,
    val low: Double? = null,
    /**
     * The bar's OPEN. Yahoo has always returned it and this app has always discarded it.
     *
     * Null carries the same meaning as [high]/[low]: the source did not report one. It is NOT
     * defaulted to [price] — a bar whose open equals its close is a doji, a specific and confident
     * reading about a session that fought to a standstill, and inventing one is exactly the class of
     * claim this file is careful not to make.
     */
    val open: Double? = null,
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
