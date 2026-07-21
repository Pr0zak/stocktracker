package com.stocktracker.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** How a tracked call left the book (OC-5). */
@Serializable
enum class CallOutcome {
    /** Sold to close — you sold the option back for a premium (the beginner-normal exit). */
    SOLD,

    /** Exercised — you turned the option into 100 × contracts shares at the strike. */
    EXERCISED,

    /** Let it expire worthless — the whole premium is lost (a −100% result). */
    EXPIRED,
}

/**
 * A tracked long call that has been closed out (OC-5). Carries the original [CallPosition] identity
 * fields plus how it ended:
 *
 *  - [outcome] — SOLD / EXERCISED / EXPIRED.
 *  - [closeDateIso] — the day it was recorded closed.
 *  - [exitPricePerShare] — the sell premium PER SHARE for a SOLD close; null for EXPIRED-worthless
 *    (premium went to $0) and for EXERCISED (there's no sale — the value rolls into the shares).
 *  - [realizedPnl] / [realizedPnlPct] — the realized result on the OPTION leg. Null for EXERCISED,
 *    because the option's value is now baked into the share cost basis (strike + premium paid), so
 *    fabricating an option P/L would double-count it.
 *
 * The realized numbers are always produced by [RealizedPnl] so the math lives in one pure, tested place.
 */
@Serializable
data class ClosedCallPosition(
    val id: String = UUID.randomUUID().toString(),
    // --- original position identity (mirrors CallPosition) ---
    val symbol: String,
    val contractSymbol: String = "",
    val type: String = "call",
    val strike: Double,
    val expiryIso: String,
    val expiryTs: Long,
    val contracts: Int,
    val fillPrice: Double, // premium PAID per share when opened
    val openDateIso: String,
    val notes: String? = null,
    // --- close details ---
    val outcome: CallOutcome,
    val closeDateIso: String,
    val exitPricePerShare: Double? = null,
    val realizedPnl: Double? = null,
    val realizedPnlPct: Double? = null,
) {
    /** What you paid to open = premium × 100 × contracts. */
    val costBasis: Double get() = fillPrice * 100.0 * contracts

    /** Shares you now hold after exercising (0 for the other outcomes). */
    val exercisedShares: Int get() = if (outcome == CallOutcome.EXERCISED) 100 * contracts else 0
}

/** Record this position as SOLD to close at [exitPricePerShare] premium/share on [closeDateIso]. */
fun CallPosition.asSold(exitPricePerShare: Double, closeDateIso: String): ClosedCallPosition {
    val r = RealizedPnl.forSale(fillPrice, exitPricePerShare, contracts)
    return closedBase(closeDateIso).copy(
        outcome = CallOutcome.SOLD,
        exitPricePerShare = exitPricePerShare,
        realizedPnl = r.pnl,
        realizedPnlPct = r.pct,
    )
}

/** Record this position as EXPIRED worthless on [closeDateIso] — the full premium is lost (−100%). */
fun CallPosition.asExpiredWorthless(closeDateIso: String): ClosedCallPosition {
    val r = RealizedPnl.forExpiredWorthless(fillPrice, contracts)
    return closedBase(closeDateIso).copy(
        outcome = CallOutcome.EXPIRED,
        exitPricePerShare = null, // premium went to zero — nothing sold
        realizedPnl = r.pnl,
        realizedPnlPct = r.pct,
    )
}

/**
 * Record this position as EXERCISED on [closeDateIso]. No option P/L is stored — you now own
 * 100 × contracts shares at the strike, with a cost basis of (strike + premium paid) per share. That
 * explanation is written into the note so the history is self-describing.
 */
fun CallPosition.asExercised(closeDateIso: String): ClosedCallPosition {
    val shares = 100 * contracts
    val perShareBasis = strike + fillPrice
    val note = "Exercised: you now own $shares shares of ${symbol.uppercase()} at the \$${plain(strike)} strike " +
        "(cost basis ≈ \$${plain(perShareBasis)}/share = strike + premium paid). The option's value rolls into " +
        "the shares, so no separate option P/L is recorded."
    return closedBase(closeDateIso).copy(
        outcome = CallOutcome.EXERCISED,
        exitPricePerShare = null,
        realizedPnl = null, // not applicable — see note
        realizedPnlPct = null,
        notes = listOfNotNull(notes?.takeIf { it.isNotBlank() }, note).joinToString("\n"),
    )
}

/** Copy the identity fields across; the caller fills in the outcome-specific bits via [copy]. */
private fun CallPosition.closedBase(closeDateIso: String) = ClosedCallPosition(
    symbol = symbol,
    contractSymbol = contractSymbol,
    type = type,
    strike = strike,
    expiryIso = expiryIso,
    expiryTs = expiryTs,
    contracts = contracts,
    fillPrice = fillPrice,
    openDateIso = openDateIso,
    notes = notes,
    outcome = CallOutcome.SOLD, // placeholder — overwritten by the caller's copy()
    closeDateIso = closeDateIso,
)

private fun plain(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v)
