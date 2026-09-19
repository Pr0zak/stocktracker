package com.stocktracker.app.data.prefs

import com.stocktracker.app.data.model.CallPosition
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Same guard as [VerdictJournalStoreUnreadableTest], on the store that holds tracked option
 * positions. Asserts on currentForMutation — the rule the mutators actually run, where a null
 * return means refuse to write — rather than on whether the JSON library rejects bad input.
 */
class CallPositionStoreUnreadableTest {

    private val pos = CallPosition(
        id = "c1",
        symbol = "NVDA",
        strike = 120.0,
        expiryIso = "2026-10-16",
        expiryTs = 1_792_281_600_000L,
        contracts = 1,
        fillPrice = 4.25,
        openDateIso = "2026-09-18",
    )

    @Test
    fun `nothing ever written is Empty, so a mutator may start from an empty list`() {
        assertEquals(emptyList<CallPosition>(), CallPositionStore.currentForMutation(null))
    }

    @Test
    fun `an explicit empty list is Ok, not Unreadable`() {
        assertEquals(emptyList<CallPosition>(), CallPositionStore.currentForMutation("[]"))
    }

    @Test
    fun `valid stored json is returned for the mutator to build on`() {
        val raw = Http.json.encodeToString(listOf(pos))
        assertEquals(listOf(pos), CallPositionStore.currentForMutation(raw))
    }

    @Test
    fun `corrupt json refuses the write rather than starting from empty`() {
        for (corrupt in listOf("{", "[{invalid}]", "not json at all", """[{"id":"c1",""", "null")) {
            assertNull(
                "corrupt input $corrupt must refuse the write, not silently start from empty",
                CallPositionStore.currentForMutation(corrupt),
            )
        }
    }

    @Test
    fun `a truncated list refuses rather than losing the survivors`() {
        val full = Http.json.encodeToString(listOf(pos, pos.copy(id = "c2", symbol = "AMD")))
        assertNull(CallPositionStore.currentForMutation(full.substring(0, full.length / 2)))
    }
}
