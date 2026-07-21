package com.stocktracker.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealizedPnlTest {

    private val eps = 1e-9

    /** Build a bought call to close out. Only the fields the P/L math reads matter here. */
    private fun call(
        fillPrice: Double,
        contracts: Int = 1,
        strike: Double = 100.0,
    ) = CallPosition(
        symbol = "aapl",
        strike = strike,
        expiryIso = "2026-09-18",
        expiryTs = 1_789_000_000L,
        contracts = contracts,
        fillPrice = fillPrice,
        openDateIso = "2026-07-01",
    )

    @Test fun `SOLD gain — one contract, premium up 80 percent`() {
        val r = RealizedPnl.forSale(fillPrice = 1.00, exitPricePerShare = 1.80, contracts = 1)
        assertEquals(80.0, r.pnl, eps)   // (1.80 − 1.00) × 100 × 1
        assertEquals(80.0, r.pct, eps)   // (1.80 − 1.00) / 1.00 × 100
    }

    @Test fun `SOLD gain — three contracts scale the dollars, not the percent`() {
        val r = RealizedPnl.forSale(fillPrice = 2.00, exitPricePerShare = 3.00, contracts = 3)
        assertEquals(300.0, r.pnl, eps)  // (1.00) × 100 × 3
        assertEquals(50.0, r.pct, eps)   // percent is per-share, contract-independent
    }

    @Test fun `SOLD loss — premium halves`() {
        val r = RealizedPnl.forSale(fillPrice = 2.00, exitPricePerShare = 1.00, contracts = 1)
        assertEquals(-100.0, r.pnl, eps)
        assertEquals(-50.0, r.pct, eps)
    }

    @Test fun `EXPIRED worthless is a full minus 100 percent loss of the premium`() {
        val r = RealizedPnl.forExpiredWorthless(fillPrice = 1.50, contracts = 2)
        assertEquals(-300.0, r.pnl, eps)  // −(1.50 × 100 × 2)
        assertEquals(-100.0, r.pct, eps)
    }

    @Test fun `close factories stamp the right outcome and realized values`() {
        val sold = call(fillPrice = 1.00).asSold(exitPricePerShare = 1.80, closeDateIso = "2026-08-01")
        assertEquals(CallOutcome.SOLD, sold.outcome)
        assertEquals(1.80, sold.exitPricePerShare!!, eps)
        assertEquals(80.0, sold.realizedPnl!!, eps)
        assertEquals(80.0, sold.realizedPnlPct!!, eps)

        val expired = call(fillPrice = 3.00).asExpiredWorthless(closeDateIso = "2026-09-18")
        assertEquals(CallOutcome.EXPIRED, expired.outcome)
        assertNull(expired.exitPricePerShare)
        assertEquals(-300.0, expired.realizedPnl!!, eps)
        assertEquals(-100.0, expired.realizedPnlPct!!, eps)

        val exercised = call(fillPrice = 1.00).asExercised(closeDateIso = "2026-09-18")
        assertEquals(CallOutcome.EXERCISED, exercised.outcome)
        assertNull("exercised has no option-leg P/L", exercised.realizedPnl)
        assertNull(exercised.realizedPnlPct)
    }

    @Test fun `summary — win rate and total cover SOLD plus EXPIRED, excluding EXERCISED`() {
        val closed = listOf(
            call(fillPrice = 1.00).asSold(exitPricePerShare = 1.80, closeDateIso = "d"), // +80
            call(fillPrice = 2.00).asSold(exitPricePerShare = 1.00, closeDateIso = "d"), // −100
            call(fillPrice = 3.00).asExpiredWorthless(closeDateIso = "d"),               // −300
            call(fillPrice = 1.00).asExercised(closeDateIso = "d"),                      // excluded
        )
        val s = RealizedPnl.summarize(closed)

        assertEquals(4, s.closedCount)          // everything, incl. the exercised one
        assertEquals(3, s.counted)              // SOLD + EXPIRED only
        assertEquals(1, s.wins)                 // just the +80
        assertEquals(100.0 / 3.0, s.winRatePct, 1e-6)
        assertEquals(-320.0, s.totalRealized, eps) // 80 − 100 − 300
    }

    @Test fun `summary — empty set reports zero win rate without dividing by zero`() {
        val s = RealizedPnl.summarize(emptyList())
        assertEquals(0, s.closedCount)
        assertEquals(0, s.counted)
        assertEquals(0.0, s.winRatePct, eps)
        assertEquals(0.0, s.totalRealized, eps)
    }
}
