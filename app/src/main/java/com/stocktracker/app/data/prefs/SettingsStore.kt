package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stocktracker.app.data.remote.Http
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** App-level preferences: theme, dynamic color, default widget refresh interval. */
class SettingsStore(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme_mode")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    private val refreshKey = intPreferencesKey("default_refresh_minutes")
    private val finnhubKeyKey = stringPreferencesKey("finnhub_api_key")
    private val hideZeroCentsKey = booleanPreferencesKey("hide_zero_cents")
    private val extendedHoursKey = booleanPreferencesKey("show_extended_hours")
    private val marketStatusKey = booleanPreferencesKey("show_market_status")
    private val showVolumeKey = booleanPreferencesKey("show_volume")
    private val showVixKey = booleanPreferencesKey("show_vix")
    private val chartIndicatorsKey = stringPreferencesKey("chart_indicators")
    private val watchlistGroupsKey = stringPreferencesKey("watchlist_groups")
    private val signalsApiUrlKey = stringPreferencesKey("signals_api_url")

    /** Base URL of the self-hosted Signals analyst service (empty = the AI analyst card is off). */
    val signalsApiUrl: Flow<String> = context.dataStore.data.map { it[signalsApiUrlKey] ?: "" }

    /** User-entered Finnhub key (empty = fall back to the build-time BuildConfig key). */
    val finnhubApiKey: Flow<String> = context.dataStore.data.map { it[finnhubKeyKey] ?: "" }

    /** When true, whole-dollar prices are shown without a trailing ".00". */
    val hideZeroCents: Flow<Boolean> = context.dataStore.data.map { it[hideZeroCentsKey] ?: false }

    /** When true, the 1D/1W stock chart includes pre/post-market and marks it distinctly. */
    val showExtendedHours: Flow<Boolean> = context.dataStore.data.map { it[extendedHoursKey] ?: false }

    /** When true, the watchlist shows the market-session timeline at the top. */
    val showMarketStatus: Flow<Boolean> = context.dataStore.data.map { it[marketStatusKey] ?: true }

    /** When true, the detail chart overlays volume bars. */
    val showVolume: Flow<Boolean> = context.dataStore.data.map { it[showVolumeKey] ?: false }

    /** Enabled chart indicators (keys like "sma20", "ema21", "bb", "vwap", "rsi", "macd"). */
    val chartIndicators: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[chartIndicatorsKey]?.let { runCatching { Http.json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?.toSet() ?: emptySet()
    }

    /** When true, the dashboard shows the VIX "market fear" gauge. */
    val showVix: Flow<Boolean> = context.dataStore.data.map { it[showVixKey] ?: true }

    /** User-defined watchlist names (in display order). Empty = only the built-in All/Stocks/Crypto. */
    val watchlistGroups: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[watchlistGroupsKey]?.let { runCatching { Http.json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: emptyList()
    }

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
    suspend fun setShowMarketStatus(enabled: Boolean) = context.dataStore.edit { it[marketStatusKey] = enabled }
    suspend fun setShowVolume(enabled: Boolean) = context.dataStore.edit { it[showVolumeKey] = enabled }
    suspend fun setChartIndicators(keys: Set<String>) = context.dataStore.edit {
        it[chartIndicatorsKey] = Http.json.encodeToString(keys.toList())
    }
    suspend fun setShowVix(enabled: Boolean) = context.dataStore.edit { it[showVixKey] = enabled }
    suspend fun setWatchlistGroups(groups: List<String>) = context.dataStore.edit {
        it[watchlistGroupsKey] = Http.json.encodeToString(groups)
    }
    suspend fun setSignalsApiUrl(url: String) = context.dataStore.edit { it[signalsApiUrlKey] = url.trim().trimEnd('/') }
}
