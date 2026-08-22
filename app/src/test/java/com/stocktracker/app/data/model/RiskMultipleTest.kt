package com.stocktracker.app.data.model

import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-multiple math and the track record built on it (SWT-6).
 *
 * THE INCIDENT THIS GUARDS. R = (exit − entry) / (entry − stop), and its denominator is the risk that
 * was defined AT ENTRY. `CallPosition` recorded that risk as `stopPct`; `ClosedCallPosition` used to
 * throw it away on close, so every trade this app had ever closed was permanently unscoreable in R —
 * the number cannot be reconstructed from price history, P/L or notes. Two failure modes follow from
 * that and are pinned below: a position with no stop recorded must come back null and NEVER 0.0 (0R is
 * a real claim — exited exactly at entry), and `stopPct` is a PERCENT OF THE PREMIUM, not a price, so
 * feeding 50.0 in as a stop price would score a $2.00 option against a $50 stop and report a confident
 * wrong answer.
 */
class RiskMultipleTest {

    private val eps = 1e-9

    /** A closed call. Defaults are the worked example throughout: $2.00 premium, 50% stop = $1.00 risk. */
    private fun closed(
        fillPrice: Double = 2.00,
        exit: Double? = 3.00,
        stopPct: Double? = 50.0,
        outcome: CallOutcome = CallOutcome.SOLD,
        closeDateIso: String = "2026-08-01",
        symbol: String = "aapl",
    ) = ClosedCallPosition(
        symbol = symbol,
        strike = 100.0,
        expiryIso = "2026-09-18",
        expiryTs = 1_789_000_000L,
        contracts = 1,
        fillPrice = fillPrice,
        openDateIso = "2026-07-01",
        stopPct = stopPct,
        outcome = outcome,
        closeDateIso = closeDateIso,
        exitPricePerShare = exit,
        realizedPnl = exit?.let { RealizedPnl.forSale(fillPrice, it, 1).pnl },
        realizedPnlPct = exit?.let { RealizedPnl.forSale(fillPrice, it, 1).pct },
    )

    // ------------------------------------------------------------------ rMultiple, on prices

    @Test fun `an exit at exactly the stop distance below entry is minus one R`() {
        // entry $2.00, stop $1.00 → risk $1.00. exit $1.00 → (1.00 − 2.00) / 1.00 = −1.0R
        assertEquals(-1.0, RiskMultiple.rMultiple(entry = 2.00, exit = 1.00, stop = 1.00)!!, eps)
    }

    @Test fun `an exit twice the risk above entry is plus two R`() {
        // entry $2.00, stop $1.00 → risk $1.00. exit $4.00 → (4.00 − 2.00) / 1.00 = +2.0R
        assertEquals(2.0, RiskMultiple.rMultiple(entry = 2.00, exit = 4.00, stop = 1.00)!!, eps)
    }

    @Test fun `an exit back at the entry is zero R, which is a real result and not an absent one`() {
        assertEquals(0.0, RiskMultiple.rMultiple(entry = 2.00, exit = 2.00, stop = 1.00)!!, eps)
    }

    @Test fun `the risk, not the position size, sets the scale`() {
        // Same +$1.00 move. A $0.50 stop distance doubles the R of a $1.00 one — that is the whole point.
        assertEquals(1.0, RiskMultiple.rMultiple(entry = 2.00, exit = 3.00, stop = 1.00)!!, eps)
        assertEquals(2.0, RiskMultiple.rMultiple(entry = 2.00, exit = 3.00, stop = 1.50)!!, eps)
    }

    @Test fun `a missing stop is null and never zero`() {
        assertNull(RiskMultiple.rMultiple(entry = 2.00, exit = 3.00, stop = null))
    }

    @Test fun `a stop at the entry is null rather than infinity`() {
        val r = RiskMultiple.rMultiple(entry = 2.00, exit = 3.00, stop = 2.00)
        assertNull("a zero-risk denominator must not produce Infinity", r)
    }

    @Test fun `a stop above the entry is null rather than a sign-flipped R`() {
        // Negative risk would turn this winner into −0.5R. Long-only: the stop belongs below the entry.
        assertNull(RiskMultiple.rMultiple(entry = 2.00, exit = 3.00, stop = 4.00))
    }

