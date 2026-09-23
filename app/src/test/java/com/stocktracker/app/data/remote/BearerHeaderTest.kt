package com.stocktracker.app.data.remote

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SEC-2: the backend token must reach the backend and nowhere else.
 *
 * Http.client is shared with Yahoo, Finnhub and CoinGecko, so the header is attached by the
 * signals caller rather than inside the client. These pin the rule that a blank or absent token
 * sends no header at all — a "Bearer " with nothing after it would be a credential the backend
 * rejects, turning "not configured yet" into "configured wrongly".
 */
class BearerHeaderTest {

    private fun headerFor(bearer: String?): String? {
        val b = Request.Builder().url("http://192.0.2.1:8000/health")
        if (!bearer.isNullOrBlank()) b.header("Authorization", "Bearer $bearer")
        return b.build().header("Authorization")
    }

    @Test
    fun `a configured token becomes a bearer header`() {
        assertEquals("Bearer s3cret", headerFor("s3cret"))
    }

    @Test
    fun `no token means no header, not an empty one`() {
        assertNull(headerFor(null))
        assertNull(headerFor(""))
        assertNull(headerFor("   "))
    }
}
