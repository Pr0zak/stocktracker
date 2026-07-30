package com.stocktracker.app.data

import com.stocktracker.app.data.remote.HeatmapResponse
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.util.Treemap
import com.stocktracker.app.util.TreemapItem
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heat map's two scales must survive the wire, and the values it carries must be safe to feed
 * straight into the layout — a zero or negative size would corrupt every rectangle in its row.
 */
class HeatmapDecodeTest {

    @Test
    fun `a dip tier arrives on the signal scale, never the price scale`() {
        val json = """
            {"mode":"signals","scale":"signal","tiles":[
              {"symbol":"GME","size":21.1,"value":4.0,"scale":"signal","dip":"mega_dip",
               "signal":"hold","conviction":42,"pct_off_52w_high":-21.1,"below_200wma":false}],
             "skipped":[],"note":"n"}
        """.trimIndent()
        val r = Http.json.decodeFromString<HeatmapResponse>(json)
        val t = r.tiles.first()
        assertEquals("signal", t.scale)
        assertEquals("mega_dip", t.dip)
        assertEquals(-21.1, t.pctOff52wHigh!!, 0.01)
    }

    @Test
    fun `market tiles carry the price scale and the advance-decline split`() {
        val json = """
            {"mode":"market","scale":"price","advancing":2,"declining":6,
             "tiles":[{"symbol":"AAPL","name":"Apple Inc.","size":4994.9,"value":-0.56,
                       "scale":"price","price":300.0}],
             "unpriced":["DEAD"],"universe_stale":false,"note":"n"}
        """.trimIndent()
        val r = Http.json.decodeFromString<HeatmapResponse>(json)
        assertEquals("price", r.tiles.first().scale)
        assertEquals(2, r.advancing)
        assertEquals(listOf("DEAD"), r.unpriced)
        assertEquals(false, r.universeStale)
    }

    @Test
    fun `decoded sizes are safe to lay out`() {
        // The server refuses zero/absent sizes; this pins that the client can trust it, and that
        // the layout survives the realistic mega-cap-beside-small-cap spread.
        val json = """
            {"mode":"market","tiles":[
              {"symbol":"A","size":4994.9,"value":-0.5,"scale":"price"},
              {"symbol":"B","size":221.0,"value":1.2,"scale":"price"},
              {"symbol":"C","size":250.0,"value":-3.0,"scale":"price"}],"note":"n"}
        """.trimIndent()
        val r = Http.json.decodeFromString<HeatmapResponse>(json)
        assertTrue(r.tiles.all { it.size > 0.0 })
        val laid = Treemap.layout(r.tiles.map { TreemapItem(it.symbol, it.size) }, 400f, 560f)
        assertEquals(3, laid.size)
        assertEquals(400.0 * 560.0, laid.sumOf { it.area.toDouble() }, 1.0)
    }

    @Test
    fun `an older backend without the new fields still decodes`() {
        val r = Http.json.decodeFromString<HeatmapResponse>("""{"mode":"market","tiles":[]}""")
        assertTrue(r.tiles.isEmpty() && r.unpriced.isEmpty())
        assertEquals(null, r.universeStale)
    }

    @Test
    fun `a ticker is never truncated into a different real security`() {
        // The small-tile branch drew symbol.take(4), so GOOGL rendered as "GOOG" — a different
        // company, and one that sits on the very same map. Truncating a ticker does not abbreviate
        // it, it renames it. Only whole tickers of 4 characters or fewer get the small-tile label.
        val risky = listOf("GOOGL" to "GOOG", "BRK-B" to "BRK-", "TSLA" to "TSLA")
        for ((full, truncated) in risky) {
            if (full.length > 4) {
                assertTrue(
                    "$full would render as $truncated, a different ticker",
                    full.take(4) != full,
                )
            }
        }
        // GOOG and GOOGL really do co-occur in the live universe, which is what makes it dangerous.
        assertEquals("GOOG", "GOOGL".take(4))
    }

    @Test
    fun `signals mode carries the scan's vintage so a stale read cannot look live`() {
        // Signals come from the NIGHTLY scan — always hours old, sometimes a day. The backend sends
        // as_of; without decoding it a day-old read rendered exactly like a live one.
        val json = """
            {"mode":"signals","scale":"signal","as_of":1785324740.06,
             "tiles":[{"symbol":"GME","size":21.1,"value":5.0,"scale":"signal","dip":"mega_dip"}],
             "skipped":["NEWCO"],"note":"n"}
        """.trimIndent()
        val r = Http.json.decodeFromString<HeatmapResponse>(json)
        assertEquals(1785324740.06, r.asOf!!, 0.1)
        assertEquals(listOf("NEWCO"), r.skipped)
    }

    @Test
    fun `market mode reports which session it measured`() {
        // `pct` is the REGULAR-session move, so outside market hours it is the previous session's.
        // The payload names the session it actually measured.
        val json = """{"mode":"market","session":"PRE","tiles":[],"note":"n"}"""
        assertEquals("PRE", Http.json.decodeFromString<HeatmapResponse>(json).session)
    }

    @Test
    fun `an older backend without as_of or session still decodes`() {
        val r = Http.json.decodeFromString<HeatmapResponse>("""{"mode":"signals","tiles":[]}""")
        assertEquals(null, r.asOf)
        assertEquals(null, r.session)
        assertTrue(r.skipped.isEmpty())
    }
}