    @Test fun `non-finite inputs are null rather than poisoning every aggregate downstream`() {
        assertNull(RiskMultiple.rMultiple(entry = Double.NaN, exit = 3.00, stop = 1.00))
        assertNull(RiskMultiple.rMultiple(entry = 2.00, exit = Double.POSITIVE_INFINITY, stop = 1.00))
        assertNull(RiskMultiple.rMultiple(entry = 2.00, exit = 3.00, stop = Double.NaN))
    }

    // ------------------------------------------------------------------ stopPct is a PERCENT

    @Test fun `a 50 percent stop on a 2 dollar premium is a 1 dollar stop, so a 3 dollar exit is plus one R`() {
        // The case the whole conversion exists for. 50% of $2.00 = $1.00 risk; exit $3.00 is +$1.00 = +1R.
        assertEquals(1.00, RiskMultiple.stopPriceFromPct(entry = 2.00, stopPct = 50.0)!!, eps)
        assertEquals(1.0, RiskMultiple.rMultipleFromStopPct(entry = 2.00, exit = 3.00, stopPct = 50.0)!!, eps)
    }

    @Test fun `a percent read as a price would score wildly differently - guards the conversion`() {
        // If 50.0 leaked through as a stop PRICE on a $2.00 option the denominator goes negative and
        // rMultiple refuses it. The named conversion is what keeps a plausible wrong number off screen.
        assertNull(RiskMultiple.rMultiple(entry = 2.00, exit = 3.00, stop = 50.0))
    }

    @Test fun `a 25 percent stop makes the same dollar gain four times the R of a 100 percent stop`() {
        // 25% of $2.00 = $0.50 risk → exit $3.00 is +$1.00 = +2R.
        assertEquals(2.0, RiskMultiple.rMultipleFromStopPct(entry = 2.00, exit = 3.00, stopPct = 25.0)!!, eps)
        // 100% = risk the whole premium ($2.00) → the same exit is only +0.5R.
        assertEquals(0.5, RiskMultiple.rMultipleFromStopPct(entry = 2.00, exit = 3.00, stopPct = 100.0)!!, eps)
    }

    @Test fun `a stop percent over 100 is null because a bought call cannot risk more than the premium`() {
        assertNull(RiskMultiple.stopPriceFromPct(entry = 2.00, stopPct = 150.0))
        assertNull(RiskMultiple.rMultipleFromStopPct(entry = 2.00, exit = 3.00, stopPct = 150.0))
    }

    @Test fun `a zero or negative stop percent is null, not a free trade`() {
        assertNull(RiskMultiple.stopPriceFromPct(entry = 2.00, stopPct = 0.0))
        assertNull(RiskMultiple.stopPriceFromPct(entry = 2.00, stopPct = -10.0))
    }

    // ------------------------------------------------------------------ scoring a closed position

    @Test fun `a sold close with its stop recorded scores in R`() {
        assertEquals(1.0, RiskMultiple.rFor(closed(fillPrice = 2.00, exit = 3.00, stopPct = 50.0))!!, eps)
    }

    @Test fun `a close with no stop recorded is unscoreable, not zero R`() {
        val legacy = closed(stopPct = null)
        assertNull("a pre-SWT-6 close must read as risk-unknown", RiskMultiple.rFor(legacy))
    }

    @Test fun `expiring worthless is scored at a zero exit and blows through the stop`() {
        // $2.00 premium, 50% stop ($1.00 risk), premium went to $0 → (0 − 2) / 1 = −2R. Not −1R:
        // the plan said bail at −50% and the position was held to zero.
        val expired = closed(fillPrice = 2.00, exit = null, stopPct = 50.0, outcome = CallOutcome.EXPIRED)
        assertEquals(-2.0, RiskMultiple.rFor(expired)!!, eps)
    }

    @Test fun `an exercised close has no option-leg exit so it cannot be scored in R`() {
        val exercised = closed(exit = null, outcome = CallOutcome.EXERCISED)
        assertNull(RiskMultiple.rFor(exercised))
    }

