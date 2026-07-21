package com.stocktracker.app.notify

import com.stocktracker.app.data.model.CallPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallExitRulesTest {

    /** Fixed "now" so DTE math is deterministic (epoch-ms). */
    private val nowMs = 1_700_000_000_000L

    /** Build a call whose expiry lands exactly [dte] days from [nowMs]. */
    private fun position(
        fillPrice: Double = 1.00,
        strike: Double = 100.0,
        dte: Int = 60,
        takeProfitPct: Double? = null,
        stopPct: Double? = null,
    ) = CallPosition(
        symbol = "aapl", // lower-case on purpose — rules must upper-case it in the message
        strike = strike,
        expiryIso = "n/a",
        expiryTs = nowMs / 1000L + dte * 86_400L,
        contracts = 1,
        fillPrice = fillPrice,
        openDateIso = "n/a",
        takeProfitPct = takeProfitPct,
        stopPct = stopPct,
    )

    private fun types(alerts: List<CallExitAlert>) = alerts.map { it.type }.toSet()

    @Test fun `take-profit fires when P-L crosses the default 80 percent`() {
        val p = position(fillPrice = 1.00, dte = 60) // 1.00 -> 1.80 = +80%, far-dated (no time alert)
        val alerts = CallExitRules.evaluate(p, currentPremiumPerShare = 1.80, spot = null, inTheMoney = null, nowEpochMs = nowMs)

        assertEquals(setOf(CallExitAlert.Type.TAKE_PROFIT), types(alerts))
        val alert = alerts.single()
        assertEquals("${p.id}:TAKE_PROFIT", alert.key)
        assertTrue(alert.message.contains("+80%"))
        assertTrue(alert.message.contains("AAPL \$100C"))
        assertTrue(alert.message.contains("selling to close"))
    }

    @Test fun `stop fires when P-L crosses the default minus 50 percent`() {
        val p = position(fillPrice = 2.00, dte = 60) // 2.00 -> 1.00 = -50%
        val alerts = CallExitRules.evaluate(p, currentPremiumPerShare = 1.00, spot = null, inTheMoney = null, nowEpochMs = nowMs)

        assertEquals(setOf(CallExitAlert.Type.STOP), types(alerts))
        val alert = alerts.single()
        assertEquals("${p.id}:STOP", alert.key)
        assertTrue(alert.message.contains("−50%")) // unicode minus, matches the spec wording
        assertTrue(alert.message.contains("at your stop"))
    }

    @Test fun `custom take-profit and stop thresholds override the defaults`() {
        // +50% would NOT trip the default 80 but DOES trip a custom take-profit of 40.
        val p = position(fillPrice = 1.00, dte = 60, takeProfitPct = 40.0)
        val alerts = CallExitRules.evaluate(p, currentPremiumPerShare = 1.50, spot = null, inTheMoney = null, nowEpochMs = nowMs)
        assertEquals(setOf(CallExitAlert.Type.TAKE_PROFIT), types(alerts))
    }

    @Test fun `time-stop fires at 21 DTE and no sooner from the P-L side`() {
        val p = position(fillPrice = 1.00, dte = 21) // flat premium -> 0% P/L, so only the time rule
        val alerts = CallExitRules.evaluate(p, currentPremiumPerShare = 1.00, spot = null, inTheMoney = null, nowEpochMs = nowMs)

        assertEquals(setOf(CallExitAlert.Type.TIME_STOP), types(alerts))
        val alert = alerts.single()
        assertEquals("${p.id}:TIME_STOP", alert.key)
        assertTrue(alert.message.contains("21d left"))
        assertTrue(alert.message.contains("time decay"))
    }

    @Test fun `expiry ITM tells you to sell to capture value before auto-exercise`() {
        val p = position(fillPrice = 1.00, strike = 100.0, dte = 3)
        // spot above strike -> inferred in-the-money (exercises the spot fallback path)
        val alerts = CallExitRules.evaluate(p, currentPremiumPerShare = 1.00, spot = 120.0, inTheMoney = null, nowEpochMs = nowMs)

        assertEquals(setOf(CallExitAlert.Type.EXPIRY), types(alerts))
        val alert = alerts.single()
        assertEquals("${p.id}:EXPIRY", alert.key)
        assertTrue(alert.message.contains("in-the-money"))
        assertTrue(alert.message.contains("auto-exercises"))
    }

    @Test fun `expiry OTM warns it is likely to expire worthless`() {
        val p = position(fillPrice = 1.00, strike = 100.0, dte = 2)
        val alerts = CallExitRules.evaluate(p, currentPremiumPerShare = 1.00, spot = 80.0, inTheMoney = false, nowEpochMs = nowMs)

        assertEquals(setOf(CallExitAlert.Type.EXPIRY), types(alerts))
        val alert = alerts.single()
        assertEquals("${p.id}:EXPIRY", alert.key)
        assertTrue(alert.message.contains("out-of-the-money"))
        assertTrue(alert.message.contains("worthless"))
    }

    @Test fun `expiry supersedes the time-stop so only one time alert fires under 3 DTE`() {
        val p = position(fillPrice = 1.00, dte = 1) // both <=21 and <=3 are true
        val alerts = CallExitRules.evaluate(p, currentPremiumPerShare = 1.00, spot = null, inTheMoney = true, nowEpochMs = nowMs)
        assertEquals(setOf(CallExitAlert.Type.EXPIRY), types(alerts))
    }

    @Test fun `no alert in the calm middle - mild gain and plenty of time`() {
        val p = position(fillPrice = 1.00, dte = 60) // +30%, far from any threshold or expiry
        val alerts = CallExitRules.evaluate(p, currentPremiumPerShare = 1.30, spot = 105.0, inTheMoney = true, nowEpochMs = nowMs)
        assertTrue("expected no alerts, got $alerts", alerts.isEmpty())
    }

    @Test fun `unpriced contract still yields DTE alerts but skips the P-L rules`() {
        val p = position(fillPrice = 1.00, dte = 40) // 40 DTE -> no time alert either
        val none = CallExitRules.evaluate(p, currentPremiumPerShare = null, spot = null, inTheMoney = null, nowEpochMs = nowMs)
        assertTrue("no premium + far DTE -> no alerts, got $none", none.isEmpty())

        val timeStop = CallExitRules.evaluate(position(dte = 10), null, null, null, nowMs)
        assertEquals(setOf(CallExitAlert.Type.TIME_STOP), types(timeStop))
    }
}
