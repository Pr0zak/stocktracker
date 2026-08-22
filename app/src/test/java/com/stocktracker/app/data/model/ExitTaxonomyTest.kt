package com.stocktracker.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exit taxonomy and the two win rates built on it (SWT-7).
 *
 * THE INCIDENT THIS GUARDS. The reference track record this feature came from published two win rates
 * for the same 72 closed trades: a "profitable exit rate" of 65.3% and a "hard win rate" of 12.5%. The
 * gap was 41 positions that reached neither their target nor their stop and were closed on the clock,
 * most of them a little green. Both numbers are honest; quoting the first alone is not. So the two
 * rates must diverge here on a constructed history, must always arrive together from one call, and
 * must be null rather than 0.0 when nothing can be classified — 0.0 would claim we checked every trade
 * and none won.
 *
 * Two collapses are pinned as well: an EXPIRY is never counted as a STOP (a stop is the plan working;
 * an expiry at zero is the plan abandoned, at roughly twice the intended loss), and DISCRETIONARY is
 * never merged with UNPLANNED (departing from a plan is not the same fact as never having had one).
 */
class ExitTaxonomyTest {

    private val eps = 1e-9

    /**
     * A closed call. Defaults are the worked plan throughout: $2.00 premium, 50% stop → $1.00 stop
     * level, 80% target → $3.60 target level.
     */
    private fun closed(
        fillPrice: Double = 2.00,
        exit: Double? = 2.50,
        stopPct: Double? = 50.0,
        takeProfitPct: Double? = 80.0,
        outcome: CallOutcome = CallOutcome.SOLD,
        closeDateIso: String = "2026-08-01",
    ): ClosedCallPosition {
        val realized = exit?.let { RealizedPnl.forSale(fillPrice, it, 1) }
        return ClosedCallPosition(
            symbol = "aapl",
            strike = 100.0,
            expiryIso = "2026-09-18",
            expiryTs = 1_789_000_000L,
            contracts = 1,
            fillPrice = fillPrice,
            openDateIso = "2026-07-01",
            takeProfitPct = takeProfitPct,
            stopPct = stopPct,
            outcome = outcome,
            closeDateIso = closeDateIso,
            exitPricePerShare = if (outcome == CallOutcome.SOLD) exit else null,
            realizedPnl = when (outcome) {
                CallOutcome.SOLD -> realized?.pnl
                CallOutcome.EXPIRED -> RealizedPnl.forExpiredWorthless(fillPrice, 1).pnl
                CallOutcome.EXERCISED -> null
            },
            realizedPnlPct = when (outcome) {
                CallOutcome.SOLD -> realized?.pct
                CallOutcome.EXPIRED -> RealizedPnl.forExpiredWorthless(fillPrice, 1).pct
                CallOutcome.EXERCISED -> null
            },
        )
    }

    // ------------------------------------------------------------------ the level conversions

    @Test fun `a take-profit percent becomes a price above the entry, not a price of its own`() {
        // 80% target on a $2.00 premium → $2.00 × 1.80 = $3.60. NOT $80.
        assertEquals(3.60, ExitTaxonomy.targetPriceFromPct(entry = 2.00, takeProfitPct = 80.0)!!, eps)
    }

    @Test fun `a missing or non-positive take-profit percent has no target level`() {
        assertNull(ExitTaxonomy.targetPriceFromPct(2.00, null))
        assertNull(ExitTaxonomy.targetPriceFromPct(2.00, 0.0))
        assertNull(ExitTaxonomy.targetPriceFromPct(2.00, -10.0))
        assertNull(ExitTaxonomy.targetPriceFromPct(2.00, Double.NaN))
        assertNull(ExitTaxonomy.targetPriceFromPct(0.0, 80.0))
    }

    // ------------------------------------------------------------------ each bucket, and its boundary

    @Test fun `an exit past the target level is a target hit`() {
        assertEquals(ExitTaxonomy.ExitKind.TARGET, ExitTaxonomy.classify(closed(exit = 4.00)))
    }