    // ------------------------------------------------------------------ aggregates

    /**
     * Five scored closes, all $2.00 premium with a 50% stop ($1.00 risk), so R = exit − 2.00:
     *   +2R, −1R, −1R, +3R, −1.5R   (dated in that close order)
     */
    private fun handComputedRun() = listOf(
        closed(exit = 4.00, closeDateIso = "2026-08-01"),  // +2R
        closed(exit = 1.00, closeDateIso = "2026-08-02"),  // −1R
        closed(exit = 1.00, closeDateIso = "2026-08-03"),  // −1R
        closed(exit = 5.00, closeDateIso = "2026-08-04"),  // +3R
        closed(exit = 0.50, closeDateIso = "2026-08-05"),  // −1.5R
    )

    @Test fun `total and expectancy over a hand-computed run`() {
        val a = RiskMultiple.aggregate(handComputedRun())
        assertEquals(5, a.scored)
        assertEquals(0, a.unscoreable)
        assertEquals(1.5, a.totalR!!, eps)   // 2 − 1 − 1 + 3 − 1.5
        assertEquals(0.3, a.avgR!!, eps)     // 1.5 / 5
    }

    @Test fun `profit factor is gross R won over gross R lost`() {
        val a = RiskMultiple.aggregate(handComputedRun())
        assertEquals(5.0 / 3.5, a.profitFactor!!, 1e-9) // wins 2+3 = 5; losses 1+1+1.5 = 3.5
    }

    @Test fun `largest win and loss and the longest losing streak`() {
        val a = RiskMultiple.aggregate(handComputedRun())
        assertEquals(3.0, a.largestWinR!!, eps)
        assertEquals(-1.5, a.largestLossR!!, eps)
        assertEquals(2, a.longestLosingStreak) // the −1R, −1R pair
    }

    @Test fun `max drawdown walks the equity curve in R`() {
        // cumulative: 2.0, 1.0, 0.0, 3.0, 1.5 — peaks 2.0, 2.0, 2.0, 3.0, 3.0
        // drawdowns:  0.0, 1.0, 2.0, 0.0, 1.5 → deepest is 2.0R
        val a = RiskMultiple.aggregate(handComputedRun())
        assertEquals(2.0, a.maxDrawdownR!!, eps)
    }

    @Test fun `the equity curve is walked in the order trades closed, not the order they were stored`() {
        // Same three trades, stored worst-first. In close order the run is +3R then −1R then −1R, a
        // 2R drawdown; in store order the curve would start underwater and report a different number.
        val stored = listOf(
            closed(exit = 1.00, closeDateIso = "2026-08-02"), // −1R, closed second
            closed(exit = 1.00, closeDateIso = "2026-08-03"), // −1R, closed third
            closed(exit = 5.00, closeDateIso = "2026-08-01"), // +3R, closed FIRST
        )
        val a = RiskMultiple.aggregate(stored)
        assertEquals(1.0, a.totalR!!, eps)
        assertEquals(2.0, a.maxDrawdownR!!, eps)
        assertEquals(2, a.longestLosingStreak)
    }

    @Test fun `a drawdown-free run reports zero rather than null`() {
        val a = RiskMultiple.aggregate(
            listOf(
                closed(exit = 3.00, closeDateIso = "2026-08-01"), // +1R
                closed(exit = 4.00, closeDateIso = "2026-08-02"), // +2R
            ),
        )
        assertEquals(0.0, a.maxDrawdownR!!, eps)
        assertNull("no losses at all means the ratio is undefined, not infinite", a.profitFactor)
    }

    // ------------------------------------------------------------------ the mixed list — the hard one

