package com.stocktracker.app.data.model

import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict journal — what YOU did with a verdict (SWT-8).
 *
 * THE GAP THIS GUARDS. Three things in this app score decisions and none of them scored the user's:
 * `memory/stats` scores the ANALYST against a fixed 20-day horizon, the sandbox scores a PAPER TRADER
 * in its own ledger, and the options tracker scores real positions but only options. "Is this thing
 * helping me" had no data behind it at all. The journal supplies it, and every defect it can develop
 * is a variant of the same one: an absence rendered as a confident zero.
 *
 * Four of those are pinned below. A verdict logged and not acted on must be UNDECIDED and must not
 * count as a pass — a `Boolean taken = false` would have said "I passed on this" about every entry
 * created in the last five minutes. A taken entry with a fill and no exit is OPEN, and scoring it as
 * a 0R closed trade would drag the expectancy toward break-even with trades that have not finished. A
 * record over zero taken entries reports nulls, because "no record yet" and "record breaks even" are
 * opposite claims. And the curve is walked in CLOSE order — store order is insertion order and entry
 * order is when the verdict arrived; a drawdown computed over either is a statement about a sequence
 * that never happened.
 */
class VerdictJournalTest {

    private val eps = 1e-9

    /**
     * A journal entry. Defaults are the worked plan throughout: stop $90, target $130, your fill
     * $100 — so risk is $10/share and a $120 exit is +2.0R, a $90 exit is −1.0R.
     */
    private fun entry(
        symbol: String = "AAPL",
        verdictDateIso: String = "2026-08-01",
        stop: Double? = 90.0,
        target: Double? = 130.0,
        taken: TakenState = TakenState.TAKEN,
        fillPrice: Double? = 100.0,
        shares: Double? = 10.0,
        fillDateIso: String? = "2026-08-02",
        exitPrice: Double? = 120.0,
        exitDateIso: String? = "2026-08-20",
    ) = VerdictJournalEntry(
        symbol = symbol,
        verdictDateIso = verdictDateIso,
        plan = JournalPlan(
            action = "buy_now",
            entryLow = 98.0,
            entryHigh = 102.0,
            stop = stop,
            target = target,
            conviction = 72,
            thesis = "Base breakout on rising volume.",
        ),
        taken = taken,
        fillPrice = fillPrice,
        shares = shares,
        fillDateIso = fillDateIso,
        exitPrice = exitPrice,
        exitDateIso = exitDateIso,
    )

    /** A freshly logged verdict: plan snapshotted, nothing decided, nothing filled. */
    private fun fresh(symbol: String = "AAPL", verdictDateIso: String = "2026-08-01") = entry(
        symbol = symbol,
        verdictDateIso = verdictDateIso,
        taken = TakenState.UNDECIDED,
        fillPrice = null,
        shares = null,
        fillDateIso = null,
        exitPrice = null,
        exitDateIso = null,
    )

    // ------------------------------------------------------------------ the undecided entry

    @Test fun `a freshly logged entry with no fill is undecided rather than not taken`() {
        val e = fresh()
        assertEquals(TakenState.UNDECIDED, e.taken)
        assertEquals(JournalStatus.UNDECIDED, e.status)
        assertFalse(e.isTaken)
        assertFalse(e.isOpen)
        assertFalse(e.isClosed)
    }

    @Test fun `an entry with no fill reports null cost and R rather than a fill of zero`() {
        val e = fresh()
        assertNull(e.fillPrice)
        assertNull(e.shares)
        assertNull(e.costBasis)
        assertNull(e.realizedPnl)
        assertNull(e.rMultiple)
        assertNull(e.exitKind)
    }

