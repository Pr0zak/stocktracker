package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.ValueTrapResponse
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MB-17 must not let an absence read as reassurance.
 *
 * "Unclear because the evidence is balanced" and "unclear because we could hardly see anything" are
 * different facts. `assessable` is what separates them, and `missing` names what was unavailable —
 * if either is dropped, a card showing "unclear" on a name with no fundamentals looks like a
 * considered judgement that nothing is wrong.
 */
class ValueTrapTest {

    @Test
    fun `not-enough-data is distinguishable from genuinely-mixed`() {
        val blind = Http.json.decodeFromString<ValueTrapResponse>(
            """{"symbol":"X","verdict":"unclear","confidence":"low","assessable":false,
                "missing":["fundamentals","insider activity"],"note":"n","below_line":true}""")
        assertFalse(blind.assessable)
        assertEquals(2, blind.missing.size)

        val mixed = Http.json.decodeFromString<ValueTrapResponse>(
            """{"symbol":"Y","verdict":"unclear","confidence":"medium","assessable":true,
                "red":["Free cash flow is falling"],"green":["Low debt"],"note":"n"}""")
        assertTrue(mixed.assessable)
        assertTrue(mixed.red.isNotEmpty() && mixed.green.isNotEmpty())
    }

    @Test
    fun `both verdicts and their evidence survive decoding`() {
        val bad = Http.json.decodeFromString<ValueTrapResponse>(
            """{"symbol":"Z","verdict":"deteriorating","confidence":"high","assessable":true,
                "red":["Free cash flow is falling","Share count up 8.0% — dilution"],"note":"n"}""")
        assertEquals("deteriorating", bad.verdict)
        assertEquals(2, bad.red.size)

        val good = Http.json.decodeFromString<ValueTrapResponse>(
            """{"symbol":"W","verdict":"discount","confidence":"high","assessable":true,
                "green":["Free cash flow is rising"],"note":"n","below_line":true}""")
        assertEquals("discount", good.verdict)
        assertEquals(true, good.belowLine)
    }

    @Test
    fun `a split-corrupted share count arrives as a named gap, not as evidence`() {
        val v = Http.json.decodeFromString<ValueTrapResponse>(
            """{"symbol":"SMCI","verdict":"unclear","assessable":true,
                "red":["No insider buying in 12 months"],
                "missing":["share count (looks like a stock split)"],"note":"n"}""")
        assertTrue(v.missing.any { it.contains("split") })
        assertFalse("a split must never appear as dilution evidence",
            v.red.any { it.contains("dilution") })
    }
}
