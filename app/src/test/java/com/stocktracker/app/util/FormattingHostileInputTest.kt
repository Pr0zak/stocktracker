package com.stocktracker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the display formatters do with numbers they should never receive but sometimes will.
 *
 * Found 2026-08-14 by probing every formatter with hostile values. Two defects, both reachable:
 *
 *  - `roundToLong()` THROWS IllegalArgumentException on NaN, and both the hide-zero-cents path and
 *    [Formatting.compact] call it. One NaN quote crashed the composable drawing the row, gated on
 *    nothing more than a user setting being on.
 *  - Everything else printed "$NaN" and "$Infinity" directly into the price column. That is the
 *    house defect this codebase keeps fixing: a value the reader takes as a number because it is
 *    sitting where numbers go.
 */
class FormattingHostileInputTest {

    private val nonFinite = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

    @Test
    fun `no formatter throws on a non-finite input`() {
        for (v in nonFinite) {
            for (hide in listOf(true, false)) {
                Formatting.price(v, "USD", hide)
                Formatting.change(v, hide)
                Formatting.changeLine(v, v, true, hide)
            }
            Formatting.percent(v)
            Formatting.shares(v)
            Formatting.compact(v)
        }
    }

    @Test
    fun `a non-finite value renders as a dash, never as a number`() {
        for (v in nonFinite) {
            assertEquals("—", Formatting.price(v, "USD", false))
            assertEquals("—", Formatting.price(v, "USD", true))
            assertEquals("—", Formatting.shares(v))
            assertEquals("—", Formatting.compact(v))
            assertEquals("—", Formatting.percent(v))
            // No arrow either: a direction beside an unknown move is a claim the data cannot support.
            assertEquals("—", Formatting.changeLine(v, v, true, false))
        }
    }

    @Test
    fun `a sub-penny price shows its real value rather than zero`() {
        // SHIB trades near $0.000012 and is on the crypto watchlist. Four decimals rendered it
        // "$0.0000" -- a price of zero for an asset the user may hold hundreds of dollars of.
        assertEquals("$0.00001208", Formatting.price(0.00001208, "USD", false))
        // And the same with hide-zero-cents on. That shortcut's rounding test is trivially true for
        // every sub-dollar value, so it used to flatten this to "$0" regardless of the fix above.
        assertEquals("$0.00001208", Formatting.price(0.00001208, "USD", true))
    }

    @Test
    fun `ordinary prices are unchanged in both modes`() {
        assertEquals("$231.40", Formatting.price(231.4012, "USD", false))
        assertEquals("$62,820.00", Formatting.price(62_820.0, "USD", false))
        assertEquals("$62,820", Formatting.price(62_820.0, "USD", true))
        assertEquals("$1", Formatting.price(1.0, "USD", true))
        assertEquals("$1.00", Formatting.price(1.0, "USD", false))
        assertEquals("-$1.50", "-" + Formatting.price(1.5, "USD", false))
    }

    @Test
    fun `hide zero cents still drops the decimals it exists to drop`() {
        assertTrue(!Formatting.price(100.0, "USD", true).contains("."))
        assertTrue(Formatting.price(100.25, "USD", true).contains(".25"))
    }
}
