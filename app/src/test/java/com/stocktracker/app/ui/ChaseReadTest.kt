package com.stocktracker.app.ui

import com.stocktracker.app.data.remote.EntryPlan
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.PlanResponse
import com.stocktracker.app.data.remote.RecommendationsResponse
import com.stocktracker.app.ui.detail.ChaseRead
import com.stocktracker.app.ui.detail.ChaseState
import com.stocktracker.app.ui.detail.ChaseTone
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SWT-3 — the entry-plan card's chase line.
 *
 * Two failure modes, opposite in direction and both expensive: staying SILENT when the user is about
 * to pay well over the plan's own entry zone, and manufacturing a reassuring "ok / 0%" when the read
 * could not be taken at all. The second is the one this codebase keeps re-committing — it is how
 * "Stop $0 · target $0" reached a money decision — so absence is tested harder than presence.
 */
class ChaseReadTest {

    @Test
    fun `an absent read renders nothing at all`() {
        // No zone from the analyst, or no quote from the feed: pct and status arrive null together.
        assertNull(ChaseRead.banner(null, null, null, null))
        assertNull("an absent read produced a line with a price on it", ChaseRead.banner(null, null, "$74.02", null))
    }

    @Test
    fun `an absent read never reaches the card in the first place`() {
        val plan = EntryPlan(symbol = "AAPL", action = "buy_now")
        assertNull(ChaseState.from(PlanResponse(symbol = "AAPL", plan = plan)))
        assertNull("a null response produced a chase state", ChaseState.from(null))
        // A price alone is not a read — the server sends chase_price with a null status when the
        // zone is missing, and a bare quote says nothing about chasing.
        assertNull(ChaseState.from(PlanResponse(symbol = "AAPL", plan = plan, chasePrice = 74.02)))
    }

    @Test
    fun `chasing is stated prominently and carries the number`() {
        val b = ChaseRead.banner(ChaseRead.TOO_DEEP, 4.64, "$74.02", null)!!
        assertEquals(ChaseTone.ALARM, b.tone)
        assertTrue("the percentage the whole feature exists to show was missing", b.headline.contains("4.6%"))
        assertTrue(b.headline.contains("above the entry zone"))
        assertTrue(b.detail!!.contains("$74.02"))
    }

    @Test
    fun `a status with no number still refuses to invent one`() {
        val b = ChaseRead.banner(ChaseRead.TOO_DEEP, null, null, null)!!
        assertEquals(ChaseTone.ALARM, b.tone)
        assertFalse("a percentage appeared out of a null", b.headline.any { it.isDigit() })
    }

    @Test
    fun `being inside or under the zone does not cry wolf`() {
        val inZone = ChaseRead.banner(ChaseRead.IN_ZONE, -1.2, "$69.10", null)!!
        assertEquals(ChaseTone.CALM, inZone.tone)
        assertEquals("In the entry zone", inZone.headline)

        val below = ChaseRead.banner(ChaseRead.BELOW_ZONE, -6.0, "$64.00", null)!!
        assertEquals(ChaseTone.CALM, below.tone)
        assertTrue(below.headline.contains("cheaper than planned"))
    }

    @Test
    fun `a hair over the zone is stated, not alarmed`() {
        // The server's ok band is quote noise, not permission to pay up. Worth printing; not worth a
        // banner, or the banner stops being read on the day it matters.
        val b = ChaseRead.banner(ChaseRead.OK, 0.8, "$70.30", null)!!
        assertEquals(ChaseTone.NEUTRAL, b.tone)
        assertTrue(b.headline.contains("0.8%"))
    }

    @Test
    fun `half a zone gives a distance and claims no verdict`() {
        // entry_high known, entry_low missing: "in the zone" and "under the zone" aren't separable,
        // so the server sends a percent with a null status.
        val b = ChaseRead.banner(null, -3.0, "$68.00", null)!!
        assertEquals(ChaseTone.NEUTRAL, b.tone)
        assertTrue(b.headline.contains("3.0%"))
        assertTrue(b.headline.contains("entry-zone top"))
        assertFalse("a verdict was implied where the server declined to give one", b.headline.contains("Chasing"))
    }

    @Test
    fun `sitting on the zone top is not printed as a rounded zero distance`() {
        val b = ChaseRead.banner(null, -0.02, null, null)!!
        assertEquals("At the top of the entry zone", b.headline)
    }

    @Test
    fun `a broken zone reports the defect instead of a price verdict`() {
        val warning = "entry zone is inverted (low 72 above high 70) — no chase read from it"
        val b = ChaseRead.banner(null, null, "$74.02", warning)!!
        assertEquals(ChaseTone.CAUTION, b.tone)
        assertEquals("No chase read", b.headline)
        assertEquals(warning, b.detail)
    }

