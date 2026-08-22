package com.stocktracker.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TWO CURVES, and the one rule that makes drawing them honest (SWT-8).
 *
 * [VerdictJournal] already refuses to invent a mechanical series. This is the other half: once the
 * backend HAS replayed the plans, the comparison is only honest over a population BOTH sides could
 * take, and the drop-outs are not random. A plan that never filled is one where price ran away from
 * the zone — disproportionately the trades you chased and got into anyway. Plotting your point for
 * those while the mechanical curve skips them compares your best decisions to the plan's full record,
 * and the resulting picture is systematically, invisibly flattering.
 *
 * So every test here is a variant of one question: can a trade end up on one curve alone? It must
 * not, and when it is dropped the reason must be counted and sayable.
 */
class JournalComparisonTest {

    private val eps = 1e-9

    /** A resolved replay: the plan filled and exited, worth [r] in the risk it defined. */
    private fun replay(
        r: Double? = 1.0,
        outcome: String = JournalReplay.TARGET,
        ambiguous: Boolean = false,
        refused: Boolean = false,
    ) = JournalReplay(
        outcome = if (refused) null else outcome,
        refused = refused,
        ambiguous = ambiguous,
        entryPrice = 101.0,
        entryDate = "2026-08-04",
        exitPrice = 130.0,
        exitDate = "2026-08-18",
        barsHeld = 11,
        r = r,
        replayedAtMs = 1_000L,
    )

    /**
     * A closed, scoreable entry. Defaults are the worked plan the journal's own tests use: stop $90,
     * your fill $100 — risk $10/share — so a $120 exit is +2.0R.
     */
    private fun closed(
        symbol: String = "AAPL",
        exitDateIso: String? = "2026-08-20",
        exitPrice: Double? = 120.0,
        stop: Double? = 90.0,
        replay: JournalReplay? = replay(),
        taken: TakenState = TakenState.TAKEN,
        fillPrice: Double? = 100.0,
    ) = VerdictJournalEntry(
        symbol = symbol,
        verdictDateIso = "2026-08-01",
        plan = JournalPlan(action = "buy_now", entryLow = 98.0, entryHigh = 102.0, stop = stop, target = 130.0),
        taken = taken,
        fillPrice = fillPrice,
        shares = 10.0,
        fillDateIso = "2026-08-02",
        exitPrice = exitPrice,
        exitDateIso = exitDateIso,
        replay = replay,
    )

    // ------------------------------------------------------------------ both curves or neither

    @Test fun `an entry with no replay is on neither curve and is counted by reason`() {
        val paired = JournalComparison.pair(listOf(closed(replay = null)))
        assertTrue(paired.curve.actual.isEmpty())
        // An EMPTY mechanical list, not null: a pairing pass ran and found nothing to pair, which is
        // a different statement from VerdictJournal.curve's "nobody has replayed anything".
        assertEquals(emptyList<VerdictJournal.CurvePoint>(), paired.curve.mechanical)
        assertEquals(1, paired.takenConsidered)
        assertEquals(
            listOf(JournalComparison.ExcludedFor(JournalComparison.Exclusion.NOT_REPLAYED, 1)),
            paired.excluded,
        )
        // And emphatically not a zero point on your side, which is the flattering failure: your +2R
        // would stand against a plan curve that never took the trade.
        assertNull(paired.yours.avgR)
        assertNull(paired.mechanical.avgR)
    }

    @Test fun `a trade the plan never filled is dropped from your curve too`() {
        // The load-bearing case. Price ran away, the plan never got in, and you bought anyway and
        // made 2R. Keeping your point would credit you with a trade the plan declined to take.
        val entries = listOf(
            closed(symbol = "AAA"),
            closed(symbol = "BBB", replay = JournalReplay(outcome = JournalReplay.NEVER_FILLED, r = null)),
        )
        val paired = JournalComparison.pair(entries)
        assertEquals(listOf("AAA"), paired.curve.actual.map { it.symbol })
        assertEquals(listOf("AAA"), paired.curve.mechanical!!.map { it.symbol })
        assertEquals(
            listOf(JournalComparison.ExcludedFor(JournalComparison.Exclusion.NEVER_FILLED, 1)),
            paired.excluded,
        )
    }