    @Test fun `an exit exactly AT the target level is a target hit, not a near miss`() {
        // 80% of $2.00 → the level is exactly $3.60, and reaching it is reaching it.
        assertEquals(ExitTaxonomy.ExitKind.TARGET, ExitTaxonomy.classify(closed(exit = 3.60)))
    }

    @Test fun `a cent below the target level is not a target hit`() {
        assertEquals(ExitTaxonomy.ExitKind.DISCRETIONARY, ExitTaxonomy.classify(closed(exit = 3.59)))
    }

    @Test fun `an exit below the stop level is a stop`() {
        assertEquals(ExitTaxonomy.ExitKind.STOP, ExitTaxonomy.classify(closed(exit = 0.60)))
    }

    @Test fun `an exit exactly AT the stop level is a stop`() {
        // 50% stop on $2.00 → the level is exactly $1.00.
        assertEquals(ExitTaxonomy.ExitKind.STOP, ExitTaxonomy.classify(closed(exit = 1.00)))
    }

    @Test fun `a cent above the stop level is a decision, not a stop`() {
        assertEquals(ExitTaxonomy.ExitKind.DISCRETIONARY, ExitTaxonomy.classify(closed(exit = 1.01)))
    }

    @Test fun `an exercised close has no option-leg exit to compare against the plan`() {
        assertEquals(
            ExitTaxonomy.ExitKind.EXERCISED,
            ExitTaxonomy.classify(closed(outcome = CallOutcome.EXERCISED)),
        )
    }

    @Test fun `an exit between the levels is discretionary`() {
        assertEquals(ExitTaxonomy.ExitKind.DISCRETIONARY, ExitTaxonomy.classify(closed(exit = 2.50)))
    }

    @Test fun `a position opened with only a stop, sold above it, departed from a plan it did have`() {
        val onlyStop = closed(exit = 2.50, takeProfitPct = null)
        assertEquals(ExitTaxonomy.ExitKind.DISCRETIONARY, ExitTaxonomy.classify(onlyStop))
    }

    // ------------------------------------------------------------------ the collapses that must not happen

    @Test fun `an expiry is its own outcome and is NEVER counted as a stop`() {
        val expired = closed(outcome = CallOutcome.EXPIRED)
        assertEquals(ExitTaxonomy.ExitKind.EXPIRY, ExitTaxonomy.classify(expired))
        assertNotEquals(ExitTaxonomy.ExitKind.STOP, ExitTaxonomy.classify(expired))

        val record = ExitTaxonomy.summarize(listOf(expired))
        assertEquals(1, record.bucket(ExitTaxonomy.ExitKind.EXPIRY).count)
        assertEquals("an expiry must not inflate the stop bucket", 0, record.bucket(ExitTaxonomy.ExitKind.STOP).count)
        // And the cost is worse than the stop said: a 50% stop that expired at $0 is −2R, not −1R.
        assertEquals(-2.0, record.bucket(ExitTaxonomy.ExitKind.EXPIRY).avgR!!, eps)
    }

    @Test fun `an expiry recorded before the plan was carried across is unplanned, not an expiry`() {
        // This assertion is the REVERSE of the one first written here, and the reversal is the point.
        //
        // The original reasoning was that "expired worthless" is a complete answer to "how did this
        // end" and needs no levels to be known. True as far as it goes — but EXPIRY counts inside
        // the rate denominator and UNPLANNED does not, so classifying a plan-less expiry as EXPIRY
        // scored the losses of a pre-SWT-6 history while discarding its wins, which fell to
        // UNPLANNED as sales. Nine profitable sales beside one expiry reported 0% finished green.
        //
        // Both rates are plan-relative and share one denominator, so the rule has to be symmetric.
        val oldExpiry = closed(outcome = CallOutcome.EXPIRED, stopPct = null, takeProfitPct = null)
        assertEquals(ExitTaxonomy.ExitKind.UNPLANNED, ExitTaxonomy.classify(oldExpiry))

        // An expiry that DID carry a plan is still an expiry — only the plan-less case moved.
        val plannedExpiry = closed(outcome = CallOutcome.EXPIRED, stopPct = 50.0, takeProfitPct = 80.0)
        assertEquals(ExitTaxonomy.ExitKind.EXPIRY, ExitTaxonomy.classify(plannedExpiry))
    }

