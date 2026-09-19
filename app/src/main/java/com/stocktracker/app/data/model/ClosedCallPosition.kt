package com.stocktracker.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** How a tracked position left the book (OC-5, extended by MONEY-3 for the wheel's short legs). */
@Serializable
enum class CallOutcome {
    /**
     * Closed by trading the option back: SOLD to close a LONG, or BOUGHT to close a SHORT (the
     * beginner-normal exit either way).
     */
    SOLD,

    /**
     * Exercised — a LONG holder turned the option into 100 × contracts shares at the strike. Never
     * used for a SHORT position; see [ASSIGNED] for the mirror event on that side.
     */
    EXERCISED,

    /**
     * Assigned — the counterparty exercised against a SHORT writer: a short put assigned buys 100 ×
     * contracts shares at the strike (less the premium collected); a short call assigned sells them
     * away at the strike. The option-writer's mirror of [EXERCISED] — never used for a LONG position.
     */
    ASSIGNED,

    /**
     * Let it expire worthless. On a LONG the whole premium paid is lost (−100%); on a SHORT the whole
     * premium collected is kept (+100%, the best outcome a seller can have) — see [RealizedPnl].
     */
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
 *  - [stopPct] / [takeProfitPct] — the exit plan the position was OPENED with, carried across the
 *    close (SWT-6).
 *
 * The realized numbers are always produced by [RealizedPnl] so the math lives in one pure, tested place.
 *
 * WHY THE STOP IS COPIED HERE. R — (exit − entry) / (entry − stop) — is the only unit that measures the
 * decision rather than the position size, and its denominator is the risk defined AT ENTRY. That number
 * exists on the open [CallPosition] and nowhere else: it cannot be recovered from the price history,
 * the P/L or the notes once the position is gone. Before SWT-6 the close threw it away, so EVERY
 * POSITION CLOSED BEFORE THIS FIELD EXISTED IS PERMANENTLY UNSCOREABLE IN R — not zero-risk, not
 * break-even, unknowable. Those records decode with [stopPct] = null (the key is simply absent from
 * the stored JSON), and [RiskMultiple] counts them as `unscoreable` instead of dropping them or
 * reading them as 0R.
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
    // --- the exit plan this position was OPENED with; null on records closed before SWT-6 ---
    val takeProfitPct: Double? = null, // e.g. 80.0 → the plan was to close at +80%
    val stopPct: Double? = null,       // e.g. 50.0 → the risk taken was 50% of the premium. R's denominator.
    // --- close details ---
    val outcome: CallOutcome,
    val closeDateIso: String,
    val exitPricePerShare: Double? = null,
    val realizedPnl: Double? = null,
    val realizedPnlPct: Double? = null,
    /** LONG or SHORT — see [PositionSide]. Missing on disk means LONG, same reasoning as [CallPosition.side]. */
    val side: PositionSide = PositionSide.LONG,
) {
    /** Premium × 100 × contracts that changed hands when opened — paid (LONG) or collected (SHORT). */
    val costBasis: Double get() = fillPrice * 100.0 * contracts

    /** Shares moved by an exercise/assignment (0 for the other outcomes) — acquired (EXERCISED, or
     *  ASSIGNED on a short put) or given up (ASSIGNED on a short call; see [CallPosition.type]). */
    val exercisedShares: Int
        get() = if (outcome == CallOutcome.EXERCISED || outcome == CallOutcome.ASSIGNED) 100 * contracts else 0
}

/**
 * Record this position closed at [exitPricePerShare] premium/share on [closeDateIso] — SOLD to close a
 * LONG, or BOUGHT to close a SHORT (same [CallOutcome.SOLD] outcome either way; [side] decides the sign
 * of the realized P/L via [RealizedPnl.forSale]).
 */
fun CallPosition.asSold(exitPricePerShare: Double, closeDateIso: String): ClosedCallPosition {
    val r = RealizedPnl.forSale(fillPrice, exitPricePerShare, contracts, side)
    return closedBase(closeDateIso).copy(
        outcome = CallOutcome.SOLD,
        exitPricePerShare = exitPricePerShare,
        realizedPnl = r.pnl,
        realizedPnlPct = r.pct,
    )
}

/**
 * Record this position as EXPIRED worthless on [closeDateIso]. The full premium paid is lost on a LONG
 * (−100%); the full premium collected is kept on a SHORT (+100% — see [RealizedPnl.forExpiredWorthless]).
 */
fun CallPosition.asExpiredWorthless(closeDateIso: String): ClosedCallPosition {
    val r = RealizedPnl.forExpiredWorthless(fillPrice, contracts, side)
    return closedBase(closeDateIso).copy(
        outcome = CallOutcome.EXPIRED,
        exitPricePerShare = null, // premium went to zero — nothing traded
        realizedPnl = r.pnl,
        realizedPnlPct = r.pct,
    )
}

/**
 * Record this LONG position as EXERCISED on [closeDateIso]. No option P/L is stored — you now own
 * 100 × contracts shares at the strike, with a cost basis of (strike + premium paid) per share. That
 * explanation is written into the note so the history is self-describing. See [asAssigned] for the
 * SHORT-side mirror of this event.
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

/**
 * Record this SHORT position as ASSIGNED on [closeDateIso] (MONEY-3) — the counterparty exercised
 * against you. No option P/L is stored here, for the same reason as [asExercised]: the value rolls
 * into a share transaction rather than a separate option-leg result.
 *
 *  - A short PUT assigned means you BUY $shares shares at the strike, net of the premium you already
 *    collected — cost basis (strike − premium collected) per share, same shape as a long call's
 *    (strike + premium paid), mirrored because you received instead of paid.
 *  - A short CALL assigned means $shares shares are CALLED AWAY at the strike — a disposal, not an
 *    acquisition. [com.stocktracker.app.ui.calls.CallsViewModel.markAssigned] is what turns this into
 *    the actual lot on the watchlist holding (a positive lot for a put, a negative one for a call).
 */
fun CallPosition.asAssigned(closeDateIso: String): ClosedCallPosition {
    val shares = 100 * contracts
    val isPut = type.equals("put", ignoreCase = true)
    val note = if (isPut) {
        val perShareBasis = strike - fillPrice
        "Assigned: you now own $shares shares of ${symbol.uppercase()} at the \$${plain(strike)} strike " +
            "(cost basis ≈ \$${plain(perShareBasis)}/share = strike − the premium you collected). The option's " +
            "value rolls into the shares, so no separate option P/L is recorded."
    } else {
        "Assigned: $shares shares of ${symbol.uppercase()} were called away at the \$${plain(strike)} strike " +
            "(you already kept the \$${plain(fillPrice)}/share premium you collected when you sold the call). " +
            "No separate option P/L is recorded here — the strike sale is on your ${symbol.uppercase()} holding."
    }
    return closedBase(closeDateIso).copy(
        outcome = CallOutcome.ASSIGNED,
        exitPricePerShare = null,
        realizedPnl = null, // not applicable — see note
        realizedPnlPct = null,
        notes = listOfNotNull(notes?.takeIf { it.isNotBlank() }, note).joinToString("\n"),
    )
}

/**
 * Copy the identity fields across; the caller fills in the outcome-specific bits via [copy].
 *
 * Every close funnels through here on purpose. A risk field threaded through three of four close paths
 * would be worse than no field at all — the gap would be invisible, and the trades that fell through it
 * would look like ordinary risk-unknown history rather than a bug.
 */
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
    takeProfitPct = takeProfitPct,
    stopPct = stopPct, // the risk defined at entry — without this the trade can never be scored in R
    outcome = CallOutcome.SOLD, // placeholder — overwritten by the caller's copy()
    closeDateIso = closeDateIso,
    side = side,
)

private fun plain(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v)
