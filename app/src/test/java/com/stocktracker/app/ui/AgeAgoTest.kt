package com.stocktracker.app.ui

import com.stocktracker.app.ui.detail.ageAgo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The bare age behind every "priced 12 min ago" line, and now behind the My Calls staleness note.
 *
 * It exists as its own function because a failed option re-price has to say how old the figure it
 * is still showing is, in a sentence that does not begin with the word "priced". The thing being
 * pinned here is the null: an unparseable or missing timestamp must produce NO age rather than a
 * plausible one, because the caller's fallback sentence ("age unknown") is honest and a fabricated
 * "just now" is not.
 */
class AgeAgoTest {

    private fun minutesAgo(m: Long): String =
        Instant.ofEpochMilli(System.currentTimeMillis() - m * 60_000).toString()

    @Test
    fun `recent reads as just now, and carries no verb`() {
        assertEquals("just now", ageAgo(minutesAgo(0)))
        assertEquals("just now", ageAgo(minutesAgo(1)))
    }

    @Test
    fun `minutes, hours and days each get their own unit`() {
        assertEquals("20 min ago", ageAgo(minutesAgo(20)))
        assertEquals("59 min ago", ageAgo(minutesAgo(59)))
        assertEquals("1h ago", ageAgo(minutesAgo(60)))
        assertEquals("3h ago", ageAgo(minutesAgo(3 * 60)))
        assertEquals("2d ago", ageAgo(minutesAgo(2 * 24 * 60)))
    }

    @Test
    fun `epoch seconds are accepted as well as ISO`() {
        val secs = (System.currentTimeMillis() - 30 * 60_000) / 1000.0
        assertEquals("30 min ago", ageAgo(secs.toString()))
    }

    @Test
    fun `no stamp means no age, never a reassuring one`() {
        assertNull(ageAgo(null))
        assertNull(ageAgo(""))
        assertNull(ageAgo("   "))
        assertNull(ageAgo("not a time"))
    }

    @Test
    fun `a stamp in the future is refused rather than rendered as negative`() {
        val ahead = Instant.ofEpochMilli(System.currentTimeMillis() + 10 * 60_000).toString()
        assertNull(ageAgo(ahead))
    }
}