    @Test fun `an undecided entry contributes to no rate and to no R aggregate`() {
        // 1 taken + closed at +2R, 1 untouched. The take rate is over DECIDED entries only, so the
        // undecided one is neither a numerator nor a denominator: 1 of 1 decided = 100%, not 50%.
        val record = VerdictJournal.record(listOf(entry(), fresh(symbol = "MSFT")))
        assertEquals(2, record.entryCount)
        assertEquals(1, record.takenCount)
        assertEquals(0, record.notTakenCount)
        assertEquals(1, record.undecidedCount)
        assertEquals(100.0, record.takeRatePct!!, eps)
        assertEquals(1, record.closedCount)
        assertEquals(2.0, record.expectancyR!!, eps)
    }

    @Test fun `an entry that is only undecided leaves the take rate null rather than zero percent`() {
        // Nothing has been decided either way. 0.0% would claim you passed on everything.
        val record = VerdictJournal.record(listOf(fresh(), fresh(symbol = "MSFT")))
        assertNull(record.takeRatePct)
        assertEquals(2, record.undecidedCount)
    }

    // ------------------------------------------------------------------ the open entry

    @Test fun `a taken entry with a fill but no exit is open and not a zero R closed trade`() {
        val e = entry(exitPrice = null, exitDateIso = null)
        assertEquals(JournalStatus.OPEN, e.status)
        assertTrue(e.isOpen)
        assertFalse(e.isClosed)
        // Not 0R — it has no result at all yet.
        assertNull(e.rMultiple)
        assertNull(e.realizedPnl)
        // And no exit bucket: UNPLANNED would file a live position in an exit breakdown.
        assertNull(e.exitKind)
    }

    @Test fun `an open entry is excluded from the R aggregate instead of averaged in as zero`() {
        // One +2R close and one open trade. Expectancy is 2.0R over ONE close, not 1.0R over two.
        val record = VerdictJournal.record(listOf(entry(), entry(symbol = "MSFT", exitPrice = null, exitDateIso = null)))
        assertEquals(2, record.takenCount)
        assertEquals(1, record.openCount)
        assertEquals(1, record.closedCount)
        assertEquals(2.0, record.expectancyR!!, eps)
        assertEquals(2.0, record.totalR!!, eps)
    }

    @Test fun `a taken entry with no fill recorded is unfilled rather than open`() {
        val e = entry(fillPrice = null, shares = null, fillDateIso = null, exitPrice = null, exitDateIso = null)
        assertEquals(JournalStatus.TAKEN_UNFILLED, e.status)
        assertTrue(e.isTaken)
        assertFalse(e.isOpen)
        val record = VerdictJournal.record(listOf(e))
        assertEquals(1, record.takenCount)
        assertEquals(1, record.unfilledCount)
        assertEquals(0, record.openCount)
        assertEquals(0, record.closedCount)
    }

    // ------------------------------------------------------------------ R, through RiskMultiple

    @Test fun `R is measured from your real fill and the plan's stop`() {
        // plan stop $90, YOUR fill $100 → risk $10/share. exit $120 → (120 − 100) / 10 = +2.0R
        assertEquals(2.0, entry().rMultiple!!, eps)
        // exit $90 (at the stop) → (90 − 100) / 10 = −1.0R
        assertEquals(-1.0, entry(exitPrice = 90.0).rMultiple!!, eps)
        // exit $140 → (140 − 100) / 10 = +4.0R
        assertEquals(4.0, entry(exitPrice = 140.0).rMultiple!!, eps)
    }

    @Test fun `R uses your fill and not the plan's entry zone`() {
        // The plan's zone is $98–$102; you chased and filled at $110. Risk from YOUR fill against the
        // $90 stop is $20, so a $120 exit is +0.5R — not the +2.0R a plan-priced entry would report.
        assertEquals(0.5, entry(fillPrice = 110.0).rMultiple!!, eps)
    }

    @Test fun `an entry whose plan carried no stop is unscoreable rather than zero R`() {
        // No stop means no denominator. 0R is a real and different claim: exited exactly at entry.
        val e = entry(stop = null)
        assertNull(e.rMultiple)
        val record = VerdictJournal.record(listOf(e))
        assertEquals(1, record.closedCount)
        assertEquals(0, record.scored)
        assertEquals(1, record.unscoreable)
        assertNull(record.expectancyR)
        assertNull(record.totalR)
    }