    @Test fun `a mixed list reports both counts and aggregates only over the scoreable positions`() {
        val mixed = listOf(
            closed(exit = 4.00, stopPct = 50.0, closeDateIso = "2026-08-01"),   // +2R, scored
            closed(exit = 9.99, stopPct = null, closeDateIso = "2026-08-02"),   // legacy — NO stop, unscoreable
            closed(                                                             // −1R, scored
                exit = null, stopPct = 100.0, outcome = CallOutcome.EXPIRED, closeDateIso = "2026-08-03",
            ),
            closed(                                                             // exercised — unscoreable
                exit = null, stopPct = 50.0, outcome = CallOutcome.EXERCISED, closeDateIso = "2026-08-04",
            ),
            closed(exit = 1.00, stopPct = null, closeDateIso = "2026-08-05"),   // legacy loss — unscoreable
        )
        val a = RiskMultiple.aggregate(mixed)

        assertEquals("every position handed in is accounted for", 5, a.closedCount)
        assertEquals(2, a.scored)
        assertEquals(3, a.unscoreable)
        assertEquals("scored + unscoreable must partition the input", a.closedCount, a.scored + a.unscoreable)

        // Only the +2R and the −1R take part: total 1.0 over 2 scored = +0.5R expectancy. If the three
        // unscoreable positions were swept in as 0R the total would be unchanged and the average would
        // read +0.2R — a quieter, wronger number over trades that were never measured at all.
        assertEquals(1.0, a.totalR!!, eps)
        assertEquals(0.5, a.avgR!!, eps)
        assertEquals(2.0, a.largestWinR!!, eps)
        assertEquals(-1.0, a.largestLossR!!, eps)
        assertEquals(2.0, a.profitFactor!!, eps)  // 2.0R won / 1.0R lost
        assertEquals("only the expired −1R is a loss", 1, a.longestLosingStreak)
        assertEquals(1.0, a.maxDrawdownR!!, eps)  // curve peaks at +2.0 then gives back 1.0
    }

    @Test fun `a zero-R close and an unscoreable close are not the same thing`() {
        val scratch = RiskMultiple.aggregate(listOf(closed(exit = 2.00, stopPct = 50.0))) // exit == entry
        assertEquals(1, scratch.scored)
        assertEquals(0, scratch.unscoreable)
        assertEquals(0.0, scratch.avgR!!, eps)

        val unknown = RiskMultiple.aggregate(listOf(closed(exit = 2.00, stopPct = null)))
        assertEquals(0, unknown.scored)
        assertEquals(1, unknown.unscoreable)
        assertNull("nothing scored means there is no average, not an average of zero", unknown.avgR)
    }

    @Test fun `a history with nothing scoreable reports its size and refuses to invent numbers`() {
        val a = RiskMultiple.aggregate(List(4) { closed(stopPct = null, closeDateIso = "2026-08-0${it + 1}") })
        assertEquals(4, a.closedCount)
        assertEquals(0, a.scored)
        assertEquals(4, a.unscoreable)
        assertNull(a.totalR)
        assertNull(a.avgR)
        assertNull(a.profitFactor)
        assertNull(a.largestWinR)
        assertNull(a.largestLossR)
        assertNull(a.maxDrawdownR)
        assertEquals(0, a.longestLosingStreak)
        assertTrue(a.smallSample)
    }

    @Test fun `an empty history does not divide by zero`() {
        val a = RiskMultiple.aggregate(emptyList())
        assertEquals(0, a.closedCount)
        assertEquals(0, a.scored)
        assertEquals(0, a.unscoreable)
        assertNull(a.totalR)
        assertNull(a.avgR)
        assertTrue(a.smallSample)
    }

    // ------------------------------------------------------------------ the small-sample floor

    @Test fun `the small-sample flag fires below the floor and clears at it`() {
        val floor = RiskMultiple.MIN_SCORED_FOR_EXPECTANCY
        val under = RiskMultiple.aggregate(List(floor - 1) { closed(exit = 3.00) })
        assertEquals(floor - 1, under.scored)
        assertTrue("expectancy over ${floor - 1} closes is noise and must say so", under.smallSample)

        val at = RiskMultiple.aggregate(List(floor) { closed(exit = 3.00) })
        assertEquals(floor, at.scored)
        assertFalse(at.smallSample)
    }

    @Test fun `the floor counts SCORED closes, not closed ones`() {
        // 30 closed, only 5 with a stop recorded: still a small sample. Counting the unscoreable ones
        // toward the floor would clear the flag on an expectancy built from six trades.
        val many = List(25) { closed(stopPct = null) } + List(5) { closed(exit = 3.00) }
        val a = RiskMultiple.aggregate(many)
        assertEquals(30, a.closedCount)
        assertEquals(5, a.scored)
        assertTrue(a.smallSample)
    }

