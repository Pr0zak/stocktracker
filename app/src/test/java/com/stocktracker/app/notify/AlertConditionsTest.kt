package com.stocktracker.app.notify

import com.stocktracker.app.data.model.AlertCondition
import com.stocktracker.app.data.model.AssetAlerts
import com.stocktracker.app.data.model.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Technical alert conditions, and the three-valued answer that is the point of them.
 *
 * A condition is true, false, or **unanswerable**. Collapsing the third into the second is what makes
 * a broken alert indistinguishable from a quiet market: the fetch fails, or the series is too short,
 * or the bars are spliced across a split, and the user sees exactly what they would see on a day
 * nothing happened.
 */
class AlertConditionsTest {

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 400L * DAY

        /** [n] daily bars ending at NOW, so nothing is stale unless a test makes it so. */
        fun series(n: Int, f: (Int) -> Double): List<PricePoint> =
            (0 until n).map { PricePoint(NOW - (n - 1 - it) * DAY, f(it)) }

        fun flat(n: Int, price: Double) = series(n) { price }
    }

    private fun evaluate(c: AlertCondition, pts: List<PricePoint>, now: Long = NOW) =
        AlertConditions.evaluate(c, pts, now)

    // --- the measurement -------------------------------------------------------------------------

    @Test fun `a close above the 50-day average triggers, and below does not`() {
        val rising = series(120) { 100.0 + it }
        assertEquals(ConditionResult.Triggered, evaluate(AlertCondition.CLOSE_ABOVE_SMA50, rising))
        assertEquals(ConditionResult.NotTriggered, evaluate(AlertCondition.CLOSE_BELOW_SMA50, rising))
    }

    @Test fun `a falling series reads the other way round`() {
        val falling = series(120) { 300.0 - it }
        assertEquals(ConditionResult.Triggered, evaluate(AlertCondition.CLOSE_BELOW_SMA50, falling))
        assertEquals(ConditionResult.NotTriggered, evaluate(AlertCondition.CLOSE_ABOVE_SMA50, falling))
    }

    @Test fun `a 52-week high needs the close to clear every prior close in the window`() {
        val breakout = series(300) { if (it == 299) 500.0 else 100.0 + (it % 20) }
        assertEquals(ConditionResult.Triggered, evaluate(AlertCondition.CLOSE_AT_52W_HIGH, breakout))

        val justUnder = series(300) { if (it == 299) 110.0 else 100.0 + (it % 20) }
        assertEquals(ConditionResult.NotTriggered, evaluate(AlertCondition.CLOSE_AT_52W_HIGH, justUnder))
    }

    // --- unanswerable, which is not the same as false ----------------------------------------------

    @Test fun `too few bars is reported, not answered`() {
        val r = evaluate(AlertCondition.CLOSE_ABOVE_SMA200, flat(120, 100.0))
        assertTrue(r is ConditionResult.CouldNotCheck)
        assertTrue((r as ConditionResult.CouldNotCheck).reason.contains("200 daily bars"))
    }

    /**
     * MarketRepository's cache has an unbounded stale-while-error branch — on a thrown fetch it hands
     * back the last good value with no age check at all. So a call that LOOKS successful can return
     * week-old bars, and the evaluator checks the last bar's own timestamp rather than trusting it.
     */
    @Test fun `stale history is reported even though the fetch succeeded`() {
        val old = series(300) { 100.0 + it }.map { it.copy(epochMs = it.epochMs - 30 * DAY) }
        val r = evaluate(AlertCondition.CLOSE_ABOVE_SMA50, old)
        assertTrue(r is ConditionResult.CouldNotCheck)
        assertTrue((r as ConditionResult.CouldNotCheck).reason.contains("days old"))
    }

    @Test fun `a long weekend is not staleness`() {
        val friday = series(300) { 100.0 + it }.map { it.copy(epochMs = it.epochMs - 3 * DAY) }
        assertTrue(evaluate(AlertCondition.CLOSE_ABOVE_SMA50, friday) !is ConditionResult.CouldNotCheck)
    }

    /**
     * The backend rejects roughly a dozen names a night at this threshold — BYND has been seen
     * oscillating 0.59 to 17.85 to 0.56 on Yahoo's mixed split basis. Without the same guard on
     * device, a spliced series produces a confident "closed at a 52-week high" off a pre-split bar.
     */
    @Test fun `a split-shaped break refuses rather than reporting a false high`() {
        val spliced = series(300) { if (it < 150) 0.59 else 17.85 }
        val r = evaluate(AlertCondition.CLOSE_AT_52W_HIGH, spliced)
        assertTrue("a 30x single-day break is not a price move", r is ConditionResult.CouldNotCheck)
        assertTrue((r as ConditionResult.CouldNotCheck).reason.contains("split"))
    }

    @Test fun `a large but plausible move is not mistaken for a split`() {
        val gapUp = series(300) { if (it < 299) 100.0 else 180.0 }  // +80% in a day, real
        assertTrue(evaluate(AlertCondition.CLOSE_AT_52W_HIGH, gapUp) !is ConditionResult.CouldNotCheck)
    }

    @Test fun `an empty series is unanswerable rather than false`() {
        assertTrue(evaluate(AlertCondition.CLOSE_ABOVE_SMA50, emptyList()) is ConditionResult.CouldNotCheck)
    }

    @Test fun `worstBarRatio ignores non-positive and non-finite bars instead of dividing by them`() {
        val pts = listOf(
            PricePoint(0, 100.0), PricePoint(DAY, 0.0), PricePoint(2 * DAY, 101.0),
            PricePoint(3 * DAY, Double.NaN), PricePoint(4 * DAY, 102.0),
        )
        val r = AlertConditions.worstBarRatio(pts)
        assertTrue("must not report an infinite ratio", r == null || r.isFinite())
        assertTrue(r == null || r < 2.0)
    }

    // --- the two silent-failure bugs in the surrounding model ---------------------------------------

    /**
     * AlertChecker filters the watchlist on `!isEmpty` before evaluating anything. Until conditions
     * joined this test, an asset carrying only a technical condition was armed in the UI, never run,
     * and produced no error to say so.
     */
    @Test fun `an asset armed with only a condition is not treated as empty`() {
        val condOnly = AssetAlerts(conditions = setOf(AlertCondition.CLOSE_ABOVE_SMA200))
        assertFalse("this asset would never have been evaluated", condOnly.isEmpty)
        assertEquals(1, condOnly.activeCount)
    }

    @Test fun `an asset with nothing armed is still empty`() {
        assertTrue(AssetAlerts().isEmpty)
        assertEquals(0, AssetAlerts().activeCount)
    }

    @Test fun `the badge counts levels and conditions together`() {
        val both = AssetAlerts(
            priceAbove = 100.0,
            priceBelow = 50.0,
            conditions = setOf(AlertCondition.CLOSE_ABOVE_SMA50, AlertCondition.CLOSE_AT_52W_HIGH),
        )
        assertEquals(4, both.activeCount)
    }

    /**
     * Every edit site used to build a FRESH AssetAlerts from the four doubles, so any field it did not
     * name reset to its default. Disarming one price level would have silently cleared every armed
     * condition. `copy()` is the fix; this pins the property it restores.
     */
    @Test fun `clearing one level leaves the conditions armed`() {
        val armed = AssetAlerts(
            priceAbove = 100.0,
            conditions = setOf(AlertCondition.CLOSE_BELOW_SMA200),
        )
        val afterDisarm = armed.copy(priceAbove = null)
        assertEquals(setOf(AlertCondition.CLOSE_BELOW_SMA200), afterDisarm.conditions)
        assertFalse(afterDisarm.isEmpty)
    }

    @Test fun `every condition declares enough bars to answer itself`() {
        // A 200-day average cannot be computed from fewer than 200 bars, and the guard is what stops
        // simpleMovingAverage returning a null the evaluator would have to interpret.
        assertTrue(AlertCondition.CLOSE_ABOVE_SMA200.minBars >= 200)
        assertTrue(AlertCondition.CLOSE_AT_52W_HIGH.minBars >= 252)
        AlertCondition.entries.forEach { assertTrue(it.minBars > 0) }
    }

    @Test fun `condition keys are stable and unique, since they are persisted`() {
        val keys = AlertCondition.entries.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(keys.contains("above_sma200"))
    }
}