    @Test fun `a stop at or above your fill is unscoreable rather than sign flipped`() {
        // Long-only: a stop above the entry gives a negative denominator, which would flip a loss into
        // a gain. RiskMultiple returns null and the journal passes that through untouched.
        assertNull(entry(fillPrice = 100.0, stop = 100.0).rMultiple)
        assertNull(entry(fillPrice = 100.0, stop = 110.0).rMultiple)
    }

    @Test fun `the scored and unscoreable split always sums to the closed count`() {
        val record = VerdictJournal.record(
            listOf(entry(), entry(symbol = "MSFT", stop = null), entry(symbol = "NVDA", exitPrice = 90.0)),
        )
        assertEquals(3, record.closedCount)
        assertEquals(2, record.scored)
        assertEquals(1, record.unscoreable)
        assertEquals(record.closedCount, record.scored + record.unscoreable)
    }

    @Test fun `expectancy and profit factor come out of the shared R aggregate`() {
        // +2.0R, −1.0R, +2.0R → total +3.0R over 3 scored → expectancy +1.0R.
        // profit factor = gross won 4.0 / gross lost 1.0 = 4.0
        val record = VerdictJournal.record(
            listOf(
                entry(symbol = "A", exitDateIso = "2026-08-10"),
                entry(symbol = "B", exitPrice = 90.0, exitDateIso = "2026-08-11"),
                entry(symbol = "C", exitDateIso = "2026-08-12"),
            ),
        )
        assertEquals(3.0, record.totalR!!, eps)
        assertEquals(1.0, record.expectancyR!!, eps)
        assertEquals(4.0, record.profitFactor!!, eps)
        assertEquals(2.0, record.risk.largestWinR!!, eps)
        assertEquals(-1.0, record.risk.largestLossR!!, eps)
    }

    @Test fun `the small sample floor is the one RiskMultiple already uses`() {
        val few = VerdictJournal.record(List(3) { entry(symbol = "S$it", exitDateIso = "2026-08-1$it") })
        assertTrue(few.smallSample)
        val many = VerdictJournal.record(
            List(RiskMultiple.MIN_SCORED_FOR_EXPECTANCY) { entry(symbol = "S$it", exitDateIso = "2026-08-01") },
        )
        assertFalse(many.smallSample)
    }

    // ------------------------------------------------------------------ exit buckets, via ExitTaxonomy

    @Test fun `an exit at or above the planned target is bucketed as a target hit`() {
        // target $130; exit exactly at it is the level being reached, not a near miss.
        assertEquals(ExitTaxonomy.ExitKind.TARGET, entry(exitPrice = 130.0).exitKind)
        assertEquals(ExitTaxonomy.ExitKind.TARGET, entry(exitPrice = 145.0).exitKind)
    }

    @Test fun `an exit at or below the planned stop is bucketed as stopped out`() {
        assertEquals(ExitTaxonomy.ExitKind.STOP, entry(exitPrice = 90.0).exitKind)
        assertEquals(ExitTaxonomy.ExitKind.STOP, entry(exitPrice = 85.0).exitKind)
    }

    @Test fun `an exit between the levels is a decision taken off plan`() {
        assertEquals(ExitTaxonomy.ExitKind.DISCRETIONARY, entry(exitPrice = 120.0).exitKind)
    }

    @Test fun `a close with no levels recorded is unplanned rather than off plan`() {
        // Departing from a plan and never having had one are different facts about your process.
        assertEquals(ExitTaxonomy.ExitKind.UNPLANNED, entry(stop = null, target = null).exitKind)
    }

    @Test fun `a half plan still counts - a stop with no target is off plan, not unplanned`() {
        assertEquals(ExitTaxonomy.ExitKind.DISCRETIONARY, entry(target = null, exitPrice = 120.0).exitKind)
    }

