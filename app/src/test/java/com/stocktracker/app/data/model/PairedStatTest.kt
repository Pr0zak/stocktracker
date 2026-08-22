package com.stocktracker.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paired statistic (SWT-9).
 *
 * THE PAIR THIS GUARDS. The reference service publishes, for the same breakout plan, a backtest of
 * 514 trades at a 50.0% win rate and 1.53 profit factor — and a live journal of 23 closed trades at
 * 73.9% and 5.17. More than three times the edge on a twentieth of the evidence. Nothing in either
 * row is false; the deception is in showing one row without the other, or either row without its n.
 *
 * So the invariants pinned here are the four that make that deception impossible to render:
 * a value cannot exist without a sample size, a missing half is missing rather than zero, a sample
 * under the shared floor prints counts instead of a percentage, and a sharp divergence over a thin
 * sample raises a flag NEXT TO the number.
 */
class PairedStatTest {

    private fun rate(hits: Int, n: Int) = PairedStat.StatSample.rate(hits, n)

    private fun winRatePair(
        backtest: PairedStat.StatSample?,
        forward: PairedStat.StatSample?,
        forwardAbsent: String = "nothing has closed live yet",
    ) = PairedStat(
        label = "Win rate",
        unit = PairedStat.StatUnit.RATE_PCT,
        backtest = PairedStat.Side.backtest("the plan, simulated", backtest, "never simulated"),
        forward = PairedStat.Side.forward("your fills", forward, forwardAbsent),
    )

    // ------------------------------------------------------------------ the floor is shared

    /**
     * ASSERTED DIRECTLY, not by value. `assertEquals(20, PairedStat.FLOOR)` would still pass on the
     * day someone moved one of the other two floors and left this one behind — which is exactly the
     * drift the shared constant exists to prevent.
     */
    @Test fun `the small-sample floor is the same constant the rest of the app uses`() {
        assertEquals(RiskMultiple.MIN_SCORED_FOR_EXPECTANCY, PairedStat.FLOOR)
        assertEquals(ExitTaxonomy.MIN_CLASSIFIED_FOR_RATES, PairedStat.FLOOR)
        // And the sample's own view of "too thin" is that same floor, not a second opinion.
        assertTrue(PairedStat.StatSample.rate(1, PairedStat.FLOOR - 1).underFloor)
        assertFalse(PairedStat.StatSample.rate(1, PairedStat.FLOOR).underFloor)
    }

    // ------------------------------------------------------------------ a sample is never optional

    @Test fun `a statistic cannot be constructed without a sample size`() {
        val boom = runCatching { PairedStat.StatSample(n = 0, value = 73.9) }.exceptionOrNull()
        assertTrue(boom is IllegalArgumentException)
        val negative = runCatching { PairedStat.StatSample(n = -3, value = 73.9) }.exceptionOrNull()
        assertTrue(negative is IllegalArgumentException)
    }

    /**
     * The render sites cannot afford the throwing constructor: a pathological expectancy would take
     * the screen down rather than degrade to "no record". [PairedStat.StatSample.of] rejects exactly
     * what the constructor rejects, and returns null so the caller lands in its absent branch.
     */
    @Test fun `the total form returns null where the constructor would throw`() {
        assertNull(PairedStat.StatSample.of(n = 0, value = 73.9))
        assertNull(PairedStat.StatSample.of(n = 10, value = null))
        assertNull(PairedStat.StatSample.of(n = 10, value = Double.POSITIVE_INFINITY))
        assertNull(PairedStat.StatSample.of(n = 4, value = 125.0, hits = 5))
        assertEquals(10, PairedStat.StatSample.of(n = 10, value = 1.5)!!.n)
    }

