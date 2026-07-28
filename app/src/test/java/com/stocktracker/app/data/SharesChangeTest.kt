package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.QualityResponse
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A stock split is not dilution.
 *
 * `shares_change_pct` divides RAW reported share counts, so SMCI's 10-for-1 split arrived as
 * "+1074.4%" and rendered as the strongest possible negative — for a corporate action that dilutes
 * nobody. The backend now flags the number unreliable; if the app ignores that flag it prints the
 * opposite of the truth on a card people read before buying.
 */
class SharesChangeTest {

    @Test
    fun `an unreliable share change is flagged so the card can suppress it`() {
        val json = """
            {"symbol":"SMCI","shares_change_pct":1074.4,"shares_change_reliable":false,
             "shares_change_note":"looks like a stock split"}
        """.trimIndent()
        val q = Http.json.decodeFromString<QualityResponse>(json)
        assertEquals(1074.4, q.sharesChangePct!!, 0.01)
        assertFalse("the card must be able to tell this is not dilution", q.sharesChangeReliable!!)
    }

    @Test
    fun `an ordinary buyback stays reliable and keeps its number`() {
        val q = Http.json.decodeFromString<QualityResponse>(
            """{"symbol":"MSFT","shares_change_pct":-1.9,"shares_change_reliable":true}""")
        assertEquals(-1.9, q.sharesChangePct!!, 0.01)
        assertEquals(true, q.sharesChangeReliable)
    }

    @Test
    fun `an older backend without the flag does not break decoding`() {
        // The flag is absent from responses predating the fix; null must not be read as "false".
        val q = Http.json.decodeFromString<QualityResponse>(
            """{"symbol":"AAPL","shares_change_pct":-2.5}""")
        assertNull(q.sharesChangeReliable)
    }
}