    @Test fun `the bucket breakdown partitions the closed entries and reports every kind`() {
        val record = VerdictJournal.record(
            listOf(
                entry(symbol = "A", exitPrice = 130.0, exitDateIso = "2026-08-10"), // TARGET, +3.0R
                entry(symbol = "B", exitPrice = 90.0, exitDateIso = "2026-08-11"),  // STOP,   −1.0R
                entry(symbol = "C", exitPrice = 120.0, exitDateIso = "2026-08-12"), // DISCRETIONARY, +2.0R
                entry(symbol = "D", stop = null, target = null, exitDateIso = "2026-08-13"), // UNPLANNED
            ),
        )
        // Every ExitKind has a bucket, including the two that cannot happen to a share.
        assertEquals(ExitTaxonomy.ExitKind.entries.size, record.buckets.size)
        assertEquals(0, record.bucket(ExitTaxonomy.ExitKind.EXPIRY).count)
        assertEquals(0, record.bucket(ExitTaxonomy.ExitKind.EXERCISED).count)
        assertEquals(1, record.bucket(ExitTaxonomy.ExitKind.TARGET).count)
        assertEquals(1, record.bucket(ExitTaxonomy.ExitKind.STOP).count)
        assertEquals(1, record.bucket(ExitTaxonomy.ExitKind.DISCRETIONARY).count)
        assertEquals(1, record.bucket(ExitTaxonomy.ExitKind.UNPLANNED).count)
        // Counts partition the closed set exactly — nothing dropped for being awkward.
        assertEquals(record.closedCount, record.buckets.sumOf { it.count })
        assertEquals(4, record.occupiedBuckets.sumOf { it.count })
    }

    @Test fun `an unscoreable bucket reports a null average R rather than zero`() {
        val record = VerdictJournal.record(listOf(entry(stop = null, target = null)))
        val bucket = record.bucket(ExitTaxonomy.ExitKind.UNPLANNED)
        assertEquals(1, bucket.count)
        assertEquals(0, bucket.scored)
        assertNull(bucket.avgR) // "could not measure these", not "these averaged to nothing"
        // A scoreable bucket does carry one.
        val hit = VerdictJournal.record(listOf(entry(exitPrice = 130.0))).bucket(ExitTaxonomy.ExitKind.TARGET)
        assertEquals(3.0, hit.avgR!!, eps) // (130 − 100) / 10 = +3.0R
    }

    // ------------------------------------------------------------------ taken vs not taken

    @Test fun `taken and not taken counts are both reported`() {
        // Twelve verdicts, you took four — the number the feature exists to produce. It is invisible
        // unless the eight you passed on are counted rather than simply absent from the store.
        val entries = List(4) { entry(symbol = "T$it", exitDateIso = "2026-08-1$it") } +
            List(8) { fresh(symbol = "P$it").copy(taken = TakenState.NOT_TAKEN) }
        val record = VerdictJournal.record(entries)
        assertEquals(12, record.entryCount)
        assertEquals(4, record.takenCount)
        assertEquals(8, record.notTakenCount)
        assertEquals(0, record.undecidedCount)
        assertEquals(record.entryCount, record.takenCount + record.notTakenCount + record.undecidedCount)
        assertEquals(4.0 / 12.0 * 100.0, record.takeRatePct!!, eps)
    }

    @Test fun `a not taken entry is never scored even if stale fill fields linger on it`() {
        // The UI can only reach this by marking an entry taken, filling it, then flipping it back —
        // but the record must key off the state, not off whether a number happens to be present.
        val e = entry(taken = TakenState.NOT_TAKEN)
        assertEquals(JournalStatus.NOT_TAKEN, e.status)
        val record = VerdictJournal.record(listOf(e))
        assertEquals(0, record.takenCount)
        assertEquals(1, record.notTakenCount)
        assertEquals(0, record.closedCount)
        assertNull(record.expectancyR)
    }

    // ------------------------------------------------------------------ the empty record

