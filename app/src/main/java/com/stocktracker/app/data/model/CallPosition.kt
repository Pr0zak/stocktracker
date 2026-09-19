package com.stocktracker.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Which side of the contract this position is (MONEY-3): the buyer (LONG — pays a premium, has the
 * right) or the writer/seller (SHORT — collects a premium, carries the obligation).
 *
 * Defaults to [LONG] wherever a [CallPosition] is decoded without this key, which is exactly right for
 * every position this app tracked before MONEY-3: OC-3 only ever recorded a bought call, so a stored
 * position with no `side` field IS a long call, not an ambiguous default standing in for one.
 */
@Serializable
enum class PositionSide { LONG, SHORT }

/**
 * A manually-entered option position (OC-3, extended by MONEY-3 to the wheel's short legs). The user
 * trades the contract on Fidelity by hand, then records the fill here to track live unrealized P/L —
 * this is a TRACKER, not an order.
 *
 * [fillPrice] is the premium PER SHARE that changed hands (an option covers 100 shares) — PAID, for a
 * [PositionSide.LONG] position (a debit; [costBasis] is also the whole max loss), or COLLECTED, for a
 * [PositionSide.SHORT] one (a credit; the max loss is uncapped for a short call and `strike × 100 ×
 * contracts − costBasis` for a short put — [costBasis] is never the max loss on a short). [expiryTs] is
 * the option chain's own expiry timestamp (epoch-seconds) when tracked from a suggestion, else
 * UTC-midnight of the chosen expiry date; it is what the re-pricing endpoint keys on.
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
    /** LONG (bought) or SHORT (sold) — see [PositionSide]. Missing on disk means LONG: see its doc. */
    val side: PositionSide = PositionSide.LONG,
) {
    /**
     * Premium × 100 × contracts that changed hands when this was opened — a debit you PAID (LONG:
     * also the whole max loss) or a credit you COLLECTED (SHORT: see the class doc — this is never
     * the max loss on a short).
     */
    val costBasis: Double get() = fillPrice * 100.0 * contracts

    /**
     * The underlying price at which this position is breakeven. The formula is the same shape for
     * both sides of a given [type] — a long call and a short call share one breakeven price, they just
     * sit on opposite sides of the trade at it — so only [type] (call vs put) changes the sign, never
     * [side].
     */
    val breakeven: Double get() = if (type.equals("put", ignoreCase = true)) strike - fillPrice else strike + fillPrice
}

/**
 * Shares of [symbol] already promised away by an OPEN short call (MONEY-3) — 100 per contract.
 *
 * Covered-call eligibility must subtract this from the raw share count on the holding, or the same 100
 * shares can be promised to two different buyers: sell one covered call, come back to the symbol before
 * it closes, and the suggester would offer to sell a second one against shares that are no longer free.
 */
fun List<CallPosition>.sharesCommittedToShortCalls(symbol: String): Int = filter {
    it.side == PositionSide.SHORT && it.type.equals("call", ignoreCase = true) &&
        it.symbol.equals(symbol, ignoreCase = true)
}.sumOf { 100 * it.contracts }
