package com.stocktracker.app.ui

import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.ui.journal.asOfIndex
import com.stocktracker.app.ui.journal.barsThrough
import com.stocktracker.app.ui.journal.stepLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The journal's as-of chart: what you were looking at on the day you gave a verdict.
 *
 * The leak is the whole risk. A chart claiming to show a past day while its axis, its high/low
 * markers or its indicator panes were scaled over bars that had not happened yet would be a
 * hindsight machine wearing the label of a record — so the series is truncated rather than the
 * renderer being asked to stop early, and these pin that the truncation is exact.
 */
class AsOfChartTest {

    private companion object {
        val NY: ZoneId = ZoneId.of("America/New_York")

        /** One bar per weekday from [start], stamped at the US session close. */
        fun bars(start: String, n: Int): List<PricePoint> {
            var d = LocalDate.parse(start)
            val out = ArrayList<PricePoint>(n)
            var i = 0
            while (out.size < n) {
                if (d.dayOfWeek.value <= 5) {
                    val ms = d.atTime(16, 0).atZone(NY).toInstant().toEpochMilli()
                    out.add(PricePoint(ms, 100.0 + i))
                    i++
                }
                d = d.plusDays(1)
            }
            return out
        }
    }

    // Monday 2026-06-01 onward, 40 weekday bars.
    private val series = bars("2026-06-01", 40)

    @Test fun `an exact trading day resolves to its own bar`() {
        val i = asOfIndex(series, "2026-06-01")!!
        assertEquals(0, i)
        assertEquals(100.0, series[i].price, 0.0)
    }

    @Test fun `a weekend resolves back to the previous trading day`() {
        // 2026-06-06 is a Saturday; the last chart that existed was Friday the 5th.
        val sat = asOfIndex(series, "2026-06-06")!!
        val fri = asOfIndex(series, "2026-06-05")!!
        assertEquals(fri, sat)
    }

    @Test fun `a date after the last bar resolves to the last bar`() {
        assertEquals(series.lastIndex, asOfIndex(series, "2030-01-01"))
    }

    /**
     * The case that must not silently truncate to nothing. "We only have history back to June" is a
     * different statement from "nothing had traded", and an empty chart says the second.
     */
    @Test fun `a verdict predating our history is unanswerable, not empty`() {
        assertNull(asOfIndex(series, "2020-01-01"))
    }

    @Test fun `an unparseable date is unanswerable`() {
        assertNull(asOfIndex(series, "not-a-date"))
        assertNull(asOfIndex(series, ""))
    }

    @Test fun `an empty series is unanswerable`() {
        assertNull(asOfIndex(emptyList(), "2026-06-01"))
    }

    // --- truncation ---------------------------------------------------------------------------

    @Test fun `the drawn series contains no bar after the cursor`() {
        val i = asOfIndex(series, "2026-06-15")!!
        val drawn = barsThrough(series, i)
        assertEquals(i + 1, drawn.size)
        assertEquals(series[i].epochMs, drawn.last().epochMs)
        assertTrue("no future bar may be in scope at all", drawn.none { it.epochMs > series[i].epochMs })
    }

    @Test fun `truncation is inclusive of the cursor bar itself`() {
        assertEquals(1, barsThrough(series, 0).size)
        assertEquals(series.size, barsThrough(series, series.lastIndex).size)
    }

    @Test fun `a nonsensical cursor still yields a drawable series rather than crashing`() {
        assertEquals(1, barsThrough(series, -5).size)
        assertEquals(series.size, barsThrough(series, 9_999).size)
        assertTrue(barsThrough(emptyList(), 3).isEmpty())
    }

    // --- the stepper --------------------------------------------------------------------------

    @Test fun `stepping forward is capped so it cannot become the ordinary chart`() {
        val start = 0
        val limit = stepLimit(series, start, maxDays = 10)
        assertEquals(10, limit)
        assertTrue("must not walk to today", limit < series.lastIndex)
    }

    @Test fun `the cap never runs past the end of the data`() {
        val nearEnd = series.lastIndex - 2
        assertEquals(series.lastIndex, stepLimit(series, nearEnd, maxDays = 60))
    }

    @Test fun `the cap never runs backwards`() {
        assertEquals(series.lastIndex, stepLimit(series, series.lastIndex, maxDays = 60))
        assertTrue(stepLimit(series, 5, maxDays = 0) >= 5)
    }
}