    @Test fun `every exclusion reason is counted separately and they sum to the drop-outs`() {
        val entries = listOf(
            closed(symbol = "OK"),
            closed(symbol = "NOFILL", fillPrice = null, exitPrice = null),
            closed(symbol = "OPEN", exitPrice = null, exitDateIso = null),
            closed(symbol = "NODATE", exitDateIso = null),
            closed(symbol = "NOSTOP", stop = null),
            closed(symbol = "NOREPLAY", replay = null),
            closed(symbol = "REFUSED", replay = replay(refused = true)),
            closed(symbol = "PENDING", replay = JournalReplay(outcome = null)),
            closed(symbol = "RUNNING", replay = JournalReplay(outcome = JournalReplay.OPEN, markPrice = 111.0)),
            closed(symbol = "UNFILLED", replay = JournalReplay(outcome = JournalReplay.NEVER_FILLED)),
            closed(symbol = "NOPLANSTOP", replay = replay(r = null)),
        )
        val paired = JournalComparison.pair(entries)
        assertEquals(11, paired.takenConsidered)
        assertEquals(1, paired.pairedCount)
        assertEquals(10, paired.excludedCount)
        // Every reason in the enum fired, exactly once, and they arrive in enum order — which is the
        // order the sentence under the chart reads them out in.
        assertEquals(JournalComparison.Exclusion.entries.toList(), paired.excluded.map { it.reason })
        assertTrue(paired.excluded.all { it.count == 1 })
        // The counts partition the taken set: nothing is quietly dropped for being awkward.
        assertEquals(paired.takenConsidered, paired.pairedCount + paired.excludedCount)
    }

    @Test fun `an entry you can score but the plan cannot is on neither curve`() {
        // Your side is perfectly scoreable; the replay resolved with no stop, so it has no R. One
        // curve alone is not a comparison.
        val paired = JournalComparison.pair(listOf(closed(replay = replay(r = null))))
        assertTrue(paired.curve.actual.isEmpty())
        assertEquals(
            listOf(JournalComparison.Exclusion.REPLAY_UNSCOREABLE),
            paired.excluded.map { it.reason },
        )
    }

    @Test fun `a still-open trade is excluded rather than plotted as a flat zero`() {
        val paired = JournalComparison.pair(listOf(closed(exitPrice = null, exitDateIso = null)))
        assertTrue(paired.curve.actual.isEmpty())
        assertEquals(listOf(JournalComparison.Exclusion.STILL_OPEN), paired.excluded.map { it.reason })
        assertNull(paired.executionGapR)
    }

    // ------------------------------------------------------------------ alignment

    @Test fun `the two series align index for index over the shared population`() {
        val entries = listOf(
            closed(symbol = "C", exitDateIso = "2026-09-03", exitPrice = 120.0, replay = replay(r = 0.5)),
            closed(symbol = "A", exitDateIso = "2026-09-01", exitPrice = 80.0, replay = replay(r = -1.0)),
            closed(symbol = "SKIP", exitDateIso = "2026-09-02", replay = null),
            closed(symbol = "B", exitDateIso = "2026-09-02", exitPrice = 130.0, replay = replay(r = 2.0)),
        )
        val paired = JournalComparison.pair(entries)
        val mine = paired.curve.actual
        val plan = paired.curve.mechanical!!

        assertEquals(mine.size, plan.size)
        // ONE order for both — your close order — so point i on each line is the same trade.
        assertEquals(listOf("A", "B", "C"), mine.map { it.symbol })
        assertEquals(listOf("A", "B", "C"), plan.map { it.symbol })
        mine.indices.forEach { i -> assertEquals(mine[i].closeDateIso, plan[i].closeDateIso) }

        // Your R: −2.0 (exit 80 on a 100/90 plan), +3.0 (130), +2.0 (120).
        assertEquals(listOf(-2.0, 1.0, 3.0), mine.map { it.cumulativeR })
        assertEquals(listOf(-1.0, 1.0, 1.5), plan.map { it.cumulativeR })
        assertEquals(3.0, paired.curve.finalActualR!!, eps)
        assertEquals(1.5, paired.curve.finalMechanicalR!!, eps)
        assertEquals(1.5, paired.executionGapR!!, eps) // you beat the mechanical plan by 1.5R
    }

    @Test fun `the two expectancies are computed over the same trades in the same order`() {
        val entries = listOf(
            closed(symbol = "A", exitDateIso = "2026-09-01", exitPrice = 130.0, replay = replay(r = 1.0)),
            closed(symbol = "B", exitDateIso = "2026-09-02", exitPrice = 80.0, replay = replay(r = -1.0)),
        )
        val paired = JournalComparison.pair(entries)
        assertEquals(2, paired.yours.scored)
        assertEquals(2, paired.mechanical.scored)
        // Nothing unscoreable can reach an aggregate here: exclusionFor rejected it first, which is
        // exactly what makes the two averages comparable.
        assertEquals(0, paired.yours.unscoreable)
        assertEquals(0, paired.mechanical.unscoreable)
        assertEquals(0.5, paired.yours.avgR!!, eps)   // (+3.0 and −2.0) / 2
        assertEquals(0.0, paired.mechanical.avgR!!, eps)
    }

