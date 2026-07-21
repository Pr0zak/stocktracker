package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    private val lastScanNotifiedKey = longPreferencesKey("last_scan_notified_at")
    private val investableCashKey = doublePreferencesKey("investable_cash")
    private val aiAnalystEnabledKey = booleanPreferencesKey("ai_analyst_enabled")
    private val lastDigestKey = longPreferencesKey("last_weekly_digest_at")
    private val marketSummaryEnabledKey = booleanPreferencesKey("market_summary_enabled")
    private val marketSummaryAfterHoursKey = booleanPreferencesKey("market_summary_after_hours")
    private val marketSummaryMarketWideKey = booleanPreferencesKey("market_summary_market_wide")
    private val lastCloseSummaryKey = stringPreferencesKey("last_close_summary_date")
    private val lastAfterHoursSummaryKey = stringPreferencesKey("last_after_hours_summary_date")

    /** Base URL of the self-hosted Signals analyst service (empty = the AI analyst card is off). */
    val signalsApiUrl: Flow<String> = context.dataStore.data.map { it[signalsApiUrlKey] ?: "" }

    /** epoch-seconds of the last nightly scan we already notified about (dedup across worker runs). */
    val lastScanNotifiedAt: Flow<Long> = context.dataStore.data.map { it[lastScanNotifiedKey] ?: 0L }
    suspend fun setLastScanNotifiedAt(value: Long) = context.dataStore.edit { it[lastScanNotifiedKey] = value }

    /** Free cash the user considers investable (drives the Ideas screen + entry plans). 0 = unset. */
    val investableCash: Flow<Double> = context.dataStore.data.map { it[investableCashKey] ?: 0.0 }
    suspend fun setInvestableCash(amount: Double) = context.dataStore.edit {
        it[investableCashKey] = amount.coerceAtLeast(0.0)
    }

    /** Master switch for app-initiated AI calls (verdicts, plans, ideas) — off saves token cost
     *  without losing the configured service URL. The server's nightly scan is unaffected. */
    val aiAnalystEnabled: Flow<Boolean> = context.dataStore.data.map { it[aiAnalystEnabledKey] ?: true }
    suspend fun setAiAnalystEnabled(enabled: Boolean) = context.dataStore.edit { it[aiAnalystEnabledKey] = enabled }

    /** epoch-ms of the last weekly watchlist digest we posted (0 = never). */
    val lastDigestAt: Flow<Long> = context.dataStore.data.map { it[lastDigestKey] ?: 0L }
    suspend fun setLastDigestAt(value: Long) = context.dataStore.edit { it[lastDigestKey] = value }

    /** Master switch for the market-close & after-hours movers recap notifications (default on). */
    val marketSummaryEnabled: Flow<Boolean> = context.dataStore.data.map { it[marketSummaryEnabledKey] ?: true }
    suspend fun setMarketSummaryEnabled(enabled: Boolean) =
        context.dataStore.edit { it[marketSummaryEnabledKey] = enabled }

    /** When true, also post the after-hours recap (≥8pm ET). Off keeps only the close recap. Default on. */
    val marketSummaryAfterHours: Flow<Boolean> = context.dataStore.data.map { it[marketSummaryAfterHoursKey] ?: true }
    suspend fun setMarketSummaryAfterHours(enabled: Boolean) =
        context.dataStore.edit { it[marketSummaryAfterHoursKey] = enabled }

    /** Close-recap source: true = the whole market's biggest movers, false (default) = your watchlist. */
    val marketSummaryMarketWide: Flow<Boolean> = context.dataStore.data.map { it[marketSummaryMarketWideKey] ?: false }
    suspend fun setMarketSummaryMarketWide(enabled: Boolean) =
        context.dataStore.edit { it[marketSummaryMarketWideKey] = enabled }

    /** ET date (yyyy-MM-dd) the close recap last fired; "" = never. Dedups it to once per trading day. */
    val lastCloseSummaryDate: Flow<String> = context.dataStore.data.map { it[lastCloseSummaryKey] ?: "" }
    suspend fun setLastCloseSummaryDate(date: String) = context.dataStore.edit { it[lastCloseSummaryKey] = date }

    /** ET date (yyyy-MM-dd) the after-hours recap last fired; "" = never. Dedups it to once per day. */
    val lastAfterHoursSummaryDate: Flow<String> = context.dataStore.data.map { it[lastAfterHoursSummaryKey] ?: "" }
    suspend fun setLastAfterHoursSummaryDate(date: String) =
        context.dataStore.edit { it[lastAfterHoursSummaryKey] = date }

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
