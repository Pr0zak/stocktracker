package com.stocktracker.app.ui

import com.stocktracker.app.ui.detail.alertConditionSwitchStateDescription
import com.stocktracker.app.ui.detail.alertLevelSwitchStateDescription
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PLAT-4: the alert switch used to announce "Switch, off" with no way to tell which of the four
 * price alerts (or which condition) it belonged to. These are the pure functions behind the
 * `stateDescription` fix — the label itself goes in `contentDescription` at the call site, so it
 * isn't covered here, but the state text is what actually needed the arithmetic-adjacent logic
 * (armed vs. off-with-a-kept-level vs. never set).
 */
class AlertAccessibilityTest {

    private fun fmt(v: Double) = "$" + String.format("%.2f", v)

    @Test fun `no level set at all`() {
        assertEquals("not set", alertLevelSwitchStateDescription(level = null, armed = false, format = ::fmt))
    }

    @Test fun `armed with a level reports it`() {
        assertEquals(
            "armed at \$150.00",
            alertLevelSwitchStateDescription(level = 150.0, armed = true, format = ::fmt),
        )
    }

    @Test fun `off keeps the level and says so, not just off`() {
        // This is the whole point of AlertRow's "off keeps the level" behavior (see its KDoc) --
        // the switch's own announcement has to reflect that or a user has no way to know flipping
        // it back on won't ask them to retype a number.
        assertEquals(
            "off, level \$95.50 kept",
            alertLevelSwitchStateDescription(level = 95.5, armed = false, format = ::fmt),
        )
    }

    @Test fun `condition switch on`() {
        assertEquals("armed", alertConditionSwitchStateDescription(armed = true))
    }

    @Test fun `condition switch off`() {
        assertEquals("off", alertConditionSwitchStateDescription(armed = false))
    }
}
