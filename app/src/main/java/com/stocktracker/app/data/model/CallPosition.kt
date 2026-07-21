package com.stocktracker.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A manually-entered long-call position (OC-3). The user buys the call on Fidelity by hand, then
 * records the fill here to track live unrealized P/L — this is a TRACKER, not an order.
 *
 * [fillPrice] is the premium PAID PER SHARE (an option covers 100 shares), so the cost basis — which
 * is also the whole max loss on a long call — is `fillPrice × 100 × contracts`. [expiryTs] is the
 * option chain's own expiry timestamp (epoch-seconds) when tracked from a suggestion, else UTC-midnight
 * of the chosen expiry date; it is what the re-pricing endpoint keys on.
 */
@Serializable
data class CallPosition(
    val id: String = UUID.randomUUID().toString(),
    val symbol: String,
    val contractSymbol: String = "",
    val type: String = "call",
    val strike: Double,
    val expiryIso: String,
    val expiryTs: Long,
    val contracts: Int,
    val fillPrice: Double,
    val openDateIso: String,
    val takeProfitPct: Double? = null, // e.g. 80.0 → plan to close at +80%
    val stopPct: Double? = null,       // e.g. 50.0 → plan to bail at −50%
    val notes: String? = null,
) {
    /** What you paid = premium × 100 × contracts. On a bought call this is also the most you can lose. */
    val costBasis: Double get() = fillPrice * 100.0 * contracts

    /** A long call breaks even at the strike plus the premium paid per share. */
    val breakeven: Double get() = strike + fillPrice
}
