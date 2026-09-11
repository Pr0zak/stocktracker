package com.stocktracker.app.model

import com.stocktracker.app.data.model.AlertCondition
import com.stocktracker.app.data.model.AlertKind
import com.stocktracker.app.data.model.AssetAlerts
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning an alert off must not throw the level away.
 *
 * A level and whether it was armed used to be the same nullable field, so a switch could only be
 * turned off by erasing the number behind it. Re-arming meant remembering "$390" and typing it
 * again — on the one control in this app whose whole job is to tell you about a price you decided
 * mattered days ago.
 *
 * The tests below pin the three things that make the separation trustworthy: the level survives,
 * a switched-off level does not fire, and data written before any of this existed still reads as
 * armed rather than silently going quiet.
 */
class DisarmedAlertTest {

    private val codec = Json { ignoreUnknownKeys = true }

    @Test
    fun `turning an alert off keeps its level`() {
        val armed = AssetAlerts(priceAbove = 390.0)
        val off = armed.copy(disarmed = setOf(AlertKind.PRICE_ABOVE))

        assertEquals("the number the user typed is still there", 390.0, off.priceAbove!!, 0.0)
        assertNull("but nothing is watching it", off.armedPriceAbove)
    }

    @Test
    fun `re-arming needs no retyping`() {
        val off = AssetAlerts(priceAbove = 390.0, disarmed = setOf(AlertKind.PRICE_ABOVE))
        val on = off.copy(disarmed = off.disarmed - AlertKind.PRICE_ABOVE)

        assertEquals(390.0, on.armedPriceAbove!!, 0.0)
    }

    @Test
    fun `a disarmed level is not counted and does not keep the asset in the check`() {
        val only = AssetAlerts(priceBelow = 355.0, disarmed = setOf(AlertKind.PRICE_BELOW))

        assertEquals(0, only.activeCount)
        assertTrue(
            "an asset whose only alert is switched off must drop out of the checker, " +
                "exactly as if it had none",
            only.isEmpty,
        )
    }

    @Test
    fun `each kind is disarmed independently`() {
        val a = AssetAlerts(
            priceAbove = 390.0,
            priceBelow = 355.0,
            percentUp = 5.0,
            percentDown = 4.0,
            disarmed = setOf(AlertKind.PRICE_BELOW, AlertKind.PERCENT_DOWN),
        )
        assertEquals(390.0, a.armedPriceAbove!!, 0.0)
        assertNull(a.armedPriceBelow)
        assertEquals(5.0, a.armedPercentUp!!, 0.0)
        assertNull(a.armedPercentDown)
        assertEquals(2, a.activeCount)
    }

    @Test
    fun `conditions are unaffected by a disarmed level`() {
        val a = AssetAlerts(
            priceAbove = 390.0,
            conditions = setOf(AlertCondition.CLOSE_BELOW_SMA50),
            disarmed = setOf(AlertKind.PRICE_ABOVE),
        )
        assertEquals(1, a.activeCount)
        assertFalse("a live condition still keeps the asset in the check", a.isEmpty)
    }

    @Test
    fun `alerts stored before disarming existed decode as armed`() {
        // Exactly what the watchlist JSON held before this field was added. If it decoded as
        // disarmed — or as anything other than fully armed — every existing alert would go silent
        // on upgrade, and nothing on screen would say why.
        val legacy = """{"priceAbove":390.0,"priceBelow":355.0}"""
        val a = codec.decodeFromString(AssetAlerts.serializer(), legacy)

        assertEquals(390.0, a.armedPriceAbove!!, 0.0)
        assertEquals(355.0, a.armedPriceBelow!!, 0.0)
        assertEquals(2, a.activeCount)
        assertTrue(a.disarmed.isEmpty())
    }

    @Test
    fun `a disarmed alert survives a serialisation round trip`() {
        val off = AssetAlerts(priceAbove = 390.0, disarmed = setOf(AlertKind.PRICE_ABOVE))
        val back = codec.decodeFromString(AssetAlerts.serializer(), codec.encodeToString(AssetAlerts.serializer(), off))

        assertEquals("the level has to survive a restart, or 'kept' means nothing", 390.0, back.priceAbove!!, 0.0)
        assertNull(back.armedPriceAbove)
    }
}