    @Test fun `a pre-SWT-6 record with no levels at all lands in unplanned, distinct from discretionary`() {
        val preSwt6 = closed(exit = 2.50, stopPct = null, takeProfitPct = null)
        assertEquals(ExitTaxonomy.ExitKind.UNPLANNED, ExitTaxonomy.classify(preSwt6))

        val offPlan = closed(exit = 2.50)
        assertNotEquals(
            "never having had a plan is not the same fact as departing from one",
            ExitTaxonomy.classify(preSwt6),
            ExitTaxonomy.classify(offPlan),
        )
        assertEquals(ExitTaxonomy.ExitKind.DISCRETIONARY, ExitTaxonomy.classify(offPlan))
    }

    @Test fun `a sold close with no exit price recorded cannot be checked against its plan`() {
        assertEquals(ExitTaxonomy.ExitKind.UNPLANNED, ExitTaxonomy.classify(closed(exit = null)))
    }

    // ------------------------------------------------------------------ the two rates

    /**
     * The reference's shape, with exact arithmetic: a history that is mostly green decisions and only
     * a handful of real target hits.
     *
     *   10 target hits        green
     *   30 off-plan closes    green (sold at $2.50 on a $2.00 premium — neither level touched)
     *   15 stops              red
     *    5 expiries           red
     *   --------------------------------
     *   60 classified
     *
     * hard win rate        = 10 / 60 = 16.666…%
     * profitable exit rate = 40 / 60 = 66.666…%
     * The 50-point gap IS the defect: quoting 66.7% alone would describe a plan that worked one time
     * in six as one that worked two times in three.
     */
    private fun referenceShapedHistory(): List<ClosedCallPosition> =
        List(10) { closed(exit = 4.00) } +
            List(30) { closed(exit = 2.50) } +
            List(15) { closed(exit = 0.80) } +
            List(5) { closed(outcome = CallOutcome.EXPIRED) }

    @Test fun `the hard win rate and the profitable exit rate diverge by exactly fifty points here`() {
        val r = ExitTaxonomy.summarize(referenceShapedHistory())
        assertEquals(60, r.classified)
        assertEquals(100.0 / 6.0, r.hardWinRatePct!!, 1e-9)        // 16.666…%
        assertEquals(200.0 / 3.0, r.profitableExitRatePct!!, 1e-9) // 66.666…%
        assertEquals(50.0, r.profitableExitRatePct!! - r.hardWinRatePct!!, 1e-9)
    }

    @Test fun `the buckets of that history hold the counts the rates were computed from`() {
        val r = ExitTaxonomy.summarize(referenceShapedHistory())
        assertEquals(10, r.bucket(ExitTaxonomy.ExitKind.TARGET).count)
        assertEquals(30, r.bucket(ExitTaxonomy.ExitKind.DISCRETIONARY).count)
        assertEquals(15, r.bucket(ExitTaxonomy.ExitKind.STOP).count)
        assertEquals(5, r.bucket(ExitTaxonomy.ExitKind.EXPIRY).count)
        // Average R per bucket: target ($4.00 exit) = +2R, off-plan ($2.50) = +0.5R, stop ($0.80) = −1.2R.
        assertEquals(2.0, r.bucket(ExitTaxonomy.ExitKind.TARGET).avgR!!, eps)
        assertEquals(0.5, r.bucket(ExitTaxonomy.ExitKind.DISCRETIONARY).avgR!!, eps)
        assertEquals(-1.2, r.bucket(ExitTaxonomy.ExitKind.STOP).avgR!!, eps)
    }

    @Test fun `exercised and unplanned closes are counted but kept out of the rate denominator`() {
        val positions = referenceShapedHistory() +
            closed(outcome = CallOutcome.EXERCISED) +
            closed(exit = 2.50, stopPct = null, takeProfitPct = null)
        val r = ExitTaxonomy.summarize(positions)
        assertEquals(62, r.closedCount)
        assertEquals("the two unmeasurable closes must not enter the denominator", 60, r.classified)
        assertEquals(2, r.unclassified)
        // The rates are unchanged by their presence — they are reported, not folded in.
        assertEquals(100.0 / 6.0, r.hardWinRatePct!!, 1e-9)
        assertEquals(200.0 / 3.0, r.profitableExitRatePct!!, 1e-9)
    }