    @Test fun `the record over zero taken entries reports nulls and never zeros`() {
        val record = VerdictJournal.record(listOf(fresh(), fresh(symbol = "MSFT").copy(taken = TakenState.NOT_TAKEN)))
        assertEquals(0, record.takenCount)
        assertEquals(0, record.closedCount)
        assertNull(record.expectancyR)
        assertNull(record.totalR)
        assertNull(record.profitFactor)
        assertNull(record.risk.largestWinR)
        assertNull(record.risk.largestLossR)
        assertNull(record.risk.maxDrawdownR)
        assertTrue(record.smallSample)
    }

    @Test fun `the record over an empty journal reports nulls and never zeros`() {
        val record = VerdictJournal.record(emptyList())
        assertEquals(0, record.entryCount)
        assertNull(record.takeRatePct)
        assertNull(record.expectancyR)
        assertNull(record.profitFactor)
        assertNull(record.totalR)
        assertTrue(record.buckets.all { it.count == 0 && it.avgR == null })
    }

    // ------------------------------------------------------------------ the curve

    @Test fun `the cumulative series is in close order and not entry or store order`() {
        // Stored newest-verdict-first and closed in the opposite order to how they were logged.
        //   AAPL: verdict 2026-08-03, exit $90  → −1.0R, closed 2026-08-25 (LAST)
        //   MSFT: verdict 2026-08-02, exit $130 → +3.0R, closed 2026-08-15
        //   NVDA: verdict 2026-08-01, exit $120 → +2.0R, closed 2026-08-05 (FIRST)
        // In close order the curve runs +2.0 → +5.0 → +4.0. In store order it would run
        // −1.0 → +2.0 → +4.0 — same endpoint, and a drawdown that never happened.
        val curve = VerdictJournal.curve(
            listOf(
                entry(symbol = "AAPL", verdictDateIso = "2026-08-03", exitPrice = 90.0, exitDateIso = "2026-08-25"),
                entry(symbol = "MSFT", verdictDateIso = "2026-08-02", exitPrice = 130.0, exitDateIso = "2026-08-15"),
                entry(symbol = "NVDA", verdictDateIso = "2026-08-01", exitPrice = 120.0, exitDateIso = "2026-08-05"),
            ),
        )
        assertEquals(listOf("NVDA", "MSFT", "AAPL"), curve.actual.map { it.symbol })
        assertEquals(listOf("2026-08-05", "2026-08-15", "2026-08-25"), curve.actual.map { it.closeDateIso })
        assertEquals(2.0, curve.actual[0].cumulativeR, eps)
        assertEquals(5.0, curve.actual[1].cumulativeR, eps)
        assertEquals(4.0, curve.actual[2].cumulativeR, eps)
        assertEquals(4.0, curve.finalActualR!!, eps)
    }

    @Test fun `the curve covers only taken and closed entries`() {
        val curve = VerdictJournal.curve(
            listOf(
                entry(symbol = "A", exitDateIso = "2026-08-10"),
                entry(symbol = "B", exitPrice = null, exitDateIso = null),      // open
                fresh(symbol = "C"),                                            // undecided
                entry(symbol = "D", taken = TakenState.NOT_TAKEN),              // passed
            ),
        )
        assertEquals(listOf("A"), curve.actual.map { it.symbol })
        assertEquals(2.0, curve.finalActualR!!, eps)
    }

    @Test fun `a close with no stop is left off the curve and counted rather than plotted as zero R`() {
        val curve = VerdictJournal.curve(
            listOf(
                entry(symbol = "A", exitDateIso = "2026-08-10"),
                entry(symbol = "B", stop = null, exitDateIso = "2026-08-11"),
            ),
        )
        assertEquals(1, curve.actual.size)
        assertEquals(1, curve.unscoreableCloses)
        assertEquals(2.0, curve.finalActualR!!, eps) // not 2.0 + a fabricated 0.0 step
    }