    // ------------------------------------------------------------------ stored records

    @Test fun `a stored record written before the risk field existed loads as unknown, not as zero`() {
        // Verbatim shape of a pre-SWT-6 DataStore entry: no stopPct key at all. It must decode (not
        // throw) and land on null — `coerceInputValues` would have turned a non-nullable `Double = 0.0`
        // into a confident zero-risk stop, which is the "Stop $0" defect in another costume.
        val legacyJson = """
            [{"id":"abc","symbol":"unh","contractSymbol":"","type":"call","strike":420.0,
              "expiryIso":"2026-09-18","expiryTs":1789000000,"contracts":1,"fillPrice":2.0,
              "openDateIso":"2026-07-01","outcome":"SOLD","closeDateIso":"2026-08-01",
              "exitPricePerShare":3.0,"realizedPnl":100.0,"realizedPnlPct":50.0}]
        """.trimIndent()

        val decoded = Http.json.decodeFromString<List<ClosedCallPosition>>(legacyJson)
        assertEquals(1, decoded.size)
        val old = decoded.single()
        assertEquals(3.0, old.exitPricePerShare!!, eps) // the rest of the record still loads
        assertNull("a record with no risk recorded must not read as a 0% stop", old.stopPct)
        assertNull(old.takeProfitPct)
        assertNull("and therefore has no R, forever", RiskMultiple.rFor(old))

        val a = RiskMultiple.aggregate(decoded)
        assertEquals(1, a.closedCount)
        assertEquals(1, a.unscoreable)
        assertEquals(0, a.scored)
    }

    @Test fun `closing a position carries the stop it was opened with into the closed record`() {
        val open = CallPosition(
            symbol = "unh",
            strike = 420.0,
            expiryIso = "2026-09-18",
            expiryTs = 1_789_000_000L,
            contracts = 1,
            fillPrice = 2.00,
            openDateIso = "2026-07-01",
            takeProfitPct = 80.0,
            stopPct = 50.0,
        )

        // Every close path, because a risk field populated at only some of them is worse than none.
        val sold = open.asSold(exitPricePerShare = 3.00, closeDateIso = "2026-08-01")
        assertEquals(50.0, sold.stopPct!!, eps)
        assertEquals(80.0, sold.takeProfitPct!!, eps)
        assertEquals(1.0, RiskMultiple.rFor(sold)!!, eps) // 50% of $2.00 = $1.00 risk; +$1.00 = +1R

        val expired = open.asExpiredWorthless(closeDateIso = "2026-09-18")
        assertEquals(50.0, expired.stopPct!!, eps)
        assertEquals(-2.0, RiskMultiple.rFor(expired)!!, eps)

        val exercised = open.asExercised(closeDateIso = "2026-09-18")
        assertEquals("the risk is preserved even where R can't use it", 50.0, exercised.stopPct!!, eps)
        assertNull(RiskMultiple.rFor(exercised))
    }

    @Test fun `closing a position that never had a stop keeps it null instead of inventing one`() {
        val noPlan = CallPosition(
            symbol = "unh",
            strike = 420.0,
            expiryIso = "2026-09-18",
            expiryTs = 1_789_000_000L,
            contracts = 1,
            fillPrice = 2.00,
            openDateIso = "2026-07-01",
        )
        val sold = noPlan.asSold(exitPricePerShare = 3.00, closeDateIso = "2026-08-01")
        assertNull(sold.stopPct)
        assertNotNull("the dollar P/L is still recorded — only R is unavailable", sold.realizedPnl)
        assertNull(RiskMultiple.rFor(sold))
    }

    // ------------------------------------------------------------------ display

    @Test fun `R formats with an explicit sign and never renders an absent value`() {
        assertEquals("+1.0R", RiskMultiple.format(1.0))
        assertEquals("−2.5R", RiskMultiple.format(-2.5))
        assertEquals("+0.0R", RiskMultiple.format(0.0)) // a scratch is a result; callers skip null instead
    }
}
