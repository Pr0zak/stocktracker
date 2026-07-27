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
        decode(prefs[key]) ?: emptyList()
    }

    suspend fun snapshot(): List<CallPosition> = positions.first()

    suspend fun add(position: CallPosition) = context.dataStore.edit { prefs ->
        val cur = decode(prefs[key]) ?: emptyList()
        if (cur.none { it.id == position.id }) prefs[key] = encode(cur + position)
    }

    /** Replace the entry with the same id; adds it if absent. */
    suspend fun update(position: CallPosition) = context.dataStore.edit { prefs ->
        val cur = decode(prefs[key]) ?: emptyList()
        prefs[key] = encode(
            if (cur.any { it.id == position.id }) cur.map { if (it.id == position.id) position else it }
            else cur + position,
        )
    }

    suspend fun delete(id: String) = context.dataStore.edit { prefs ->
        val cur = decode(prefs[key]) ?: emptyList()
        prefs[key] = encode(cur.filterNot { it.id == id })
    }

    /** Wholesale replace — used only by a backup restore, which is destructive by design. */
    suspend fun setAll(positions: List<CallPosition>) = context.dataStore.edit { prefs ->
        prefs[key] = encode(positions)
    }

    private fun decode(raw: String?): List<CallPosition>? =
        raw?.let { runCatching { Http.json.decodeFromString<List<CallPosition>>(it) }.getOrNull() }

    private fun encode(list: List<CallPosition>): String = Http.json.encodeToString(list)
}
