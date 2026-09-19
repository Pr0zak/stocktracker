package com.stocktracker.app.data.prefs

import com.stocktracker.app.data.model.VerdictJournalEntry
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data-loss guard, tested at the seam that actually decides.
 *
 * Before the Unreadable state existed, a corrupt stored blob decoded to "empty", and the next
 * add/update/delete encoded an empty list back over the user's still-intact JSON — a recoverable
 * read failure turned into the permanent loss of a real trading history. A lost call position can
 * be re-entered; a lost journal entry is a trade that happened and no longer has a record.
 *
 * These assert on VerdictJournalStore.currentForMutation, which IS the rule the mutators run:
 * a null return means refuse to write. Testing that the JSON library rejects malformed input
 * would prove nothing about this store.
 */
class VerdictJournalStoreUnreadableTest {

    private val entry = VerdictJournalEntry(id = "e1", symbol = "AAPL", verdictDateIso = "2026-09-18")

    @Test
    fun `nothing ever written is Empty, and a mutator may safely start from an empty list`() {
        assertEquals(emptyList<VerdictJournalEntry>(), VerdictJournalStore.currentForMutation(null))
    }

    @Test
    fun `an explicit empty list is Ok, not Unreadable`() {
        assertEquals(emptyList<VerdictJournalEntry>(), VerdictJournalStore.currentForMutation("[]"))
    }

    @Test
    fun `valid stored json is returned for the mutator to build on`() {
        val raw = Http.json.encodeToString(listOf(entry))
        assertEquals(listOf(entry), VerdictJournalStore.currentForMutation(raw))
    }

    @Test
    fun `corrupt json refuses the write rather than starting from empty`() {
        for (corrupt in listOf("{", "[{invalid}]", "not json at all", """[{"id":"e1",""", "null")) {
            assertNull(
                "corrupt input $corrupt must refuse the write, not silently start from empty",
                VerdictJournalStore.currentForMutation(corrupt),
            )
        }
    }

    @Test
    fun `a truncated journal of many entries refuses rather than losing the survivors`() {
        val full = Http.json.encodeToString(listOf(entry, entry.copy(id = "e2", symbol = "MSFT")))
        val truncated = full.substring(0, full.length / 2)
        assertNull(VerdictJournalStore.currentForMutation(truncated))
    }

    @Test
    fun `refusing is distinguishable from an empty result, which is the whole point`() {
        assertTrue(VerdictJournalStore.currentForMutation("[]")!!.isEmpty())
        assertNull(VerdictJournalStore.currentForMutation("{"))
    }
}
