package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.model.ClosedCallPosition
import com.stocktracker.app.data.remote.Http
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The user's closed-out call positions (OC-5) — realized-P&L history — persisted as a JSON list in
 * DataStore, mirroring [CallPositionStore]. Newest closes are appended to the tail; the UI reverses
 * for display.
 */
class ClosedCallPositionStore(private val context: Context) {

    private val key = stringPreferencesKey("closed_call_positions_json")

    val closed: Flow<List<ClosedCallPosition>> = context.dataStore.data.map { prefs ->
        decode(prefs[key]) ?: emptyList()
    }

    suspend fun snapshot(): List<ClosedCallPosition> = closed.first()

    suspend fun add(position: ClosedCallPosition) = context.dataStore.edit { prefs ->
        val cur = decode(prefs[key]) ?: emptyList()
        if (cur.none { it.id == position.id }) prefs[key] = encode(cur + position)
    }

    suspend fun delete(id: String) = context.dataStore.edit { prefs ->
        val cur = decode(prefs[key]) ?: emptyList()
        prefs[key] = encode(cur.filterNot { it.id == id })
    }

    /** Wholesale replace — used only by a backup restore, which is destructive by design. */
    suspend fun setAll(positions: List<ClosedCallPosition>) = context.dataStore.edit { prefs ->
        prefs[key] = encode(positions)
    }

    /** The exact bytes on disk, or null if the key was never written. For [com.stocktracker.app.data.BackupManager]
     *  only, so a restore snapshot mirrors [CallPositionStore]/[VerdictJournalStore] and can put back
     *  exactly what was there — the realized-P&L history this store holds. */
    internal fun rawValue(prefs: Preferences): String? = prefs[key]

    /** Writes [raw] verbatim, or clears the key when null. Participates in a caller-supplied
     *  transaction so several stores can be replaced atomically in one write. */
    internal fun writeRaw(prefs: MutablePreferences, raw: String?) {
        if (raw == null) prefs.remove(key) else prefs[key] = raw
    }

    private fun decode(raw: String?): List<ClosedCallPosition>? =
        raw?.let { runCatching { Http.json.decodeFromString<List<ClosedCallPosition>>(it) }.getOrNull() }

    private fun encode(list: List<ClosedCallPosition>): String = Http.json.encodeToString(list)
}