    @Test fun `a non-finite value is not a measurement`() {
        assertTrue(
            runCatching { PairedStat.StatSample(n = 10, value = Double.NaN) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test fun `hits cannot exceed the sample they came from`() {
        assertTrue(
            runCatching { PairedStat.StatSample(n = 4, value = 125.0, hits = 5) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test fun `every present side carries its n through to the reading`() {
        val stat = winRatePair(backtest = rate(257, 514), forward = rate(17, 23))
        val bt = stat.backtestReading as PairedStat.Reading.Measured
        val fwd = stat.forwardReading as PairedStat.Reading.Measured
        assertEquals(514, bt.n)
        assertEquals(23, fwd.n)
        // And the line a text-only site prints names both populations.
        assertTrue(stat.line().contains("514"))
        assertTrue(stat.line().contains("23"))
    }

    // ------------------------------------------------------------------ absent is never zero

    @Test fun `a missing forward side reports missing and never zero`() {
        val stat = winRatePair(
            backtest = rate(257, 514),
            forward = null,
            forwardAbsent = "no rule signal has ever been recorded live",
        )
        val reading = stat.forwardReading
        assertTrue(reading is PairedStat.Reading.Absent)
        assertEquals("no rule signal has ever been recorded live", (reading as PairedStat.Reading.Absent).why)

        // Nothing anywhere on the pair turns that absence into a number.
        assertNull(stat.forward.sample)
        assertFalse(stat.bothPresent)
        assertFalse(stat.diverges)
        assertFalse(stat.noisyDivergence)
        assertNull(stat.divergenceNote)
        // And the one-line form still shows the missing half rather than dropping it — a line that
        // silently omitted it would leave the backtest standing alone as the whole story.
        assertTrue(stat.line().contains("no rule signal has ever been recorded live"))
    }

    @Test fun `a side with no sample must say why`() {
        val boom = runCatching {
            PairedStat.Side.forward("your fills", sample = null, absentReason = null)
        }.exceptionOrNull()
        assertTrue(boom is IllegalArgumentException)
        val blank = runCatching {
            PairedStat.Side.forward("your fills", sample = null, absentReason = "   ")
        }.exceptionOrNull()
        assertTrue(blank is IllegalArgumentException)
    }

    @Test fun `a pair with neither half is empty and renders nothing`() {
        val stat = winRatePair(backtest = null, forward = null)
        assertTrue(stat.isEmpty)
        assertTrue(stat.backtestReading is PairedStat.Reading.Absent)
        assertTrue(stat.forwardReading is PairedStat.Reading.Absent)
    }

    @Test fun `the two halves cannot be swapped`() {
        val boom = runCatching {
            PairedStat(
                label = "Win rate",
                unit = PairedStat.StatUnit.RATE_PCT,
                backtest = PairedStat.Side.forward("your fills", rate(3, 4)),
                forward = PairedStat.Side.backtest("the plan", rate(257, 514)),
            )
        }.exceptionOrNull()
        assertTrue(boom is IllegalArgumentException)
    }

    // ------------------------------------------------------------------ under the floor: counts

    @Test fun `under the floor the pair exposes counts and suppresses the percentage`() {
        val stat = winRatePair(backtest = rate(257, 514), forward = rate(3, 4))
        val fwd = stat.forwardReading
        assertTrue(fwd is PairedStat.Reading.Counts)
        assertEquals("3 of 4", (fwd as PairedStat.Reading.Counts).text)
        assertEquals(4, fwd.n)
        // The percentage the counts replaced must not appear anywhere in what gets printed.
        assertFalse(fwd.text.contains("%"))
        assertFalse(stat.line().contains("75%"))

        // The well-sampled half is unaffected — suppression is per side, not per pair.
        val bt = stat.backtestReading
        assertTrue(bt is PairedStat.Reading.Measured)
        assertEquals("50%", (bt as PairedStat.Reading.Measured).text)

        assertTrue(stat.anyUnderFloor)
        assertNotNull(stat.smallSampleNote)
    }

    @Test fun `at exactly the floor the rate is printed`() {
        val stat = winRatePair(backtest = rate(257, 514), forward = rate(10, PairedStat.FLOOR))
        assertTrue(stat.forwardReading is PairedStat.Reading.Measured)
        assertEquals("50%", (stat.forwardReading as PairedStat.Reading.Measured).text)
        assertFalse(stat.anyUnderFloor)
        assertNull(stat.smallSampleNote)
    }

    @Test fun `a rate that arrived already divided cannot invent a count`() {
        // No hits to fall back on, so under the floor the only honest output is the sample and no
        // rate at all — never the percentage it was told not to print.
        val stat = winRatePair(
            backtest = rate(257, 514),
            forward = PairedStat.StatSample(n = 4, value = 75.0),
        )
        val fwd = stat.forwardReading as PairedStat.Reading.Counts
        assertEquals(4, fwd.n)
        assertFalse(fwd.text.contains("75"))
        assertFalse(fwd.text.contains("%"))
    }

    /**
     * An expectancy has no counts to degrade to. Hiding it would leave the other half standing alone
     * — the very failure the pairing exists to prevent — so it is printed WITH the caveat welded on.
     */
    @Test fun `an R expectancy under the floor is shown with its caveat rather than hidden`() {
        val stat = PairedStat(
            label = "Expectancy per trade",
            unit = PairedStat.StatUnit.R_MULTIPLE,
            backtest = PairedStat.Side.backtest("the plan, replayed", PairedStat.StatSample(6, 0.23)),
            forward = PairedStat.Side.forward("your fills", PairedStat.StatSample(6, 0.31)),
        )
        val fwd = stat.forwardReading
        assertTrue(fwd is PairedStat.Reading.Measured)
        assertEquals("+0.3R", (fwd as PairedStat.Reading.Measured).text)
        assertEquals(6, fwd.n)
        assertTrue(stat.anyUnderFloor)
        assertNotNull(stat.smallSampleNote)
    }

    // ------------------------------------------------------------------ noisy divergence

    /** The cautionary pair itself: 514 simulated trades at 50.0%, 23 live ones at 73.9%. */
    @Test fun `a sharp divergence with a small forward sample raises the flag`() {
        val stat = winRatePair(backtest = rate(257, 514), forward = rate(17, 23))
        assertTrue(stat.diverges)                       // 23.9 points apart
        assertFalse(stat.noisyDivergence)               // …but 23 closes clears the floor of 20

        val thinner = winRatePair(backtest = rate(257, 514), forward = rate(3, 4))
        assertTrue(thinner.diverges)                    // 75% against 50%
        assertTrue(thinner.noisyDivergence)             // over four trades
        val note = thinner.divergenceNote
        assertNotNull(note)
        assertTrue(note!!.contains("forward"))
        assertTrue(note.contains("4"))
        // The flag must not need a footnote to be understood, and must name the floor it fired on.
        assertTrue(note.contains("${PairedStat.FLOOR}"))
    }

    @Test fun `a small sample that agrees with the backtest raises nothing`() {
        val stat = winRatePair(backtest = rate(257, 514), forward = rate(2, 4)) // 50% vs 50%
        assertFalse(stat.diverges)
        assertFalse(stat.noisyDivergence)
        assertNull(stat.divergenceNote)
        // Still a thin sample, so the caveat stands even though the divergence flag does not.
        assertNotNull(stat.smallSampleNote)
    }

    @Test fun `a profit factor several times its backtest diverges`() {
        val stat = PairedStat(
            label = "Profit factor",
            unit = PairedStat.StatUnit.RATIO,
            backtest = PairedStat.Side.backtest("the plan", PairedStat.StatSample(514, 1.53)),
            forward = PairedStat.Side.forward("live", PairedStat.StatSample(4, 5.17)),
        )
        assertTrue(stat.diverges)
        assertTrue(stat.noisyDivergence)
    }

    @Test fun `an R expectancy gap under half an R is not sharp`() {
        val stat = PairedStat(
            label = "Expectancy per trade",
            unit = PairedStat.StatUnit.R_MULTIPLE,
            backtest = PairedStat.Side.backtest("the plan", PairedStat.StatSample(514, 0.23)),
            forward = PairedStat.Side.forward("live", PairedStat.StatSample(4, 0.45)),
        )
        assertFalse(stat.diverges)
        assertFalse(stat.noisyDivergence)
        assertNull(stat.divergenceNote)
    }

    // ------------------------------------------------------------------ labels

    @Test fun `the two halves are labelled as different kinds of evidence`() {
        val stat = winRatePair(backtest = rate(257, 514), forward = rate(17, 23))
        assertSame(PairedStat.Evidence.BACKTEST, stat.backtest.evidence)
        assertSame(PairedStat.Evidence.FORWARD, stat.forward.evidence)
        assertEquals("simulated over history", PairedStat.Evidence.BACKTEST.sentence)
        assertEquals("recorded before the outcome was known", PairedStat.Evidence.FORWARD.sentence)
        assertTrue(stat.line().startsWith("Win rate — Backtested"))
        assertTrue(stat.line().contains("Forward"))
    }

    @Test fun `the sample text names the trades, singular and plural`() {
        val stat = winRatePair(backtest = rate(1, 1), forward = rate(17, 23))
        assertEquals("over 1 trade", stat.sampleText(stat.backtest.sample!!))
        assertEquals("over 23 trades", stat.sampleText(stat.forward.sample!!))
    }
}
