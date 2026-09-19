package com.stocktracker.app.data.model

/**
 * Pure realized-P&L math for closed option positions (OC-5, extended by MONEY-3 to short positions).
 * Kept free of Android/UI so it can be unit-tested in isolation (see RealizedPnlTest).
 *
 * An option is measured in premium PER SHARE; one contract covers 100 shares, so every dollar of
 * premium move is worth `100 × contracts` dollars.
 */
object RealizedPnl {

    /** Realized result on the option leg: dollars, and percent of the premium that changed hands. */
    data class Realized(val pnl: Double, val pct: Double)

    /**
     * Close out a tracked position: [fillPrice] is the premium/share it was opened at, [exitPricePerShare]
     * the premium/share it was closed at, [contracts] the size, and [side] which way the trade runs.
     *
     *  - LONG (you paid [fillPrice], then sold at [exitPricePerShare] — "sell to close"):
     *      pnl = (exit − fill) × 100 × contracts
     *  - SHORT (you collected [fillPrice], then paid [exitPricePerShare] to buy it back — "buy to
     *    close"): profit is the premium GIVING BACK LESS than was collected, so the diff flips —
     *      pnl = (fill − exit) × 100 × contracts
     *
     * Both express pct as the diff over the ORIGINAL premium ([fillPrice]) — the number the position
     * was sized against — so +80% always means "moved 80% of the entry premium in your favor" on
     * either side.
     *
     * Passing an exit of 0 gives the expired-worthless result: −100% on a LONG (the premium paid is a
     * total loss) and +100% on a SHORT (the premium collected is a total, and final, gain — nothing
     * left to buy back); [forExpiredWorthless] is the named shortcut for that.
     */
    fun forSale(
        fillPrice: Double,
        exitPricePerShare: Double,
        contracts: Int,
        side: PositionSide = PositionSide.LONG,
    ): Realized {
        val diff = when (side) {
            PositionSide.LONG -> exitPricePerShare - fillPrice
            PositionSide.SHORT -> fillPrice - exitPricePerShare
        }
        val pnl = diff * 100.0 * contracts
        val pct = if (fillPrice != 0.0) diff / fillPrice * 100.0 else 0.0
        return Realized(pnl, pct)
    }

    /**
     * Expired worthless: the premium went to $0. On a LONG that is a clean −100% (you paid it and got
     * nothing back); on a SHORT it is a clean +100% (you collected it and owe nothing back — the best
     * outcome a premium seller can have).
     */
    fun forExpiredWorthless(fillPrice: Double, contracts: Int, side: PositionSide = PositionSide.LONG): Realized =
        forSale(fillPrice, 0.0, contracts, side)

    /** Rolled-up stats for the closed-calls history. */
    data class Summary(
        /** Every closed position, including exercised/assigned ones. */
        val closedCount: Int,
        /** Positions with a realized option P/L — SOLD + EXPIRED (exercised/assigned leg is excluded). */
        val counted: Int,
        /** [counted] positions whose realized P/L is a gain (> 0). */
        val wins: Int,
        /** wins / counted × 100 (0 when nothing is counted yet). */
        val winRatePct: Double,
        /** Sum of realized P/L over [counted] positions. */
        val totalRealized: Double,
    )

    /**
     * Summarize a set of closed positions. Win-rate and total are computed over SOLD + EXPIRED only —
     * EXERCISED (long) and ASSIGNED (short) positions have no option-leg P/L (the value moved into the
     * shares), so they count toward [closedCount] but are excluded from the win-rate and total.
     */
    fun summarize(positions: List<ClosedCallPosition>): Summary {
        val counted = positions.filter {
            it.outcome != CallOutcome.EXERCISED && it.outcome != CallOutcome.ASSIGNED && it.realizedPnl != null
        }
        val wins = counted.count { (it.realizedPnl ?: 0.0) > 0.0 }
        val total = counted.sumOf { it.realizedPnl ?: 0.0 }
        val winRate = if (counted.isEmpty()) 0.0 else wins.toDouble() / counted.size * 100.0
        return Summary(
            closedCount = positions.size,
            counted = counted.size,
            wins = wins,
            winRatePct = winRate,
            totalRealized = total,
        )
    }
}