    // ------------------------------------------------------------------ passes

    @Test fun `declined entries enter neither curve but do enter the taken and passed counts`() {
        val entries = listOf(
            closed(symbol = "TOOK"),
            // A pass that still carries a replay and stale fill fields — the plan made 2R without you.
            closed(symbol = "PASSED", taken = TakenState.NOT_TAKEN),
            closed(symbol = "DUNNO", taken = TakenState.UNDECIDED),
        )
        val paired = JournalComparison.pair(entries)
        assertEquals(listOf("TOOK"), paired.curve.actual.map { it.symbol })
        assertEquals(listOf("TOOK"), paired.curve.mechanical!!.map { it.symbol })
        // Not "excluded" either: they were never candidates, so counting them as drop-outs would make
        // the chart's population line report your passes as a data problem.
        assertEquals(1, paired.takenConsidered)
        assertTrue(paired.excluded.isEmpty())

        val record = VerdictJournal.record(entries)
        assertEquals(3, record.entryCount)
        assertEquals(1, record.takenCount)
        assertEquals(1, record.notTakenCount)
        assertEquals(1, record.undecidedCount)
        // The headline the whole feature exists for survives: half of the decided verdicts were passes.
        assertEquals(50.0, record.takeRatePct!!, eps)
    }

    @Test fun `a pass is never scored even though it kept a replay`() {
        val paired = JournalComparison.pair(listOf(closed(taken = TakenState.NOT_TAKEN)))
        assertTrue(paired.isEmpty)
        assertNull(paired.yours.avgR)
        assertNull(paired.mechanical.avgR)
        assertNull(paired.executionGapR)
    }

    // ------------------------------------------------------------------ null outcome is not an error

    @Test fun `an outcome of null reads as not replayed yet and is never a zero point`() {
        // The single most common state in a fresh journal: the plan is fine, no session has traded
        // since it was written. It is not an error, and it is not a 0R trade.
        val pending = JournalReplay(
            outcome = null,
            reason = "no session has traded since 20260821 — nothing to replay yet",
            replayedAtMs = 5L,
        )
        assertEquals("Not replayed yet", JournalReplay.describe(pending))
        assertTrue(pending.isPending)
        assertFalse(pending.refused)
        assertFalse(pending.isResolved)
        assertNull(pending.scoredR)

        val paired = JournalComparison.pair(listOf(closed(replay = pending)))
        assertTrue(paired.curve.actual.isEmpty())
        assertTrue(paired.curve.mechanical!!.isEmpty())
        assertEquals(listOf(JournalComparison.Exclusion.REPLAY_PENDING), paired.excluded.map { it.reason })
        assertEquals("nothing has traded since", JournalComparison.label(JournalComparison.Exclusion.REPLAY_PENDING))
    }

    @Test fun `no replay at all also reads as not replayed yet rather than as a failure`() {
        assertEquals("Not replayed yet", JournalReplay.describe(null))
        // A refusal is a DIFFERENT sentence: something is wrong with the plan, and saying "not
        // replayed yet" there would promise an answer that will never come.
        assertEquals("Plan can't be replayed", JournalReplay.describe(JournalReplay(refused = true)))
    }

    @Test fun `an open replay is not resolved and carries no R even if the server sent one`() {
        val open = JournalReplay(outcome = JournalReplay.OPEN, markPrice = 118.0, r = 1.8)
        // A mark is not an exit and an unrealized R is not an R. The gate is the OUTCOME, not the
        // presence of a number in the r field.
        assertFalse(open.isResolved)
        assertNull(open.scoredR)
        assertEquals("Plan still open", JournalReplay.describe(open))
    }

    @Test fun `a resolved outcome with a non-finite R is treated as unscoreable`() {
        val poisoned = JournalReplay(outcome = JournalReplay.STOP, r = Double.NaN)
        assertNull(poisoned.scoredR)
        val paired = JournalComparison.pair(listOf(closed(replay = poisoned)))
        assertTrue(paired.isEmpty)
        assertEquals(listOf(JournalComparison.Exclusion.REPLAY_UNSCOREABLE), paired.excluded.map { it.reason })
    }

