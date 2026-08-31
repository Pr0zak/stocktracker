package com.stocktracker.app.ui

import com.stocktracker.app.data.model.AssetAlerts
import com.stocktracker.app.ui.detail.armedAlertLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart draws the cost line, the 200-week line and four AI-analyst levels — every level except
 * the one the user armed themselves. These cover the two halves of putting that right: the level is
 * drawn when the chart can hold it, and it is REPORTED, with a distance, when it cannot.
 *
 * The second half is the one worth testing. Clipping alone would have made an armed alert outside
 * the band render as nothing at all, which is the same failure the widget sparkline and the dip
 * strip both had: absent presented as if there were nothing to present.
 */
class AlertLevelsTest {

    private fun fmt(v: Double) = "$" + String.format("%.2f", v)

    // A chart spanning $100..$120, last close $110. The +/-5% band is therefore $95..$126.
    private fun levels(alerts: AssetAlerts, lastClose: Double = 110.0) =
        armedAlertLevels(alerts, chartMin = 100.0, chartMax = 120.0, lastClose = lastClose, format = ::fmt)

    @Test fun `a level inside the band is drawn, not reported`() {
        val out = levels(AssetAlerts(priceAbove = 118.0))
        assertEquals(1, out.size)
        assertFalse(out[0].isOffScale)
        assertEquals(null, out[0].offScalePct)
        assertEquals("Alert ≥ \$118.00", out[0].label)
        assertTrue(out[0].rising)
    }

    @Test fun `a level just inside the band edge is still drawn`() {
        // hi bound = 120 * 1.05 = 126
        assertFalse(levels(AssetAlerts(priceAbove = 126.0))[0].isOffScale)
        // lo bound = 100 * 0.95 = 95
        assertFalse(levels(AssetAlerts(priceBelow = 95.0))[0].isOffScale)
    }

    @Test fun `a level above the band is reported with its distance above`() {
        val out = levels(AssetAlerts(priceAbove = 180.0))
        assertTrue(out[0].isOffScale)
        // (180 - 110) / 110 = +63.6%
        assertEquals(63.636363, out[0].offScalePct!!, 1e-5)
    }

    @Test fun `a level below the band is reported with a negative distance`() {
        val out = levels(AssetAlerts(priceBelow = 50.0))
        assertTrue(out[0].isOffScale)
        // (50 - 110) / 110 = -54.5%
        assertEquals(-54.545454, out[0].offScalePct!!, 1e-5)
        assertFalse(out[0].rising)
    }

    /**
     * The whole point of the split: the alert must not vanish. Before this, an armed "below $50" on
     * a chart that never went under $100 produced no line and no text — indistinguishable from
     * having set no alert at all.
     */
    @Test fun `an off-scale alert is never silently dropped`() {
        val out = levels(AssetAlerts(priceAbove = 500.0, priceBelow = 1.0))
        assertEquals("both armed levels must survive the clip", 2, out.size)
        assertTrue(out.all { it.isOffScale })
    }

    @Test fun `both levels can be armed at once and classified independently`() {
        val out = levels(AssetAlerts(priceAbove = 118.0, priceBelow = 40.0))
        assertEquals(2, out.size)
        assertFalse("in-band above is drawn", out[0].isOffScale)
        assertTrue("out-of-band below is reported", out[1].isOffScale)
    }

    /** Day-change triggers have no position on a price plot, so they must not appear here at all. */
    @Test fun `percent triggers are not price levels and are excluded`() {
        assertTrue(levels(AssetAlerts(percentUp = 5.0, percentDown = 5.0)).isEmpty())
    }

    @Test fun `no armed alerts yields nothing`() {
        assertTrue(levels(AssetAlerts()).isEmpty())
    }

    /**
     * A zero or missing last close cannot produce a percentage. It must still report the alert as
     * off-scale — signalling 0.0 would claim the level sits exactly at the last close, which is the
     * opposite of what is true.
     */
    @Test fun `an off-scale alert with no usable baseline reports NaN, not zero`() {
        val out = levels(AssetAlerts(priceAbove = 500.0), lastClose = 0.0)
        assertTrue(out[0].isOffScale)
        assertTrue("distance must be unstated, not zero", out[0].offScalePct!!.isNaN())
    }

    @Test fun `labels carry the direction and the level, individually`() {
        val out = levels(AssetAlerts(priceAbove = 118.0, priceBelow = 101.0))
        assertEquals("Alert ≥ \$118.00", out[0].label)
        assertEquals("Alert ≤ \$101.00", out[1].label)
    }
}