    @Test fun `a close with no exit date is counted rather than sorted to the front of the series`() {
        // An undated close cannot be placed in time. Sorting it to the front would invent a sequence
        // and change the shape of the drawdown drawn from it.
        val curve = VerdictJournal.curve(
            listOf(
                entry(symbol = "A", exitDateIso = "2026-08-10"),
                entry(symbol = "B", exitPrice = 90.0, exitDateIso = null),
            ),
        )
        assertEquals(listOf("A"), curve.actual.map { it.symbol })
        assertEquals(1, curve.undatedCloses)
    }

    @Test fun `the curve leaves the mechanical series null rather than fabricating one`() {
        // A curve drawn from a plan nobody replayed is exactly the invented number this feature
        // exists to eliminate. Null, and NOT an empty list — empty would mean a replay ran.
        val curve = VerdictJournal.curve(listOf(entry()))
        assertNull(curve.mechanical)
        assertNull(curve.finalMechanicalR)
        assertNull(curve.executionGapR) // no gap against a curve that was never drawn
    }

    @Test fun `the execution gap needs both curves and appears once the replay engine supplies one`() {
        val actual = VerdictJournal.curve(listOf(entry())).actual // +2.0R
        val replayed = listOf(
            VerdictJournal.CurvePoint(symbol = "AAPL", closeDateIso = "2026-08-20", r = 3.0, cumulativeR = 3.0),
        )
        val both = VerdictJournal.ActualVsPlanCurve(actual = actual, mechanical = replayed)
        assertEquals(2.0, both.finalActualR!!, eps)
        assertEquals(3.0, both.finalMechanicalR!!, eps)
        assertEquals(-1.0, both.executionGapR!!, eps) // you got 1R less than the mechanical plan
    }

    @Test fun `an empty curve reports a null final R rather than zero`() {
        val curve = VerdictJournal.curve(listOf(fresh()))
        assertTrue(curve.actual.isEmpty())
        assertNull(curve.finalActualR)
    }

    // ------------------------------------------------------------------ persistence, old records

    @Test fun `an entry stored before the fill and exit fields existed loads as unknown`() {
        // The oldest shape this store could have written: a symbol, a date, and nothing else. Every
        // absent field must decode to null / UNDECIDED, NOT to 0.0 — Http.json sets coerceInputValues,
        // which is exactly how "Stop $0 · target $0" reached a money decision on the plan card.
        val legacy = """{"id":"abc","symbol":"AAPL","verdictDateIso":"2026-08-01"}"""
        val e = Http.json.decodeFromString<VerdictJournalEntry>(legacy)
        assertEquals("AAPL", e.symbol)
        assertEquals(TakenState.UNDECIDED, e.taken)
        assertEquals(JournalStatus.UNDECIDED, e.status)
        assertNull(e.fillPrice)
        assertNull(e.shares)
        assertNull(e.fillDateIso)
        assertNull(e.exitPrice)
        assertNull(e.exitDateIso)
        assertNull(e.plan.stop)
        assertNull(e.plan.target)
        assertNull(e.plan.conviction)
        assertNull(e.rMultiple)
        assertNull(e.exitKind)
    }

    @Test fun `an entry stored before the plan snapshot carried levels loads them as unknown`() {
        val partial = """
            {"id":"abc","symbol":"AAPL","verdictDateIso":"2026-08-01",
             "plan":{"action":"buy_now","thesis":"Base breakout."},
             "taken":"TAKEN","fillPrice":100.0,"shares":10.0,
             "exitPrice":120.0,"exitDateIso":"2026-08-20"}
        """.trimIndent()
        val e = Http.json.decodeFromString<VerdictJournalEntry>(partial)
        assertEquals("buy_now", e.plan.action)
        assertNull(e.plan.stop)
        assertFalse(e.plan.hasLevels)
        assertTrue(e.isClosed)
        // Closed, real money made — but permanently unscoreable in R, and named as such.
        assertEquals(200.0, e.realizedPnl!!, eps)
        assertNull(e.rMultiple)
        assertEquals(ExitTaxonomy.ExitKind.UNPLANNED, e.exitKind)
    }

