package com.stocktracker.shared

import kotlinx.serialization.Serializable

/**
 * A point-in-time price snapshot.
 *
 * Moved here from `com.stocktracker.app.data.model` (WGT-7) so the Wear module can decode the same
 * payload the phone widgets store and feed it into the same [tickerDisplay] the phone uses -- the
 * type itself carries no Android dependency, so relocating it costs the phone app nothing. `:app`
 * keeps a `typealias Quote = com.stocktracker.shared.Quote` at the original location (see
 * `data/model/Models.kt`) so every existing import/call site there is unchanged.
 */
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
