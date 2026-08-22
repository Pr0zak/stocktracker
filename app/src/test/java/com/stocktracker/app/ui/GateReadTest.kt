package com.stocktracker.app.ui

import com.stocktracker.app.data.remote.GateLeg
import com.stocktracker.app.data.remote.GateResponse
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.ui.watchlist.GateRead
import com.stocktracker.app.ui.watchlist.GateVerdict
import com.stocktracker.app.ui.watchlist.LegMark
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SWT-13 — the gate card's verdict, which is a THREE-VALUED fact rendered on a screen that has
 * historically had two colours for it.
 *
 * The expensive mistake here has a direction: `passed == null` means a leg could not be MEASURED and
 * nothing failed, and a card that paints that with the shut treatment asserts a bearish market on
 * the strength of a failed fetch. So the tests below care less about the open case than about
 * keeping null distinguishable from false, per leg as well as overall.
 */
class GateReadTest {

    private fun leg(key: String, name: String, ok: Boolean?, v: Double? = null, t: Double? = null) =
        GateLeg(name = name, key = key, ok = ok, value = v, threshold = t)

    @Test
    fun `the three passed states are three different verdicts`() {
        val open = GateRead.summary(GateResponse(passed = true, available = true))!!
        val shut = GateRead.summary(GateResponse(passed = false, available = true))!!
        val unread = GateRead.summary(GateResponse(passed = null, available = true))!!

        assertEquals(GateVerdict.OPEN, open.verdict)
        assertEquals(GateVerdict.SHUT, shut.verdict)
        assertEquals(GateVerdict.UNMEASURED, unread.verdict)

        // Not merely different enums — the words on screen have to differ too, or the card renders
        // the same sentence for two opposite situations.
        assertNotEquals(shut.headline, unread.headline)
        assertNotEquals(shut.chip, unread.chip)
        assertNotEquals(open.headline, unread.headline)
    }

    @Test
    fun `an unmeasured gate is not a shut one`() {
        val unread = GateRead.summary(
            GateResponse(
                passed = null,
                available = true,
                unmeasured = listOf("breadth"),
                legs = listOf(leg("breadth", "Breadth > 55%", null)),
            ),
        )!!
        assertEquals(GateVerdict.UNMEASURED, unread.verdict)
        // It must SAY that nothing failed. "Couldn't be measured" alone still reads as trouble to
        // someone skimming a card whose other state is a red "shut".
        assertTrue(unread.detail!!.contains("Nothing failed"))
        assertFalse(
            "an unmeasured gate described itself as failing",
            unread.detail!!.lowercase().contains("failing:"),
        )
        // And it names WHAT went unread, so the reader can go and check that one thing.
        assertTrue(unread.detail!!.contains("Breadth > 55%"))
    }

    @Test
    fun `a shut gate names the failing legs`() {
        val shut = GateRead.summary(
            GateResponse(
                passed = false,
                available = true,
                failing = listOf("spy_above_ema50", "vix_below_20"),
                legs = listOf(
                    leg("spy_above_ema50", "SPY > 50-EMA", false),
                    leg("vix_below_20", "VIX < 20", false),
                    leg("breadth", "Breadth > 55%", true),
                ),
            ),
        )!!
        assertEquals(GateVerdict.SHUT, shut.verdict)
        assertTrue(shut.detail!!.contains("SPY > 50-EMA"))
        assertTrue(shut.detail!!.contains("VIX < 20"))
        assertFalse("a passing leg was named as failing", shut.detail!!.contains("Breadth"))
    }

    @Test
    fun `a shut gate that names nothing says so rather than implying everything`() {
        // `failing` null with no leg carrying ok == false: an older history row. The card may not
        // invent the culprit, and it may not pretend the list was empty because nothing failed.
        val shut = GateRead.summary(GateResponse(passed = false, available = true))!!
        assertTrue(shut.detail!!.contains("didn't name which"))
    }

    @Test
    fun `a shut gate with no failing list falls back to the legs themselves`() {
        val shut = GateRead.summary(
            GateResponse(
                passed = false,
                available = true,
                failing = null,
                legs = listOf(leg("vix_below_20", "VIX < 20", false), leg("breadth", "Breadth > 55%", true)),
            ),
        )!!
        assertTrue(shut.detail!!.contains("VIX < 20"))
        assertFalse(shut.detail!!.contains("Breadth"))
    }

    @Test
    fun `nothing measurable at all is its own state, not a shut gate`() {
        val none = GateRead.summary(GateResponse(available = false))!!
        assertEquals(GateVerdict.UNAVAILABLE, none.verdict)
        assertNotEquals(GateVerdict.SHUT, none.verdict)
        assertTrue(none.detail!!.contains("not a shut gate"))
        // And it is not the same state as "one leg went unread", so it doesn't borrow its words.
        val unread = GateRead.summary(GateResponse(passed = null, available = true))!!
        assertNotEquals(unread.headline, none.headline)
        assertNotEquals(unread.chip, none.chip)
    }

