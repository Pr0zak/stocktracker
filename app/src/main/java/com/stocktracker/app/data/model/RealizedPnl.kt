package com.stocktracker.app.data.model

/**
 * Pure realized-P&L math for closed call positions (OC-5). Kept free of Android/UI so it can be
 * unit-tested in isolation (see RealizedPnlTest).
 *
 * A long call is measured in premium PER SHARE; one contract covers 100 shares, so every dollar of
 * premium move is worth `100 × contracts` dollars.
 */
object RealizedPnl {

    /** Realized result on the option leg: dollars, and percent of the premium paid. */
    data class Realized(val pnl: Double, val pct: Double)

    /**
     * Sell-to-close (SOLD): you buy at [fillPrice] premium/share and sell at [exitPricePerShare].
     *
     *  - pnl = (exit − fill) × 100 × contracts
     *  - pct = (exit − fill) / fill × 100
     *
     * Passing an exit of 0 gives the expired-worthless result (−100%); [forExpiredWorthless] is the
     * named shortcut for that.
     */
    fun forSale(fillPrice: Double, exitPricePerShare: Double, contracts: Int): Realized {
        val pnl = (exitPricePerShare - fillPrice) * 100.0 * contracts
        val pct = if (fillPrice != 0.0) (exitPricePerShare - fillPrice) / fillPrice * 100.0 else 0.0
        return Realized(pnl, pct)
    }

    /** Expired worthless: the premium went to $0, so you lose all of it — a clean −100%. */
    fun forExpiredWorthless(fillPrice: Double, contracts: Int): Realized =
        forSale(fillPrice, 0.0, contracts)

    /** Rolled-up stats for the closed-calls history. */
    data class Summary(
        /** Every closed position, including exercised ones. */
        val closedCount: Int,
        /** Positions with a realized option P/L — SOLD + EXPIRED (exercised leg is excluded). */
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
     * EXERCISED positions have no option-leg P/L (the value moved into the shares), so they count toward
     * [closedCount] but are excluded from the win-rate and total.
     */
    fun summarize(positions: List<ClosedCallPosition>): Summary {
        val counted = positions.filter { it.outcome != CallOutcome.EXERCISED && it.realizedPnl != null }
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