    @Test fun `the numerators are carried as counts so a small sample can be stated without a percentage`() {
        // "1 of 2 reached target" is honest where "50%" is not, and the render site must not have to
        // reconstruct the count by multiplying a percentage it was told not to print.
        val r = ExitTaxonomy.summarize(referenceShapedHistory())
        assertEquals(10, r.targetHits)
        assertEquals(40, r.greenExits)
        assertEquals(r.targetHits.toDouble() / r.classified * 100.0, r.hardWinRatePct!!, eps)
        assertEquals(r.greenExits.toDouble() / r.classified * 100.0, r.profitableExitRatePct!!, eps)
    }

    @Test fun `both rates are null over an empty history, never zero`() {
        val r = ExitTaxonomy.summarize(emptyList())
        assertNull("0.0 would claim we checked every trade and none won", r.hardWinRatePct)
        assertNull(r.profitableExitRatePct)
        assertEquals(0, r.closedCount)
        assertEquals(0, r.classified)
    }

    @Test fun `both rates are null over an all-unplanned history, never zero`() {
        val positions = List(4) { closed(exit = 2.50, stopPct = null, takeProfitPct = null) }
        val r = ExitTaxonomy.summarize(positions)
        assertNull(r.hardWinRatePct)
        assertNull(r.profitableExitRatePct)
        assertEquals(4, r.closedCount)
        assertEquals(0, r.classified)
        assertEquals(4, r.bucket(ExitTaxonomy.ExitKind.UNPLANNED).count)
    }

    @Test fun `the two rates cannot be obtained separately`() {
        // Structural, not behavioural: both live on the one Record that summarize() returns, so a
        // caller reaching for the flattering number necessarily has the qualifying one in hand.
        val r = ExitTaxonomy.summarize(referenceShapedHistory())
        assertNotNull(r.hardWinRatePct)
        assertNotNull(r.profitableExitRatePct)
    }

    // ------------------------------------------------------------------ per-bucket average R

    @Test fun `a bucket whose members cannot be scored reports a null average rather than zero R`() {
        val r = ExitTaxonomy.summarize(List(3) { closed(exit = 2.50, stopPct = null, takeProfitPct = null) })
        val unplanned = r.bucket(ExitTaxonomy.ExitKind.UNPLANNED)
        assertEquals(3, unplanned.count)
        assertEquals(0, unplanned.scored)
        assertNull("no stop was recorded, so there is no R — not 0R", unplanned.avgR)
    }

    @Test fun `a bucket average skips the members that cannot be scored instead of counting them as zero`() {
        // Two off-plan closes at $2.50 with a 50% stop = +0.5R each; one with no stop recorded, which
        // has no R at all. The average is +0.5R over 2, not +0.333R over 3.
        val positions = listOf(
            closed(exit = 2.50),
            closed(exit = 2.50),
            closed(exit = 2.50, stopPct = null),
        )
        val bucket = ExitTaxonomy.summarize(positions).bucket(ExitTaxonomy.ExitKind.DISCRETIONARY)
        assertEquals(3, bucket.count)
        assertEquals(2, bucket.scored)
        assertEquals(0.5, bucket.avgR!!, eps)
    }

    @Test fun `the exercised bucket has no R, because there is no option-leg exit to measure`() {
        val r = ExitTaxonomy.summarize(listOf(closed(outcome = CallOutcome.EXERCISED)))
        assertEquals(1, r.bucket(ExitTaxonomy.ExitKind.EXERCISED).count)
        assertNull(r.bucket(ExitTaxonomy.ExitKind.EXERCISED).avgR)
    }

    // ------------------------------------------------------------------ the partition

