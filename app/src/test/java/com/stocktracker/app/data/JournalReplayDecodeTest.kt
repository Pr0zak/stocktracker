package com.stocktracker.app.data

import com.stocktracker.app.data.model.JournalReplay
import com.stocktracker.app.data.model.VerdictJournalEntry
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.PlanReplayRequestBody
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `POST /journal/replay` decoded honestly (SWT-8).
 *
 * The route emits EVERY key on EVERY path, null where the value does not apply — the chase.annotate
 * shape. That is only a defence if the client models those nulls as nulls: `Http.json` sets
 * `coerceInputValues = true`, so a non-nullable `Double = 0.0` swallows both an omitted key and an
 * explicit `null` into a confident zero. On this payload the zeroes are all claims. `r: 0` says the
 * plan exited exactly where it entered; `entry_price: 0` says it filled for nothing; `outcome: ""`
 * says we know how it ended. Every one of them must stay distinguishable from "the server did not
 * say", because the whole feature exists to stop invented numbers standing next to real ones.
 */
class JournalReplayDecodeTest {

    @Test
    fun `a resolved replay decodes with every field intact`() {
        val json = """
            {"symbol":"AAPL","as_of":"20260801","source":"yahoo","bars_from":"20260804",
             "bars_to":"20260821","outcome":"target","reason":null,"refused":false,"ambiguous":false,
             "entry_price":102.0,"entry_date":"20260804","exit_price":130.0,"exit_date":"20260818",
             "bars_held":11,"r":2.75,"return_pct":27.451,"mark_price":null,"mark_date":null,
             "horizon_days":40,"fill_window_days":5,"bars_seen":13,"bars_skipped":0,
             "note":"What the plan as written would have done — the MECHANICAL leg."}
        """.trimIndent()
        val r = Http.json.decodeFromString<JournalReplay>(json)
        assertEquals("target", r.outcome)
        assertEquals(102.0, r.entryPrice!!, 1e-9)
        assertEquals("20260804", r.entryDate)
        assertEquals(130.0, r.exitPrice!!, 1e-9)
        assertEquals(11, r.barsHeld)
        assertEquals(2.75, r.r!!, 1e-9)
        assertEquals(40, r.horizonDays)
        assertEquals(5, r.fillWindowDays)
        assertEquals("yahoo", r.source)
        assertTrue(r.isResolved)
        assertEquals(2.75, r.scoredR!!, 1e-9)
        // The mark belongs to open plans only, and it must not have leaked into an exit field.
        assertNull(r.markPrice)
    }

    @Test
    fun `an explicit null r decodes as unknown and not as a scratch trade`() {
        // A plan with no stop resolves without an R. 0.0 here would claim it made exactly what it
        // risked — a real, different outcome — and would then average into the mechanical expectancy.
        val json = """{"outcome":"time","refused":false,"ambiguous":false,"r":null,
                       "exit_price":104.0,"exit_date":"20260901","return_pct":2.0}"""
        val r = Http.json.decodeFromString<JournalReplay>(json)
        assertTrue(r.isResolved)
        assertNull(r.r)
        assertNull(r.scoredR)
    }

    @Test
    fun `the nothing-decided-yet payload decodes as pending rather than as a failure`() {
        val json = """
            {"symbol":"AAPL","as_of":"20260821","source":"yahoo","bars_from":null,"bars_to":null,
             "outcome":null,"reason":"no session has traded since 20260821 — nothing to replay yet",
             "refused":false,"ambiguous":false,"entry_price":null,"entry_date":null,"exit_price":null,
             "exit_date":null,"bars_held":null,"r":null,"return_pct":null,"mark_price":null,
             "mark_date":null,"horizon_days":40,"fill_window_days":5,"bars_seen":0,"bars_skipped":0}
        """.trimIndent()
        val r = Http.json.decodeFromString<JournalReplay>(json)
        assertNull(r.outcome)
        assertFalse(r.refused)
        assertTrue(r.isPending)
        assertNull(r.scoredR)
        assertEquals("Not replayed yet", JournalReplay.describe(r))
        assertNotNull(r.reason) // the server's own sentence survives for the row to show
    }

