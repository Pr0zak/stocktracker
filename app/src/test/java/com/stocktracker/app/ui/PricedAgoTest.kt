package com.stocktracker.app.ui

import com.stocktracker.app.ui.detail.pricedAgo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Options cards carry `as_of` and rendered none of it. The only staleness hint was the server's
 * `quote_delayed` flag, which says "market closed" and nothing about a card generated an hour ago
 * mid-session — these are strikes and premiums copied onto a broker ticket.
 */
class PricedAgoTest {

    private fun isoMinutesAgo(m: Long): String =
        java.time.Instant.ofEpochMilli(System.currentTimeMillis() - m * 60_000).toString()

    @Test
    fun `a fresh card reads as just now`() {
        assertEquals("priced just now", pricedAgo(isoMinutesAgo(0)))
    }

    @Test
    fun `minutes, hours and days each get their own unit`() {
        assertEquals("priced 20 min ago", pricedAgo(isoMinutesAgo(20)))
        assertEquals("priced 3h ago", pricedAgo(isoMinutesAgo(3 * 60)))
        assertEquals("priced 2d ago", pricedAgo(isoMinutesAgo(2 * 24 * 60)))
    }

    @Test
    fun `an epoch-seconds timestamp is also accepted`() {
        val secs = (System.currentTimeMillis() / 1000.0) - 1800
        assertTrue(pricedAgo(secs.toString())!!.contains("30 min"))
    }

    @Test
    fun `absent or unparseable timestamps say nothing rather than guess`() {
        assertNull(pricedAgo(null))
        assertNull(pricedAgo(""))
        assertNull(pricedAgo("not a time"))
    }
}
