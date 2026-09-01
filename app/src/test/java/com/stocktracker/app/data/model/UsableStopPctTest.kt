package com.stocktracker.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SWT-11 — the entry form and the scoring end must agree on what a stop is.
 *
 * They had drifted apart in the only way that matters. `CallEntryDialog` accepted whatever
 * `toDoubleOrNull()` gave it — nothing at all if the field was cleared, or 150 if that is what was
 * typed — and stored it without complaint. `RiskMultiple.stopPriceFromPct` then rejected exactly
 * those values and returned null. So the position was accepted at entry, displayed like any other,
 * and revealed as permanently unscoreable only once it closed and someone went looking for its R —
 * at which point the number the trade was actually taken with can no longer be recovered.
 *
 * `isUsableStopPct` is that shared predicate. These pin it to `stopPriceFromPct`'s behaviour rather
 * than to a restatement of the same rule, so the two cannot drift again.
 */
class UsableStopPctTest {

    private companion object {
        const val ENTRY = 2.00   // $2.00 premium per share
    }

    /** The invariant that matters: accepted at entry == scoreable at exit, for every input. */
    private fun assertAgreesWithScoring(pct: Double?) {
        val usable = RiskMultiple.isUsableStopPct(pct)
        val price = RiskMultiple.stopPriceFromPct(ENTRY, pct)
        assertEquals(
            "isUsableStopPct($pct)=$usable disagrees with stopPriceFromPct -> $price",
            usable,
            price != null,
        )
    }

    @Test fun `the predicate and the conversion agree on every shape of input`() {
        listOf(
            null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            -50.0, -0.001, 0.0, 0.001, 1.0, 50.0, 99.999, 100.0, 100.001, 150.0, 1_000.0,
        ).forEach { assertAgreesWithScoring(it) }
    }

    // --- the boundaries, stated on their own so a change has to be deliberate --------------------

    @Test fun `a cleared field is not a stop`() {
        assertFalse(RiskMultiple.isUsableStopPct(null))
    }

    @Test fun `zero is not a stop`() {
        // A 0% stop is a stop price equal to the entry — stopped out the instant it is opened.
        assertFalse(RiskMultiple.isUsableStopPct(0.0))
    }

    @Test fun `risking the whole premium is legal and means a stop price of zero`() {
        assertTrue(RiskMultiple.isUsableStopPct(100.0))
        assertEquals(0.0, RiskMultiple.stopPriceFromPct(ENTRY, 100.0)!!, 1e-9)
    }

    @Test fun `more than the premium is refused rather than clamped`() {
        // Clamping 150 to 100 would report a confident R from a nonsense input.
        assertFalse(RiskMultiple.isUsableStopPct(100.001))
        assertFalse(RiskMultiple.isUsableStopPct(150.0))
        assertNull(RiskMultiple.stopPriceFromPct(ENTRY, 150.0))
    }

    @Test fun `non-finite input is refused`() {
        assertFalse(RiskMultiple.isUsableStopPct(Double.NaN))
        assertFalse(RiskMultiple.isUsableStopPct(Double.POSITIVE_INFINITY))
    }

    @Test fun `a usable stop still yields no price against a nonsense entry`() {
        // The predicate is about the PERCENT alone; the entry is the conversion's own problem.
        assertTrue(RiskMultiple.isUsableStopPct(50.0))
        assertNull(RiskMultiple.stopPriceFromPct(0.0, 50.0))
        assertNull(RiskMultiple.stopPriceFromPct(Double.NaN, 50.0))
    }

    // --- what the form is actually protecting ----------------------------------------------------

    @Test fun `the default the dialog pre-fills is one the scorer accepts`() {
        // The dialog starts at "50" and the exit alerts assume 50 when the record has none. If that
        // default were ever changed to something this predicate rejects, every new position would be
        // silently unscoreable and nothing else would notice.
        val default = com.stocktracker.app.notify.CallExitRules.DEFAULT_STOP_PCT
        assertTrue("DEFAULT_STOP_PCT=$default is not a scoreable stop", RiskMultiple.isUsableStopPct(default))
    }

    @Test fun `a position taken at the default is scoreable end to end`() {
        val price = RiskMultiple.stopPriceFromPct(ENTRY, 50.0)
        assertNotNull(price)
        assertEquals(1.00, price!!, 1e-9)
        // Sold at $3.00 against $1.00 of risk is +1R.
        assertEquals(1.0, RiskMultiple.rMultipleFromStopPct(ENTRY, 3.00, 50.0)!!, 1e-9)
    }
}
