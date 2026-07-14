package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** App-level preferences: theme, dynamic color, default widget refresh interval. */
class SettingsStore(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme_mode")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    private val refreshKey = intPreferencesKey("default_refresh_minutes")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[dynamicKey] ?: true }

    val defaultRefreshMinutes: Flow<Int> = context.dataStore.data.map { it[refreshKey] ?: 15 }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[themeKey] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[dynamicKey] = enabled }
    suspend fun setDefaultRefreshMinutes(minutes: Int) = context.dataStore.edit { it[refreshKey] = minutes }
}