    @Test fun `an outcome this build has never heard of is excluded rather than plotted`() {
        val future = JournalReplay(outcome = "delisted", r = 1.0)
        assertFalse(future.isResolved)
        val paired = JournalComparison.pair(listOf(closed(replay = future)))
        assertTrue(paired.isEmpty)
        assertEquals(listOf(JournalComparison.Exclusion.REPLAY_UNSCOREABLE), paired.excluded.map { it.reason })
        assertEquals("delisted", JournalReplay.describe(future)) // said verbatim, not swallowed
    }

    // ------------------------------------------------------------------ ambiguity

    @Test fun `the ambiguous count surfaces when a contributing replay carries the flag`() {
        val entries = listOf(
            closed(symbol = "A", exitDateIso = "2026-09-01"),
            closed(symbol = "B", exitDateIso = "2026-09-02", replay = replay(r = -1.0, outcome = JournalReplay.STOP, ambiguous = true)),
        )
        val paired = JournalComparison.pair(entries)
        assertEquals(2, paired.pairedCount)
        assertEquals(1, paired.ambiguousCount)
    }

    @Test fun `an ambiguous replay that is not on the curve is not counted`() {
        // The count answers "how much of the curve rests on the assumption", so only the trades the
        // curve actually rests on may count. An excluded row contributed nothing to lean on.
        val entries = listOf(
            closed(symbol = "A"),
            closed(symbol = "OPEN", exitPrice = null, exitDateIso = null,
                replay = replay(outcome = JournalReplay.STOP, ambiguous = true)),
        )
        val paired = JournalComparison.pair(entries)
        assertEquals(1, paired.pairedCount)
        assertEquals(0, paired.ambiguousCount)
    }

    @Test fun `no ambiguity at all reports zero rather than a null nobody can print`() {
        assertEquals(0, JournalComparison.pair(listOf(closed())).ambiguousCount)
    }

    // ------------------------------------------------------------------ the sentence under the chart

    @Test fun `the population sentence names the count and every reason`() {
        val entries = listOf(
            closed(symbol = "A", exitDateIso = "2026-09-01"),
            closed(symbol = "B", exitDateIso = "2026-09-02"),
            closed(symbol = "OPEN1", exitPrice = null, exitDateIso = null),
            closed(symbol = "OPEN2", exitPrice = null, exitDateIso = null),
            closed(symbol = "NOFILL", replay = JournalReplay(outcome = JournalReplay.NEVER_FILLED)),
        )
        val paired = JournalComparison.pair(entries)
        assertEquals(
            "2 entries on both curves; 3 excluded — 2 still open, 1 never filled.",
            JournalComparison.populationSentence(paired),
        )
    }

    @Test fun `a clean population says so without inventing an exclusion clause`() {
        val paired = JournalComparison.pair(listOf(closed()))
        assertEquals("1 entry on both curves.", JournalComparison.populationSentence(paired))
    }

    @Test fun `an empty pairing says nothing is on the curves rather than showing zero R`() {
        val paired = JournalComparison.pair(listOf(closed(replay = null)))
        assertTrue(paired.isEmpty)
        assertEquals(
            "No entries on both curves; 1 excluded — 1 not replayed yet.",
            JournalComparison.populationSentence(paired),
        )
        assertNull(paired.curve.finalActualR)
        assertNull(paired.curve.finalMechanicalR)
    }

    @Test fun `an empty journal pairs to nothing without failing`() {
        val paired = JournalComparison.pair(emptyList())
        assertTrue(paired.isEmpty)
        assertEquals(0, paired.takenConsidered)
        assertEquals("No entries on both curves.", JournalComparison.populationSentence(paired))
    }

    // ------------------------------------------------------------------ the recorded replay

    @Test fun `the replay is stored on the entry so a curve never refetches it`() {
        val entry = closed()
        assertNotNull(entry.replay)
        assertEquals(1.0, entry.mechanicalR!!, eps)
        // And it is dated, because a mechanical outcome recomputed later can quietly differ.
        assertEquals(1_000L, entry.replay!!.replayedAtMs)
    }

    @Test fun `the unscoreable and undated counts still travel with the curve for the old readers`() {
        val entries = listOf(
            closed(symbol = "NOSTOP", stop = null),
            closed(symbol = "NODATE", exitDateIso = null),
        )
        val paired = JournalComparison.pair(entries)
        assertEquals(1, paired.curve.unscoreableCloses)
        assertEquals(1, paired.curve.undatedCloses)
    }
}