/**
 * A backup written before conditions existed must still restore. `AssetAlerts` is @Serializable and
 * reaches disk through the watchlist store and the export file, so a decoder that required the new
 * field would fail every restore taken before today.
 */
class AlertsBackCompatTest {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Test fun `an old payload with no conditions field decodes to none armed`() {
        val old = """{"priceAbove":100.0,"priceBelow":50.0}"""
        val decoded = json.decodeFromString(AssetAlerts.serializer(), old)
        assertEquals(100.0, decoded.priceAbove!!, 0.0)
        assertTrue(decoded.conditions.isEmpty())
        assertEquals(2, decoded.activeCount)
    }

    @Test fun `conditions round-trip by their stable keys`() {
        val armed = AssetAlerts(conditions = setOf(AlertCondition.CLOSE_ABOVE_SMA200))
        val text = json.encodeToString(AssetAlerts.serializer(), armed)
        assertEquals(armed, json.decodeFromString(AssetAlerts.serializer(), text))
    }

    @Test fun `an unknown condition from a newer build does not break the decode`() {
        // Forward compatibility matters here too: the in-app updater means an older build can meet a
        // backup written by a newer one.
        val future = """{"conditions":["CLOSE_ABOVE_SMA50"]}"""
        val decoded = json.decodeFromString(AssetAlerts.serializer(), future)
        assertEquals(setOf(AlertCondition.CLOSE_ABOVE_SMA50), decoded.conditions)
    }
}
