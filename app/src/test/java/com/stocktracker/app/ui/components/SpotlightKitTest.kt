package com.stocktracker.app.ui.components

import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotlightKitTest {

    @Test
    fun `change pill carries direction in the arrow, not only the colour`() {
        assertEquals("▲ 0.52%", changePillText(0.52))
        assertEquals("▼ 1.47%", changePillText(-1.47))
        assertEquals("▲ 0.00%", changePillText(0.0))
    }

    @Test
    fun `an unknown move earns no glow`() {
        assertNull(directionTint(null))
        assertNull(directionTint(Double.NaN))
        assertNull(directionTint(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `a known move glows its own direction`() {
        assertEquals(GainGreen, directionTint(0.0))
        assertEquals(GainGreen, directionTint(134.14))
        assertEquals(LossRed, directionTint(-0.01))
    }
}
