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
    private val finnhubKeyKey = stringPreferencesKey("finnhub_api_key")
    private val hideZeroCentsKey = booleanPreferencesKey("hide_zero_cents")
    private val extendedHoursKey = booleanPreferencesKey("show_extended_hours")

    /** User-entered Finnhub key (empty = fall back to the build-time BuildConfig key). */
    val finnhubApiKey: Flow<String> = context.dataStore.data.map { it[finnhubKeyKey] ?: "" }

    /** When true, whole-dollar prices are shown without a trailing ".00". */
    val hideZeroCents: Flow<Boolean> = context.dataStore.data.map { it[hideZeroCentsKey] ?: false }

    /** When true, the 1D stock chart includes pre/post-market and marks it distinctly. */
    val showExtendedHours: Flow<Boolean> = context.dataStore.data.map { it[extendedHoursKey] ?: false }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[dynamicKey] ?: true }

    val defaultRefreshMinutes: Flow<Int> = context.dataStore.data.map { it[refreshKey] ?: 15 }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[themeKey] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[dynamicKey] = enabled }
    suspend fun setDefaultRefreshMinutes(minutes: Int) = context.dataStore.edit { it[refreshKey] = minutes }
    suspend fun setFinnhubApiKey(key: String) = context.dataStore.edit { it[finnhubKeyKey] = key.trim() }
    suspend fun setHideZeroCents(enabled: Boolean) = context.dataStore.edit { it[hideZeroCentsKey] = enabled }
    suspend fun setShowExtendedHours(enabled: Boolean) = context.dataStore.edit { it[extendedHoursKey] = enabled }
}
