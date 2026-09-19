package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.model.VerdictJournalEntry
import com.stocktracker.app.data.remote.Http
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The verdict journal (SWT-8) — what you did with each verdict — persisted as a JSON list in
 * DataStore, mirroring [ClosedCallPositionStore] exactly so both histories survive the same way.
 *
 * Entries are appended in the order they were LOGGED; the record and the curve re-order by close date
 * themselves, because store order is not chronology.
 *
 * [update] is the workhorse here rather than an afterthought: unlike a closed option, a journal entry
 * is edited repeatedly over its life — logged undecided, marked taken, given a fill, and finally given
 * an exit, each a separate visit.
 */
class VerdictJournalStore(private val context: Context) {

    private val key = stringPreferencesKey("verdict_journal_json")




    val entries: Flow<List<VerdictJournalEntry>> = context.dataStore.data.map { prefs ->
        when (val s = read(prefs[key])) {
            is Stored.Ok -> s.entries
            Stored.Empty -> emptyList()
            // Deliberately NOT a demo list: an unreadable state is corrupted data, not missing data.
            Stored.Unreadable -> emptyList()
        }
    }

    /** True when stored data exists but could not be parsed — the journal is corrupted and needs
     *  recovery or a fresh start. This is the data loss warning. */
    val corrupted: Flow<Boolean> = context.dataStore.data.map { read(it[key]) is Stored.Unreadable }

    suspend fun snapshot(): List<VerdictJournalEntry> = entries.first()

    /** Mutators refuse to run against unreadable storage, so the original bytes stay recoverable. */
    private inline fun mutate(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        block: (List<VerdictJournalEntry>) -> List<VerdictJournalEntry>,
    ) {
        val cur = currentForMutation(prefs[key]) ?: return   // corrupt: leave the bytes alone
        prefs[key] = encode(block(cur))
    }

    suspend fun add(entry: VerdictJournalEntry) = context.dataStore.edit { prefs ->
        mutate(prefs) { cur -> if (cur.none { it.id == entry.id }) cur + entry else cur }
    }

    /** Replace the entry with the same id; adds it if absent. */
    suspend fun update(entry: VerdictJournalEntry) = context.dataStore.edit { prefs ->
        mutate(prefs) { cur ->
            if (cur.any { it.id == entry.id }) cur.map { if (it.id == entry.id) entry else it }
            else cur + entry
        }
    }

    suspend fun delete(id: String) = context.dataStore.edit { prefs ->
        mutate(prefs) { cur -> cur.filterNot { it.id == id } }
    }

    /** Wholesale replace — used only by a backup restore, which is destructive by design. */
    suspend fun setAll(entries: List<VerdictJournalEntry>) = context.dataStore.edit { prefs ->
        prefs[key] = encode(entries)
    }


    private fun encode(list: List<VerdictJournalEntry>): String = Http.json.encodeToString(list)

    /** Pure, Context-free: the decode and the refusal rule, so both can be unit-tested. */
    companion object {
        /**
         * What is actually in storage.
         *
         * The distinction between [Empty] and [Unreadable] is crucial for a journal of real trades.
         * Collapsing both to "use an empty list" meant a single unreadable byte looked exactly like a fresh
         * install: and then the very next add/update/delete encoded an empty list back over the user's
         * still-intact JSON — turning a recoverable read failure into permanent destruction of the entire
         * trading history.
         */
        internal sealed interface Stored {
            data class Ok(val entries: List<VerdictJournalEntry>) : Stored
            data object Empty : Stored          // nothing ever written — empty list is genuinely right
            data object Unreadable : Stored     // present but corrupt — must NOT be overwritten
        }
        internal fun read(raw: String?): Stored = when {
            raw == null -> Stored.Empty
            else -> decode(raw)?.let { Stored.Ok(it) } ?: Stored.Unreadable
        }
        /**
         * The list a mutator should start from, or null meaning REFUSE to write.
         *
         * This is the whole data-loss guard stated as one pure function so it can be tested without a
         * DataStore. [mutate] is this decision plus the write; keep the two in step.
         */
        internal fun currentForMutation(raw: String?): List<VerdictJournalEntry>? = when (val s = read(raw)) {
            is Stored.Ok -> s.entries
            Stored.Empty -> emptyList()
            Stored.Unreadable -> null
        }
        private fun decode(raw: String?): List<VerdictJournalEntry>? =
            raw?.let { runCatching { Http.json.decodeFromString<List<VerdictJournalEntry>>(it) }.getOrNull() }
    }
}