    @Test
    fun `no response at all renders no verdict`() {
        // The card falls back to its loading / error state. Null here is never "fine".
        assertNull(GateRead.summary(null))
    }

    @Test
    fun `a leg with a null ok is a dash, never a cross`() {
        assertEquals(LegMark.PASS, GateRead.mark(true))
        assertEquals(LegMark.FAIL, GateRead.mark(false))
        assertEquals(LegMark.UNKNOWN, GateRead.mark(null))
        assertNotEquals(
            "an unmeasured leg was marked the same as a failing one",
            GateRead.mark(false),
            GateRead.mark(null),
        )
    }

    @Test
    fun `leg numbers are printed only when they arrived`() {
        assertEquals("54.1 vs 55", GateRead.legValue(leg("b", "Breadth", false, 54.1, 55.0)))
        assertEquals("54.1", GateRead.legValue(leg("b", "Breadth", false, 54.1, null)))
        assertEquals("needs 55", GateRead.legValue(leg("b", "Breadth", null, null, 55.0)))
        // The whole point: an unmeasured leg prints no number rather than a 0.
        assertNull(GateRead.legValue(leg("b", "Breadth", null, null, null)))
    }

    @Test
    fun `an absent market score is a dash, not a zero`() {
        assertEquals("75.1", GateRead.scoreText(GateResponse(available = true, marketScore = 75.1)))
        assertNull(GateRead.scoreText(GateResponse(available = true, marketScore = null)))
        assertNull(GateRead.scoreText(GateResponse(available = true, marketScore = Double.NaN)))
        assertNull(GateRead.scoreText(null))
    }

    @Test
    fun `an explicit null passed survives the decoder`() {
        // Http.json sets coerceInputValues = true, which turns an explicit null into the declared
        // default for a NON-nullable field. This is the wire-level proof that `passed` isn't one:
        // a server saying "I couldn't decide" must not arrive as "I decided no".
        val json = """
            {"passed": null, "available": true, "market_score": null,
             "legs": [{"name": "Breadth > 55%", "key": "breadth", "ok": null}],
             "unmeasured": ["breadth"], "note": "breadth unavailable"}
        """.trimIndent()
        val resp = Http.json.decodeFromString<GateResponse>(json)
        assertNull("an explicit null passed decoded to a Boolean", resp.passed)
        assertNull(resp.legs.first().ok)
        assertEquals(GateVerdict.UNMEASURED, GateRead.summary(resp)!!.verdict)
    }

    @Test
    fun `the live shape reads as an open gate`() {
        // The real payload the service was returning when this card was built, kept verbatim: five
        // legs, every one measured, score 75.1, breadth over 3101 names. If the wire shape drifts,
        // this is where it shows up rather than on the dashboard.
        val json = """
            {"passed":true,"available":true,"as_of":"20260821","evaluated_at":1787408482.5,
             "market_score":75.1,"legs":[
              {"name":"SPY > 50-EMA","key":"spy_above_ema50","ok":true,"value":765.72,"threshold":752.63,
               "note":"SPY 765.72 is +1.74% vs its 50-EMA"},
              {"name":"QQQ > 50-EMA","key":"qqq_above_ema50","ok":true,"value":713.44,"threshold":708.64,
               "note":"QQQ 713.44 is +0.68% vs its 50-EMA"},
              {"name":"Breadth > 55%","key":"breadth_55","ok":true,"value":58.4,"threshold":55.0,
               "note":"58.4% of 3101 scanned names are above their 50-SMA (scan is 14h old)"},
              {"name":"VIX < 20","key":"vix_under_20","ok":true,"value":15.13,"threshold":20.0,
               "note":"VIX is 15.13"},
              {"name":"SPY 20-day momentum > 0","key":"spy_mom_20d","ok":true,"value":3.63,"threshold":0.0,
               "note":"SPY is +3.63% over 20 sessions"}],
             "failing":[],"unmeasured":[],"note":"Market gate open: all five legs pass.",
             "cached":false,"cached_age_seconds":0}
        """.trimIndent()
        val resp = Http.json.decodeFromString<GateResponse>(json)
        val s = GateRead.summary(resp)!!
        assertEquals(GateVerdict.OPEN, s.verdict)
        assertEquals("75.1", GateRead.scoreText(resp))
        assertEquals("All 5 conditions hold.", s.detail)
        assertEquals("58.4 vs 55", GateRead.legValue(resp.legs[2]))
        assertTrue(resp.legs.all { GateRead.mark(it.ok) == LegMark.PASS })
        // Computed fresh, so there is no age line to print — and a zero age is not "cached".
        assertNull(GateRead.cachedNote(resp))
    }

    @Test
    fun `a cached reading says how old it is, and an unknown age is not a fresh one`() {
        assertNull(GateRead.cachedNote(GateResponse(available = true, cached = false, cachedAgeSeconds = 400)))
        assertEquals("cached · 6m old", GateRead.cachedNote(GateResponse(available = true, cached = true, cachedAgeSeconds = 400)))
        assertEquals("cached", GateRead.cachedNote(GateResponse(available = true, cached = true, cachedAgeSeconds = null)))
    }
}