    @Test
    fun `a still-running plan keeps its mark out of the exit fields`() {
        val json = """{"outcome":"open","refused":false,"ambiguous":false,"bars_held":6,
                       "exit_price":null,"exit_date":null,"mark_price":118.4,"mark_date":"20260821",
                       "r":null,"reason":"still open — 6 of 40 session(s) elapsed"}"""
        val r = Http.json.decodeFromString<JournalReplay>(json)
        assertEquals(118.4, r.markPrice!!, 1e-9)
        assertNull(r.exitPrice)
        assertFalse(r.isResolved)
        assertNull(r.scoredR)
    }

    @Test
    fun `the ambiguous flag survives the wire`() {
        val json = """{"outcome":"stop","refused":false,"ambiguous":true,"r":-1.0,
                       "exit_price":90.0,"exit_date":"20260812"}"""
        val r = Http.json.decodeFromString<JournalReplay>(json)
        assertTrue(r.ambiguous)
        assertEquals(-1.0, r.scoredR!!, 1e-9)
    }

    @Test
    fun `a replay stored before a field existed loads as absent rather than as zero`() {
        // The entry's own persisted JSON, from an older build that only wrote what it had.
        val json = """{"outcome":"target","r":1.5}"""
        val r = Http.json.decodeFromString<JournalReplay>(json)
        assertNull(r.entryPrice)
        assertNull(r.barsHeld)
        assertNull(r.replayedAtMs)
        assertFalse(r.refused)
        assertFalse(r.ambiguous)
    }

    @Test
    fun `an entry round trips through the store's codec with its replay attached`() {
        val entry = VerdictJournalEntry(
            symbol = "AAPL",
            verdictDateIso = "2026-08-01",
            replay = JournalReplay(outcome = "stop", r = -1.0, ambiguous = true, replayedAtMs = 42L),
        )
        val back = Http.json.decodeFromString<VerdictJournalEntry>(Http.json.encodeToString(entry))
        assertEquals("stop", back.replay!!.outcome)
        assertEquals(-1.0, back.mechanicalR!!, 1e-9)
        assertTrue(back.replay!!.ambiguous)
        assertEquals(42L, back.replay!!.replayedAtMs)
    }

    @Test
    fun `an entry written before replays existed loads with no replay rather than an empty one`() {
        val json = """{"id":"x","symbol":"AAPL","verdictDateIso":"2026-08-01","taken":"TAKEN"}"""
        val entry = Http.json.decodeFromString<VerdictJournalEntry>(json)
        // Null, not JournalReplay() — an empty replay would claim the plan was asked about and had
        // nothing to say, when in truth it was never asked.
        assertNull(entry.replay)
        assertNull(entry.mechanicalR)
    }

    @Test
    fun `the request body always states the plan it is asking about`() {
        // `Http.json` leaves encodeDefaults at false, so a field equal to its class default is dropped
        // — the defect that made "run a tick now" serialize to `{}`. This body has no defaults, so a
        // level that IS present cannot go missing on the way out.
        val body = Http.json.encodeToString(
            PlanReplayRequestBody(
                symbol = "AAPL", date = "2026-08-01",
                entryLow = 98.0, entryHigh = 102.0, stop = 90.0, target = 130.0,
            ),
        )
        assertTrue(body.contains("\"symbol\":\"AAPL\""))
        assertTrue(body.contains("\"date\":\"2026-08-01\""))
        assertTrue(body.contains("\"entry_low\":98.0"))
        assertTrue(body.contains("\"entry_high\":102.0"))
        assertTrue(body.contains("\"stop\":90.0"))
        assertTrue(body.contains("\"target\":130.0"))
    }

    @Test
    fun `a plan with no levels still sends its symbol and date`() {
        val body = Http.json.encodeToString(
            PlanReplayRequestBody(symbol = "AAPL", date = "2026-08-01",
                entryLow = null, entryHigh = null, stop = null, target = null),
        )
        assertTrue(body.contains("\"symbol\":\"AAPL\""))
        // The server refuses this with a 422 ("the plan names no entry level"), which is the honest
        // answer — better than a client that quietly invents a zone to have something to send.
        assertTrue(body.contains("\"entry_high\":null"))
    }
}