    @Test fun `the bucket counts partition the closed set exactly`() {
        val positions = referenceShapedHistory() +
            closed(outcome = CallOutcome.EXERCISED) +
            closed(exit = 2.50, stopPct = null, takeProfitPct = null) +
            closed(exit = null) +
            closed(exit = 3.60) +
            closed(exit = 1.00)
        val r = ExitTaxonomy.summarize(positions)
        assertEquals(positions.size, r.closedCount)
        assertEquals(
            "every close must land in exactly one named bucket — none dropped, none double-counted",
            r.closedCount,
            r.buckets.sumOf { it.count },
        )
        assertEquals(r.closedCount, r.classified + r.unclassified)
        assertEquals(ExitTaxonomy.ExitKind.entries.size, r.buckets.size)
    }

    // ------------------------------------------------------------------ small sample

    @Test fun `a handful of classified closes is flagged as too few to be a rate`() {
        val r = ExitTaxonomy.summarize(listOf(closed(exit = 4.00), closed(exit = 2.50)))
        assertEquals(2, r.classified)
        assertTrue("2 closes is not a win rate", r.smallSample)
        // The numbers still exist — the render site is what must say "1 of 2" instead of "50%".
        assertEquals(50.0, r.hardWinRatePct!!, eps)
        assertEquals(100.0, r.profitableExitRatePct!!, eps)
    }

    @Test fun `the small-sample floor is the same one the expectancy uses`() {
        assertEquals(RiskMultiple.MIN_SCORED_FOR_EXPECTANCY, ExitTaxonomy.MIN_CLASSIFIED_FOR_RATES)
        val r = ExitTaxonomy.summarize(List(ExitTaxonomy.MIN_CLASSIFIED_FOR_RATES) { closed(exit = 2.50) })
        assertFalse("at the floor itself the flag comes off", r.smallSample)
    }

    /**
     * A history recorded before the exit plan was carried across must not read as a losing one.
     *
     * `classify` returned EXPIRY for an expired position before checking whether any levels were
     * recorded, while a SOLD position with no levels fell to UNPLANNED. EXPIRY counts inside the
     * rate denominator and UNPLANNED does not, so on any pre-SWT-6 history the losses were scored
     * and the wins were discarded: nine profitable sales and one expiry reported 0% finished green.
     *
     * Both rates are plan-relative and share one denominator by construction, so the rule has to be
     * symmetric — with no plan recorded there is nothing to assess, however the position ended.
     */
    @Test
    fun `an old history without plans is unclassified in both directions, not scored as all losses`() {
        val noPlan = { outcome: CallOutcome, exit: Double? ->
            closed(exit = exit, stopPct = null, takeProfitPct = null, outcome = outcome)
        }
        val history = List(9) { noPlan(CallOutcome.SOLD, 3.00) } + noPlan(CallOutcome.EXPIRED, null)

        val rec = ExitTaxonomy.summarize(history)

        assertEquals(10, rec.closedCount)
        assertEquals("no position here carried a plan to assess", 0, rec.classified)
        assertEquals(10, rec.unclassified)
        assertNull("9 of 10 finished green — 0% would be the lie", rec.profitableExitRatePct)
        assertNull(rec.hardWinRatePct)
    }

    @Test
    fun `an expiry with no plan recorded is unplanned, while one with a plan is an expiry`() {
        val bare = closed(stopPct = null, takeProfitPct = null, outcome = CallOutcome.EXPIRED, exit = null)
        val planned = closed(stopPct = 50.0, takeProfitPct = 80.0, outcome = CallOutcome.EXPIRED, exit = null)

        assertEquals(ExitTaxonomy.ExitKind.UNPLANNED, ExitTaxonomy.classify(bare))
        assertEquals(ExitTaxonomy.ExitKind.EXPIRY, ExitTaxonomy.classify(planned))
    }

    @Test
    fun `one recorded level is enough for an expiry to be assessable`() {
        // A stop with no target still says what the plan was on the downside, which is the side an
        // expiry landed on. Only a total absence of levels makes it unassessable.
        val stopOnly = closed(stopPct = 50.0, takeProfitPct = null, outcome = CallOutcome.EXPIRED, exit = null)
        assertEquals(ExitTaxonomy.ExitKind.EXPIRY, ExitTaxonomy.classify(stopOnly))
    }
}
