package com.stocktracker.app.ui.ask

import com.stocktracker.app.data.remote.AskAsOf
import com.stocktracker.app.data.remote.AskIdea
import com.stocktracker.app.data.remote.AskResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskLabelsTest {
    @Test fun `entry zone needs both ends`() {
        assertNull(entryZone(AskIdea("QQQM", entryLow = 250.0)))
        assertEquals("Buy $248.00–$252.00", entryZone(AskIdea("QQQM", entryLow = 248.0, entryHigh = 252.0)))
    }

    @Test fun `as-of line names what was missing and removed`() {
        val a = AskResponse(
            asOf = AskAsOf(session = "REGULAR", stockScanNight = "20261030"),
            gatePassed = false, missing = listOf("macro"), dropped = listOf("TSLA"),
        )
        assertEquals(
            "Market regular · stock scan 20261030 · market gate closed · missing: macro · removed unpriced: TSLA",
            asOfLine(a),
        )
        assertNull(asOfLine(AskResponse()))
    }

    @Test fun `unknown gate is not reported as closed`() {
        assertNull(asOfLine(AskResponse(gatePassed = null)))
    }
}
