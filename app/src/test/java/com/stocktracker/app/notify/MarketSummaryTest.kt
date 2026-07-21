package com.stocktracker.app.notify

import com.stocktracker.app.util.MarketPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSummaryTest {

    private val closeEt = 17 * 3600      // 5:00pm ET — inside the after-hours window
    private val afterEt = 20 * 3600 + 5  // 8:00:05pm ET — post session over

    private fun mover(sym: String, day: Double, ah: Double? = null) = Mover(sym, day, ah)

    // A typical watchlist: three up, two down, one flat.
    private val watchlist = listOf(
        mover("NVDA", 3.2),
        mover("MSFT", 2.1),
        mover("V", 1.4),
        mover("AAPL", -2.1),
        mover("PFE", -1.2),
        mover("KO", 0.01), // flat — omitted from the movers line
    )

    private fun close(
        phase: MarketPhase = MarketPhase.AFTER,
        movers: List<Mover> = watchlist,
        sent: Boolean = false,
    ) = MarketSummary.build(
        phase = phase,
        etSecondsOfDay = closeEt,
        isTradingDay = true,
        movers = movers,
        alreadySentClose = sent,
        alreadySentAfterHours = false,
    )

    // --- Close recap timing ---

    @Test fun `close recap fires in AFTER phase when not already sent`() {
        val s = close()
        assertNotNull(s)
        assertEquals(MoverSummary.Kind.CLOSE, s!!.kind)
    }

    @Test fun `close recap does not fire outside the after-hours phase`() {
        assertNull(close(phase = MarketPhase.REGULAR))
        assertNull(close(phase = MarketPhase.PRE))
        assertNull(close(phase = MarketPhase.CLOSED))
    }

    @Test fun `close dedup flag suppresses a second close recap`() {
        assertNull(close(sent = true))
    }

    // --- Close recap content / ranking ---

    @Test fun `close recap ranks top gainers and losers and formats both sides`() {
        val body = close()!!.body
        // Overview counts every positive day change: NVDA/MSFT/V + KO's +0.01 = 4 of 6.
        assertTrue(body.contains("4/6 up on the day"))
        // Gainers ranked high→low, losers ranked most-negative first, flat KO omitted.
        assertTrue(body.contains("▲ NVDA +3.2% · MSFT +2.1% · V +1.4%"))
        assertTrue(body.contains("▼ AAPL −2.1% · PFE −1.2%"))
        assertTrue("flat name should be omitted", !body.contains("KO"))
    }

    @Test fun `close recap title reports the tracked count`() {
        assertEquals("Market close · 6 tracked", close()!!.title)
    }

    @Test fun `close recap caps each side at the top three`() {
        val many = listOf(
            mover("A", 5.0), mover("B", 4.0), mover("C", 3.0), mover("D", 2.0),
            mover("E", -5.0), mover("F", -4.0), mover("G", -3.0), mover("H", -2.0),
        )
        val body = close(movers = many)!!.body
        assertTrue(body.contains("▲ A +5.0% · B +4.0% · C +3.0%"))
        assertTrue("only top 3 gainers", !body.contains("D +2.0%"))
        assertTrue(body.contains("▼ E −5.0% · F −4.0% · G −3.0%"))
        assertTrue("only top 3 losers", !body.contains("H −2.0%"))
    }

    @Test fun `close recap shows only the side that has movers`() {
        val allUp = listOf(mover("NVDA", 3.2), mover("MSFT", 2.1))
        val body = close(movers = allUp)!!.body
        assertTrue(body.contains("▲ NVDA +3.2% · MSFT +2.1%"))
        assertTrue("no down side when nothing fell", !body.contains("▼"))
    }

    @Test fun `close recap on an empty watchlist returns null`() {
        assertNull(close(movers = emptyList()))
    }

    @Test fun `close recap when everything is flat still summarizes but lists no movers`() {
        val flat = listOf(mover("AAA", 0.0), mover("BBB", -0.01), mover("CCC", 0.02))
        val s = close(movers = flat)
        assertNotNull(s)
        assertTrue(s!!.body.contains("little movement"))
        assertTrue(!s.body.contains("▲") && !s.body.contains("▼"))
    }

    // --- After-hours recap timing ---

    private fun afterHours(
        phase: MarketPhase = MarketPhase.CLOSED,
        etSeconds: Int = afterEt,
        isTradingDay: Boolean = true,
        movers: List<Mover> = watchlist,
        sent: Boolean = false,
    ) = MarketSummary.build(
        phase = phase,
        etSecondsOfDay = etSeconds,
        isTradingDay = isTradingDay,
        movers = movers,
        alreadySentClose = false,
        alreadySentAfterHours = sent,
    )

    @Test fun `after-hours recap fires when closed past 20-00 ET on a trading day`() {
        val moved = listOf(mover("NVDA", 1.0, ah = 4.5), mover("AAPL", -0.5, ah = -3.0))
        val s = afterHours(movers = moved)
        assertNotNull(s)
        assertEquals(MoverSummary.Kind.AFTER_HOURS, s!!.kind)
    }

    @Test fun `after-hours recap does not fire before 20-00 ET`() {
        val moved = listOf(mover("NVDA", 1.0, ah = 4.5))
        assertNull(afterHours(etSeconds = 19 * 3600 + 30 * 60, movers = moved))
    }

    @Test fun `after-hours recap does not fire on a non-trading day`() {
        val moved = listOf(mover("NVDA", 1.0, ah = 4.5))
        assertNull(afterHours(isTradingDay = false, movers = moved))
    }

    @Test fun `after-hours dedup flag suppresses a second after-hours recap`() {
        val moved = listOf(mover("NVDA", 1.0, ah = 4.5))
        assertNull(afterHours(movers = moved, sent = true))
    }

    // --- After-hours recap content ---

    @Test fun `after-hours recap ranks by the after-hours move and skips null or near-zero`() {
        val movers = listOf(
            mover("NVDA", 1.0, ah = 4.5),
            mover("MSFT", 0.2, ah = 1.1),
            mover("AAPL", -0.3, ah = -2.7),
            mover("V", 0.5, ah = 0.01),  // ~0 after hours — skipped
            mover("KO", 0.9, ah = null), // no AH data — skipped
        )
        val s = afterHours(movers = movers)!!
        assertEquals("After-hours movers", s.title)
        assertTrue(s.body.contains("3 names moved after hours"))
        assertTrue(s.body.contains("▲ NVDA +4.5% · MSFT +1.1%"))
        assertTrue(s.body.contains("▼ AAPL −2.7%"))
        assertTrue("near-zero AH name skipped", !s.body.contains("V "))
        assertTrue("null AH name skipped", !s.body.contains("KO"))
    }

    @Test fun `after-hours recap is a quiet note when nothing moved after the close`() {
        val quiet = listOf(mover("NVDA", 1.0, ah = 0.0), mover("AAPL", -0.5, ah = null))
        val s = afterHours(movers = quiet)
        assertNotNull(s)
        assertEquals(MoverSummary.Kind.AFTER_HOURS, s!!.kind)
        assertTrue(s.body.contains("Quiet after-hours"))
    }

    // --- Crypto exclusion is the caller's job; assert the pure ranking over a pre-filtered list ---

    @Test fun `ranking operates only on the stocks the caller passes in`() {
        // Caller has already dropped crypto; BTC/ETH must never appear here.
        val stocksOnly = listOf(mover("NVDA", 3.2), mover("AAPL", -2.1))
        val body = close(movers = stocksOnly)!!.body
        assertTrue(body.contains("NVDA +3.2%"))
        assertTrue(body.contains("AAPL −2.1%"))
        assertTrue(!body.contains("BTC") && !body.contains("ETH"))
    }
}