    @Test fun `an explicit null level decodes as unknown and not as a stop of zero`() {
        // coerceInputValues turns an explicit null into the default for a NON-nullable field. The
        // levels are nullable precisely so this stays null instead of becoming a $0 stop, which would
        // score a $100 fill against $100 of risk and report a confident wrong R.
        val json = """
            {"id":"abc","symbol":"AAPL","verdictDateIso":"2026-08-01",
             "plan":{"stop":null,"target":null},"taken":"TAKEN",
             "fillPrice":100.0,"exitPrice":120.0,"exitDateIso":"2026-08-20"}
        """.trimIndent()
        val e = Http.json.decodeFromString<VerdictJournalEntry>(json)
        assertNull(e.plan.stop)
        assertNull(e.rMultiple)
    }

    @Test fun `an unrecognised taken state decodes as undecided rather than failing`() {
        val json = """{"id":"abc","symbol":"AAPL","verdictDateIso":"2026-08-01","taken":"MAYBE"}"""
        val e = Http.json.decodeFromString<VerdictJournalEntry>(json)
        assertEquals(TakenState.UNDECIDED, e.taken)
    }

    @Test fun `an entry survives a round trip through the store's codec`() {
        val original = entry()
        val restored = Http.json.decodeFromString<VerdictJournalEntry>(Http.json.encodeToString(original))
        assertEquals(original, restored)
        assertNotNull(restored.rMultiple)
        assertEquals(2.0, restored.rMultiple!!, eps)
    }

    @Test fun `an undecided entry round trips without acquiring a zero fill`() {
        val restored = Http.json.decodeFromString<VerdictJournalEntry>(Http.json.encodeToString(fresh()))
        assertNull(restored.fillPrice)
        assertNull(restored.exitPrice)
        assertEquals(TakenState.UNDECIDED, restored.taken)
    }

    // ------------------------------------------------------------------ the shared classifier

    @Test fun `the options history keeps its buckets after the price classifier was factored out`() {
        // ExitTaxonomy.classify now delegates to classifyAgainstLevels. The options-side semantics
        // must be unchanged: $2.00 premium, 50% stop → $1.00, 80% target → $3.60.
        assertEquals(
            ExitTaxonomy.ExitKind.TARGET,
            ExitTaxonomy.classifyAgainstLevels(exitPrice = 3.60, stopPrice = 1.00, targetPrice = 3.60),
        )
        assertEquals(
            ExitTaxonomy.ExitKind.STOP,
            ExitTaxonomy.classifyAgainstLevels(exitPrice = 1.00, stopPrice = 1.00, targetPrice = 3.60),
        )
        assertEquals(
            ExitTaxonomy.ExitKind.DISCRETIONARY,
            ExitTaxonomy.classifyAgainstLevels(exitPrice = 2.50, stopPrice = 1.00, targetPrice = 3.60),
        )
    }

    @Test fun `a non-finite level counts as absent rather than as a level that was never reached`() {
        // A NaN comparison silently answers false, which would report a confident DISCRETIONARY.
        assertEquals(
            ExitTaxonomy.ExitKind.UNPLANNED,
            ExitTaxonomy.classifyAgainstLevels(exitPrice = 120.0, stopPrice = Double.NaN, targetPrice = null),
        )
        assertEquals(
            ExitTaxonomy.ExitKind.UNPLANNED,
            ExitTaxonomy.classifyAgainstLevels(exitPrice = Double.NaN, stopPrice = 90.0, targetPrice = 130.0),
        )
    }

    @Test fun `the R aggregate is fed the nulls so the unscoreable count survives`() {
        // Passing only the scored values would report an expectancy over 2 trades as though it were
        // over 3, with nothing left in the object to say otherwise.
        val aggregate = RiskMultiple.aggregateR(listOf(2.0, null, -1.0))
        assertEquals(3, aggregate.closedCount)
        assertEquals(2, aggregate.scored)
        assertEquals(1, aggregate.unscoreable)
        assertEquals(0.5, aggregate.avgR!!, eps) // (2.0 − 1.0) / 2 scored
    }
}
