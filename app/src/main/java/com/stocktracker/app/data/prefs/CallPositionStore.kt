package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.model.CallPosition
import com.stocktracker.app.data.remote.Http
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** The user's manually-tracked long-call positions (OC-3), persisted as a JSON list in DataStore. */
class CallPositionStore(private val context: Context) {

    private val key = stringPreferencesKey("call_positions_json")




    val positions: Flow<List<CallPosition>> = context.dataStore.data.map { prefs ->
        when (val s = read(prefs[key])) {
            is Stored.Ok -> s.positions
            Stored.Empty -> emptyList()
            // Deliberately NOT a demo list: an unreadable state is corrupted data, not missing data.
            Stored.Unreadable -> emptyList()
        }
    }

    /** True when stored data exists but could not be parsed — the UI should warn rather than
     *  present an empty list as though it were the user's. */
    val corrupted: Flow<Boolean> = context.dataStore.data.map { read(it[key]) is Stored.Unreadable }

    suspend fun snapshot(): List<CallPosition> = positions.first()

    /** Mutators refuse to run against unreadable storage, so the original bytes stay recoverable. */
    private inline fun mutate(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        block: (List<CallPosition>) -> List<CallPosition>,
    ) {
        val cur = currentForMutation(prefs[key]) ?: return   // corrupt: leave the bytes alone
        prefs[key] = encode(block(cur))
    }

    suspend fun add(position: CallPosition) = context.dataStore.edit { prefs ->
        mutate(prefs) { cur -> if (cur.none { it.id == position.id }) cur + position else cur }
    }

    /** Replace the entry with the same id; adds it if absent. */
    suspend fun update(position: CallPosition) = context.dataStore.edit { prefs ->
        mutate(prefs) { cur ->
            if (cur.any { it.id == position.id }) cur.map { if (it.id == position.id) position else it }
            else cur + position
        }
    }

    suspend fun delete(id: String) = context.dataStore.edit { prefs ->
        mutate(prefs) { cur -> cur.filterNot { it.id == id } }
    }

    /** Wholesale replace — used only by a backup restore, which is destructive by design. */
    suspend fun setAll(positions: List<CallPosition>) = context.dataStore.edit { prefs ->
        prefs[key] = encode(positions)
    }


    private fun encode(list: List<CallPosition>): String = Http.json.encodeToString(list)

    /** Pure, Context-free: the decode and the refusal rule, so both can be unit-tested. */
    companion object {
        /**
         * What is actually in storage.
         *
         * The distinction between [Empty] and [Unreadable] is the whole point. Collapsing both to "use
         * DEFAULT" meant a single unreadable byte looked exactly like a fresh install: the UI showed the
         * demo list, and then the very next add/update/delete encoded that demo list back over
         * the user's still-intact JSON — turning a recoverable read failure into permanent destruction
         * of hand-entered positions.
         */
        internal sealed interface Stored {
            data class Ok(val positions: List<CallPosition>) : Stored
            data object Empty : Stored          // nothing ever written — DEFAULT is genuinely right
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
        internal fun currentForMutation(raw: String?): List<CallPosition>? = when (val s = read(raw)) {
            is Stored.Ok -> s.positions
            Stored.Empty -> emptyList()
            Stored.Unreadable -> null
        }
        private fun decode(raw: String?): List<CallPosition>? =
            raw?.let { runCatching { Http.json.decodeFromString<List<CallPosition>>(it) }.getOrNull() }
    }
}