    @Test
    fun `an unknown status from a newer server shows the number and no invented label`() {
        val b = ChaseRead.banner("some_future_status", 2.5, null, null)!!
        assertEquals(ChaseTone.NEUTRAL, b.tone)
        assertTrue(b.headline.contains("2.5%"))
        assertNull(ChaseRead.banner("some_future_status", null, null, null))
    }

    @Test
    fun `the chase fields survive the deserializer as nulls`() {
        // coerceInputValues turns an explicit null into the declared default for a NON-nullable
        // field. These are nullable precisely so "we looked and couldn't say" stays representable.
        val resp = Http.json.decodeFromString<PlanResponse>(
            """{"symbol":"AAPL","plan":{"symbol":"AAPL","action":"wait"},
                "chase_pct":null,"chase_status":null,"chase_warning":null,"chase_price":null}""",
        )
        assertNull("a null chase percent became a confident 0.0", resp.chasePct)
        assertNull(resp.chaseStatus)
        assertNull(ChaseState.from(resp))
    }

    @Test
    fun `a real chase read decodes and renders`() {
        val resp = Http.json.decodeFromString<PlanResponse>(
            """{"symbol":"AAPL","plan":{"symbol":"AAPL","action":"buy_now","entry_high":70.73},
                "chase_pct":4.65,"chase_status":"chase_too_deep","chase_warning":null,"chase_price":74.02}""",
        )
        val state = ChaseState.from(resp)!!
        assertEquals("chase_too_deep", state.status)
        assertEquals(4.65, state.pct!!, 1e-9)
        assertEquals(74.02, state.price!!, 1e-9)
        val b = ChaseRead.banner(state.status, state.pct, "$74.02", state.warning)
        assertNotNull(b)
        assertEquals(ChaseTone.ALARM, b!!.tone)
    }

    @Test
    fun `a backend older than SWT-3 sends no chase keys and the card stays silent`() {
        val resp = Http.json.decodeFromString<PlanResponse>(
            """{"symbol":"AAPL","plan":{"symbol":"AAPL","action":"buy_now"}}""",
        )
        assertNull(ChaseState.from(resp))
    }

    // ---------------------------------------------------------------- SWT-15: the Ideas screen

    @Test
    fun `an Ideas pick with no chase fields renders neither a line nor a zero`() {
        // This is the state TODAY: /recommendations is not annotated with the chase read yet, so
        // every pick arrives with all four fields absent. The card must show NOTHING — a "0.0%
        // above the entry zone" beside a suggested share count is worse than silence.
        val pick = EntryPlan(symbol = "PYPL", action = "buy_now", conviction = 72, entryHigh = 70.73)
        assertNull(ChaseState.fromPick(pick))
        assertNull(ChaseState.fromPick(null))
        // And nothing downstream manufactures one from the absence either.
        assertNull(ChaseRead.banner(pick.chaseStatus, pick.chasePct, "$74.02", pick.chaseWarning))
    }

    @Test
    fun `todays recommendations payload decodes with the chase fields null`() {
        val resp = Http.json.decodeFromString<RecommendationsResponse>(
            """{"model":"x","picks":[{"symbol":"PYPL","action":"buy_now","conviction":72}]}""",
        )
        val pick = resp.picks.single()
        assertNull("an absent chase percent became a confident 0.0", pick.chasePct)
        assertNull(pick.chaseStatus)
        assertNull(ChaseState.fromPick(pick))
    }

    @Test
    fun `an annotated pick renders through the same reader as the detail card`() {
        // When the backend half lands, the Ideas card lights up with no further app change — and it
        // says exactly what the detail screen says, because it is the same mapping.
        val resp = Http.json.decodeFromString<RecommendationsResponse>(
            """{"model":"x","picks":[{"symbol":"PYPL","action":"buy_now","conviction":72,
                "chase_pct":4.65,"chase_status":"chase_too_deep","chase_price":74.02}]}""",
        )
        val state = ChaseState.fromPick(resp.picks.single())!!
        assertEquals("chase_too_deep", state.status)
        assertEquals(4.65, state.pct!!, 1e-9)
        val fromPick = ChaseRead.banner(state.status, state.pct, "$74.02", state.warning)!!
        val fromPlan = ChaseRead.banner("chase_too_deep", 4.65, "$74.02", null)!!
        assertEquals("the two screens disagreed about the same fact", fromPlan, fromPick)
        assertEquals(ChaseTone.ALARM, fromPick.tone)
    }

    @Test
    fun `a null status with a null percent is silent on a pick too`() {
        val pick = EntryPlan(symbol = "PYPL", action = "buy_now", chasePrice = 74.02)
        // A quote alone is not a read: the server can price the check without being able to make it.
        assertNull(ChaseState.fromPick(pick))
    }
}
