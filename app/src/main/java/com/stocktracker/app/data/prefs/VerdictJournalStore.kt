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
        decode(prefs[key]) ?: emptyList()
    }

    suspend fun snapshot(): List<VerdictJournalEntry> = entries.first()

    suspend fun add(entry: VerdictJournalEntry) = context.dataStore.edit { prefs ->
        val cur = decode(prefs[key]) ?: emptyList()
        if (cur.none { it.id == entry.id }) prefs[key] = encode(cur + entry)
    }

    /** Replace the entry with the same id; adds it if absent. */
    suspend fun update(entry: VerdictJournalEntry) = context.dataStore.edit { prefs ->
        val cur = decode(prefs[key]) ?: emptyList()
        prefs[key] = encode(
            if (cur.any { it.id == entry.id }) cur.map { if (it.id == entry.id) entry else it }
            else cur + entry,
        )
    }

    suspend fun delete(id: String) = context.dataStore.edit { prefs ->
        val cur = decode(prefs[key]) ?: emptyList()
        prefs[key] = encode(cur.filterNot { it.id == id })
    }

    /** Wholesale replace — used only by a backup restore, which is destructive by design. */
    suspend fun setAll(entries: List<VerdictJournalEntry>) = context.dataStore.edit { prefs ->
        prefs[key] = encode(entries)
    }

    private fun decode(raw: String?): List<VerdictJournalEntry>? =
        raw?.let { runCatching { Http.json.decodeFromString<List<VerdictJournalEntry>>(it) }.getOrNull() }

    private fun encode(list: List<VerdictJournalEntry>): String = Http.json.encodeToString(list)
}
