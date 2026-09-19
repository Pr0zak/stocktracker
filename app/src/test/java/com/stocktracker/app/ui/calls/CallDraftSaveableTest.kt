package com.stocktracker.app.ui.calls

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

/**
 * Pins the one property that keeps DetailScreen's rememberSaveable call from crashing.
 *
 * rememberSaveable rejects a type the SaveableStateRegistry cannot store, and it does so at
 * runtime, on rotation, holding a non-null value — precisely the situation the draft state exists
 * to survive. Nothing about that failure is visible at compile time, so this asserts the contract
 * directly rather than trusting that it compiled.
 */
class CallDraftSaveableTest {

    @Test
    fun `a call draft can actually be written to saved state`() {
        val draft = CallDraft(
            symbol = "NVDA",
            contractSymbol = "NVDA261016C00120000",
            strike = 120.0,
            expiryIso = "2026-10-16",
            expiryTs = 1_792_281_600_000L,
            contracts = 2,
            fillPrice = 4.25,
        )
        assertTrue("CallDraft must be Serializable for rememberSaveable", draft is java.io.Serializable)
        ObjectOutputStream(ByteArrayOutputStream()).use { it.writeObject(draft) }
    }
}
