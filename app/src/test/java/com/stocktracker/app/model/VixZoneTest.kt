package com.stocktracker.app.model

import com.stocktracker.app.data.model.VixZone
import org.junit.Assert.assertEquals
import org.junit.Test

class VixZoneTest {

    @Test fun `zone boundaries are inclusive-low, exclusive-high`() {
        assertEquals(VixZone.CALM, VixZone.forValue(10.0))
        assertEquals(VixZone.CALM, VixZone.forValue(14.99))
        assertEquals(VixZone.NORMAL, VixZone.forValue(15.0))
        assertEquals(VixZone.NORMAL, VixZone.forValue(19.99))
        assertEquals(VixZone.ELEVATED, VixZone.forValue(20.0))
        assertEquals(VixZone.ELEVATED, VixZone.forValue(29.99))
        assertEquals(VixZone.HIGH, VixZone.forValue(30.0))
        assertEquals(VixZone.HIGH, VixZone.forValue(39.99))
        assertEquals(VixZone.EXTREME, VixZone.forValue(40.0))
        assertEquals(VixZone.EXTREME, VixZone.forValue(85.0))
    }
}
