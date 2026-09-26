package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.widget.WidgetBackground
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** App-level preferences: theme, dynamic color, default widget refresh interval. */
class SettingsStore(private val context: Context) {

    // theme_mode and dynamic_color are no longer read: the app is dark, always. The stored keys
    // are left in place rather than migrated away — they cost nothing and a future reader
    // finding them in a DataStore dump should find this note rather than a mystery.
    private val refreshKey = intPreferencesKey("default_refresh_minutes")
    private val finnhubKeyKey = stringPreferencesKey("finnhub_api_key")
    private val hideZeroCentsKey = booleanPreferencesKey("hide_zero_cents")
    private val widgetBgArgbKey = longPreferencesKey("widget_bg_argb")
    private val widgetBgTransparencyKey = intPreferencesKey("widget_bg_transparency")
    private val groupBySectorKey = booleanPreferencesKey("watchlist_group_by_sector")
    private val collapsedVerticalsKey = stringSetPreferencesKey("watchlist_collapsed_verticals")
    private val extendedHoursKey = booleanPreferencesKey("show_extended_hours")
    private val marketStatusKey = booleanPreferencesKey("show_market_status")
    private val showVolumeKey = booleanPreferencesKey("show_volume")
    private val showVixKey = booleanPreferencesKey("show_vix")
    private val showGateKey = booleanPreferencesKey("show_gate")
    private val contextOpenKey = booleanPreferencesKey("context_strip_open")
    private val chartIndicatorsKey = stringPreferencesKey("chart_indicators")
    private val chartCandlesKey = booleanPreferencesKey("chart_candles")
    private val chartLogScaleKey = booleanPreferencesKey("chart_log_scale")
    private val watchlistGroupsKey = stringPreferencesKey("watchlist_groups")
    private val signalsApiUrlKey = stringPreferencesKey("signals_api_url")

    /**
     * Shared secret for the self-hosted signals backend (SEC-2).
     *
     * The backend requires it on every route that mutates anything or that discloses the watchlist
     * or the paper book. Empty means "not configured", which is the correct state for anyone whose
     * backend predates the requirement — their requests simply go out without the header, and the
     * backend they are talking to does not ask for one.
     */
    private val signalsApiTokenKey = stringPreferencesKey("signals_api_token")
    private val installIdKey = stringPreferencesKey("install_id")
    private val lastScanNotifiedKey = longPreferencesKey("last_scan_notified_at")
    private val investableCashKey = doublePreferencesKey("investable_cash")
    private val aiAnalystEnabledKey = booleanPreferencesKey("ai_analyst_enabled")
    private val taxableAccountKey = booleanPreferencesKey("taxable_account")
    private val lastDigestKey = longPreferencesKey("last_weekly_digest_at")
    private val marketSummaryEnabledKey = booleanPreferencesKey("market_summary_enabled")
    private val marketSummaryAfterHoursKey = booleanPreferencesKey("market_summary_after_hours")
    private val marketSummaryMarketWideKey = booleanPreferencesKey("market_summary_market_wide")
    private val lastCloseSummaryKey = stringPreferencesKey("last_close_summary_date")
    private val lastAfterHoursSummaryKey = stringPreferencesKey("last_after_hours_summary_date")
    private val aiDailyBriefEnabledKey = booleanPreferencesKey("ai_daily_brief_enabled")
    private val lastDailyBriefKey = stringPreferencesKey("last_daily_brief_date")

    /** The versionName the user was last SHOWN the changelog for. Absent on a fresh install, which
     *  is how "first run" is told apart from "just upgraded" — a new user gets no release notes. */
    private val lastSeenVersionKey = stringPreferencesKey("last_seen_version")

    /** The release version the user last chose "Later" for in the update dialog. Compared against
     *  the latest available release so a cold start doesn't re-nag about the same one every launch;
     *  see [dismissedUpdateVersion]. */
    private val dismissedUpdateVersionKey = stringPreferencesKey("dismissed_update_version")
    private val lastSandboxTradeTsKey = doublePreferencesKey("last_sandbox_trade_ts")
    private val lastBackgroundRunKey = longPreferencesKey("last_background_run_at")
    private val lastBackgroundFailuresKey = stringPreferencesKey("last_background_failures")

    /** Base URL of the self-hosted Signals analyst service (empty = the AI analyst card is off). */
    val signalsApiUrl: Flow<String> = context.dataStore.data.map { it[signalsApiUrlKey] ?: "" }
    val signalsApiToken: Flow<String> = context.dataStore.data.map { it[signalsApiTokenKey] ?: "" }

    /**
     * This install's stable OPS-3 id — see [InstallId] for what it is and isn't. Generated on first
     * call and persisted from then on, so every later call (this session or after a restart) returns
     * the same value; sent as `client_id` on every watchlist sync in [SignalScanNotifier][com.stocktracker.app.notify.SignalScanNotifier].
     */
    suspend fun installId(): String {
        val current = context.dataStore.data.map { it[installIdKey] }.first()
        if (!current.isNullOrBlank()) return current
        val fresh = InstallId.resolve(null)
        context.dataStore.edit { prefs ->
            // DataStore serializes concurrent edit() calls, but another caller may have already run
            // this same read-then-write between our read above and this block executing — never
            // clobber an id that's already there.
            if (prefs[installIdKey].isNullOrBlank()) prefs[installIdKey] = fresh
        }
        return context.dataStore.data.map { it[installIdKey] }.first()!!
    }

    /**
     * When the background worker last completed, and which of its steps failed (comma-separated,
     * empty = all clean).
     *
     * Exists so "are my price alerts even running?" is answerable from inside the app. The worker
     * used to chain every notifier to the first failure and log nothing, so a permanently broken
     * step was indistinguishable from a quiet market — you would believe alerts were armed when
     * nothing had evaluated them for weeks.
     */
    val lastBackgroundRunAt: Flow<Long> = context.dataStore.data.map { it[lastBackgroundRunKey] ?: 0L }
    val lastBackgroundFailures: Flow<String> =
        context.dataStore.data.map { it[lastBackgroundFailuresKey] ?: "" }

    suspend fun recordBackgroundRun(atMs: Long, failures: String) = context.dataStore.edit {
        it[lastBackgroundRunKey] = atMs
        it[lastBackgroundFailuresKey] = failures
    }

    /** epoch-seconds of the last nightly scan we already notified about (dedup across worker runs). */
    val lastScanNotifiedAt: Flow<Long> = context.dataStore.data.map { it[lastScanNotifiedKey] ?: 0L }
    suspend fun setLastScanNotifiedAt(value: Long) = context.dataStore.edit { it[lastScanNotifiedKey] = value }

    /** Free cash the user considers investable (drives the Ideas screen + entry plans). 0 = unset. */
    val investableCash: Flow<Double> = context.dataStore.data.map { it[investableCashKey] ?: 0.0 }

    val lastSeenVersion: Flow<String?> = context.dataStore.data.map { it[lastSeenVersionKey] }
    suspend fun setLastSeenVersion(v: String) = context.dataStore.edit { it[lastSeenVersionKey] = v }

    /** null = nothing ever dismissed. See [UpdateChecker.shouldPrompt][com.stocktracker.app.update.UpdateChecker.shouldPrompt]
     *  for how this suppresses only that exact version, not updates in general. */
    val dismissedUpdateVersion: Flow<String?> = context.dataStore.data.map { it[dismissedUpdateVersionKey] }
    suspend fun setDismissedUpdateVersion(version: String) =
        context.dataStore.edit { it[dismissedUpdateVersionKey] = version }
    suspend fun setInvestableCash(amount: Double) = context.dataStore.edit {
        it[investableCashKey] = amount.coerceAtLeast(0.0)
    }

    /** Master switch for app-initiated AI calls (verdicts, plans, ideas) — off saves token cost
     *  without losing the configured service URL. The server's nightly scan is unaffected. */
    val aiAnalystEnabled: Flow<Boolean> = context.dataStore.data.map { it[aiAnalystEnabledKey] ?: true }
    suspend fun setAiAnalystEnabled(enabled: Boolean) = context.dataStore.edit { it[aiAnalystEnabledKey] = enabled }

    /**
     * MONEY-1: is the portfolio being rebalanced a TAXABLE brokerage account? Default true, so an
     * install that has never touched this screen still gets the capital-gains weighing the backend
     * has always been able to do — the alternative default (off) would silently hide it from most
     * people, who ARE in a taxable account. Off for an IRA/401(k)/etc., where holding period is
     * meaningless and the rebalance dialog must not pretend it matters.
     */
    val taxableAccount: Flow<Boolean> = context.dataStore.data.map { it[taxableAccountKey] ?: true }
    suspend fun setTaxableAccount(taxable: Boolean) = context.dataStore.edit { it[taxableAccountKey] = taxable }

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

    /** Master switch for the AI morning brief (AIE-3). Off by default — it's an LLM call, so opt-in;
     *  it also needs a Signals URL and the AI analyst switch on to actually fire. */
    val aiDailyBriefEnabled: Flow<Boolean> = context.dataStore.data.map { it[aiDailyBriefEnabledKey] ?: false }
    suspend fun setAiDailyBriefEnabled(enabled: Boolean) =
        context.dataStore.edit { it[aiDailyBriefEnabledKey] = enabled }

    /** ET date (yyyy-MM-dd) the morning brief last fired; "" = never. Dedups it to once per trading day. */
    val lastDailyBriefDate: Flow<String> = context.dataStore.data.map { it[lastDailyBriefKey] ?: "" }
    suspend fun setLastDailyBriefDate(date: String) = context.dataStore.edit { it[lastDailyBriefKey] = date }

    /** Epoch-seconds watermark of the newest sandbox trade already notified about (0 = none yet). Only
     *  fills newer than this produce a notification, so the 15-min worker never re-announces old trades. */
    val lastSandboxTradeTs: Flow<Double> = context.dataStore.data.map { it[lastSandboxTradeTsKey] ?: 0.0 }
    suspend fun setLastSandboxTradeTs(ts: Double) =
        context.dataStore.edit { it[lastSandboxTradeTsKey] = ts }

    /** User-entered Finnhub key (empty = fall back to the build-time BuildConfig key). */
    val finnhubApiKey: Flow<String> = context.dataStore.data.map { it[finnhubKeyKey] ?: "" }

    /** When true, whole-dollar prices are shown without a trailing ".00". */
    val hideZeroCents: Flow<Boolean> = context.dataStore.data.map { it[hideZeroCentsKey] ?: false }

    /**
     * Sort the watchlist into sector verticals with favourites pinned on top.
     *
     * Defaults ON. Off restores one flat list in the user's own stored order, which is the only mode
     * where drag-to-reorder means anything -- grouped, the displayed order is derived from sector and
     * a drag would have nowhere to land.
     */
    val watchlistGroupBySector: Flow<Boolean> = context.dataStore.data.map { it[groupBySectorKey] ?: true }

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

    /**
     * When true, the dashboard shows the five-leg market gate (SWT-13). Defaults ON: it is free
     * arithmetic over prices the app already pulls — no model call, no key — and it is the only
     * card that answers the market question with numbers you can check.
     */
    /**
     * Whether the watchlist's market-context strip is expanded.
     *
     * This was `remember`, so it collapsed on every cold start. The strip is where the dip radar,
     * the gate and the VIX gauge live; a user who opened it yesterday had to rediscover that it
     * opens at all, and two of those screens had no other entrance.
     */
    val contextStripOpen: Flow<Boolean> = context.dataStore.data.map { it[contextOpenKey] ?: false }

    suspend fun setContextStripOpen(v: Boolean) = context.dataStore.edit { it[contextOpenKey] = v }

    val showGate: Flow<Boolean> = context.dataStore.data.map { it[showGateKey] ?: true }

    /** User-defined watchlist names (in display order). Empty = only the built-in All/Stocks/Crypto. */
    val watchlistGroups: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[watchlistGroupsKey]?.let { runCatching { Http.json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: emptyList()
    }



    val defaultRefreshMinutes: Flow<Int> = context.dataStore.data.map { it[refreshKey] ?: 15 }

    /** Card colour behind every home-screen widget. See [WidgetBackground] for why the palette is all dark. */
    val widgetBackgroundArgb: Flow<Long> = context.dataStore.data.map {
        it[widgetBgArgbKey] ?: WidgetBackground.DEFAULT_ARGB
    }

    /**
     * How much of the wallpaper shows through that card, as a percentage: 0 is solid, 100 invisible.
     *
     * Stored in the same sense the slider shows, so the stored number and the displayed number are
     * the same number. The conversion to an alpha channel happens once, in [WidgetBackground].
     */
    val widgetBackgroundTransparency: Flow<Int> = context.dataStore.data.map {
        (it[widgetBgTransparencyKey] ?: WidgetBackground.DEFAULT_TRANSPARENCY).coerceIn(0, 100)
    }

    suspend fun setDefaultRefreshMinutes(minutes: Int) = context.dataStore.edit { it[refreshKey] = minutes }

    suspend fun setWidgetBackgroundArgb(argb: Long) = context.dataStore.edit { it[widgetBgArgbKey] = argb }

    suspend fun setWidgetBackgroundTransparency(pct: Int) =
        context.dataStore.edit { it[widgetBgTransparencyKey] = pct.coerceIn(0, 100) }
    suspend fun setFinnhubApiKey(key: String) = context.dataStore.edit { it[finnhubKeyKey] = key.trim() }
    suspend fun setHideZeroCents(enabled: Boolean) = context.dataStore.edit { it[hideZeroCentsKey] = enabled }

    suspend fun setWatchlistGroupBySector(enabled: Boolean) =
        context.dataStore.edit { it[groupBySectorKey] = enabled }

    /**
     * Sector sections the user has collapsed, by heading.
     *
     * Stored as the COLLAPSED set rather than the expanded one so a sector that appears for the
     * first time -- a new ticker in a sector nothing was held in -- opens by default. Recording
     * expansions instead would hide every new section until it was found and opened, which is the
     * one state a user cannot discover by scrolling.
     */
    val collapsedVerticals: Flow<Set<String>> =
        context.dataStore.data.map { it[collapsedVerticalsKey] ?: emptySet() }

    suspend fun toggleCollapsedVertical(name: String) = context.dataStore.edit { prefs ->
        val cur = prefs[collapsedVerticalsKey] ?: emptySet()
        prefs[collapsedVerticalsKey] = if (name in cur) cur - name else cur + name
    }
    suspend fun setShowExtendedHours(enabled: Boolean) = context.dataStore.edit { it[extendedHoursKey] = enabled }
    suspend fun setShowMarketStatus(enabled: Boolean) = context.dataStore.edit { it[marketStatusKey] = enabled }
    suspend fun setShowVolume(enabled: Boolean) = context.dataStore.edit { it[showVolumeKey] = enabled }
    /** Draw the price series as candles rather than the close line. Off by default: candles are only
     *  legible on the longer ranges, and the close line is what every other surface shows. */
    val chartCandles: Flow<Boolean> = context.dataStore.data.map { it[chartCandlesKey] ?: false }

    suspend fun setChartCandles(on: Boolean) = context.dataStore.edit { it[chartCandlesKey] = on }

    /** Map price logarithmically, so equal vertical distances are equal percentage moves. Off by
     *  default: it only changes the reading on multi-year ranges, and on most single-stock windows a
     *  log axis is visually indistinguishable from a linear one. */
    val chartLogScale: Flow<Boolean> = context.dataStore.data.map { it[chartLogScaleKey] ?: false }

    suspend fun setChartLogScale(on: Boolean) = context.dataStore.edit { it[chartLogScaleKey] = on }

    suspend fun setChartIndicators(keys: Set<String>) = context.dataStore.edit {
        it[chartIndicatorsKey] = Http.json.encodeToString(keys.toList())
    }
    suspend fun setShowVix(enabled: Boolean) = context.dataStore.edit { it[showVixKey] = enabled }
    suspend fun setShowGate(enabled: Boolean) = context.dataStore.edit { it[showGateKey] = enabled }
    suspend fun setWatchlistGroups(groups: List<String>) = context.dataStore.edit {
        it[watchlistGroupsKey] = Http.json.encodeToString(groups)
    }
    suspend fun setSignalsApiUrl(url: String) = context.dataStore.edit { it[signalsApiUrlKey] = url.trim().trimEnd('/') }

    suspend fun setSignalsApiToken(token: String) =
        context.dataStore.edit { it[signalsApiTokenKey] = token.trim() }

    // --- Daily Pick (DP-7 / DP-10 / DP-14) ---

    private val dailyPickNotifyKey = booleanPreferencesKey("daily_pick_notify_enabled")
    private val dailyPickAlertsKey = booleanPreferencesKey("daily_pick_alerts_enabled")
    private val dailyPickCollapsedKey = booleanPreferencesKey("daily_pick_card_collapsed")
    private val lastDailyPickNotifyKey = stringPreferencesKey("last_daily_pick_notify_date")
    private val dailyPickAlertLogKey = stringSetPreferencesKey("daily_pick_alert_log")
    private val dailyPickReportCardsKey = stringSetPreferencesKey("daily_pick_report_cards")

    /** Morning "today's pick" notification. ON by default (decided 2026-09-22) — unlike the AI brief,
     *  it costs no call of its own: the backend made the pick once, and this only reads it. */
    val dailyPickNotifyEnabled: Flow<Boolean> = context.dataStore.data.map { it[dailyPickNotifyKey] ?: true }
    suspend fun setDailyPickNotifyEnabled(enabled: Boolean) =
        context.dataStore.edit { it[dailyPickNotifyKey] = enabled }

    /** Intraday price alerts on the pick (entered zone / ran past it / stop / target). ON by default. */
    val dailyPickAlertsEnabled: Flow<Boolean> = context.dataStore.data.map { it[dailyPickAlertsKey] ?: true }
    suspend fun setDailyPickAlertsEnabled(enabled: Boolean) =
        context.dataStore.edit { it[dailyPickAlertsKey] = enabled }

    val dailyPickCardCollapsed: Flow<Boolean> = context.dataStore.data.map { it[dailyPickCollapsedKey] ?: false }
    suspend fun setDailyPickCardCollapsed(collapsed: Boolean) =
        context.dataStore.edit { it[dailyPickCollapsedKey] = collapsed }

    /** ET date the morning pick notification last went out; "" = never. */
    val lastDailyPickNotifyDate: Flow<String> = context.dataStore.data.map { it[lastDailyPickNotifyKey] ?: "" }
    suspend fun setLastDailyPickNotifyDate(date: String) =
        context.dataStore.edit { it[lastDailyPickNotifyKey] = date }

    /** "date|SYMBOL|state" for every intraday alert already sent. Pruned to the last few days on write. */
    val dailyPickAlertLog: Flow<Set<String>> = context.dataStore.data.map { it[dailyPickAlertLogKey] ?: emptySet() }
    suspend fun setDailyPickAlertLog(entries: Set<String>) =
        context.dataStore.edit { it[dailyPickAlertLogKey] = entries }

    /** "date|horizon" for every report card (DP-14) already delivered. */
    val dailyPickReportCards: Flow<Set<String>> = context.dataStore.data.map { it[dailyPickReportCardsKey] ?: emptySet() }
    suspend fun setDailyPickReportCards(entries: Set<String>) =
        context.dataStore.edit { it[dailyPickReportCardsKey] = entries }

    // --- Weekly & monthly report (RPT-1) ---

    private val reportWeeklyNotifyKey = booleanPreferencesKey("report_weekly_notify_enabled")
    private val reportMonthlyNotifyKey = booleanPreferencesKey("report_monthly_notify_enabled")
    private val reportNotifiedKey = stringSetPreferencesKey("report_notified_ids")
    private val reportPortfolioKey = stringPreferencesKey("report_portfolio_snapshots")

    /** Friday-after-the-close "week in review" alert. ON by default: the user asked for it, and it
     *  costs nothing extra — the backend builds the report once and this only reads it. */
    val reportWeeklyNotifyEnabled: Flow<Boolean> = context.dataStore.data.map { it[reportWeeklyNotifyKey] ?: true }
    suspend fun setReportWeeklyNotifyEnabled(enabled: Boolean) =
        context.dataStore.edit { it[reportWeeklyNotifyKey] = enabled }

    /** Month-end "month in review" alert. ON by default, for the same reasons. */
    val reportMonthlyNotifyEnabled: Flow<Boolean> = context.dataStore.data.map { it[reportMonthlyNotifyKey] ?: true }
    suspend fun setReportMonthlyNotifyEnabled(enabled: Boolean) =
        context.dataStore.edit { it[reportMonthlyNotifyKey] = enabled }

    /** Report ids already announced (or deliberately skipped as too old to announce). */
    val reportNotifiedIds: Flow<Set<String>> = context.dataStore.data.map { it[reportNotifiedKey] ?: emptySet() }
    suspend fun setReportNotifiedIds(ids: Set<String>) =
        context.dataStore.edit { it[reportNotifiedKey] = ids }

    /**
     * The portfolio section of each report, as computed on this phone, keyed by report id (JSON).
     *
     * Stored, not recomputed on every open, so a report keeps saying what the portfolio did THAT week
     * even after the holdings change. Written and read only by [com.stocktracker.app.ui.report.ReportPortfolioStore].
     */
    val reportPortfolioSnapshots: Flow<String> = context.dataStore.data.map { it[reportPortfolioKey] ?: "" }
    suspend fun setReportPortfolioSnapshots(json: String) =
        context.dataStore.edit { it[reportPortfolioKey] = json }

    // --- Raw accessors for com.stocktracker.app.data.BackupManager only ---
    //
    // A backup restore touches [watchlistGroups] and [investableCash] alongside four other stores'
    // keys in ONE atomic DataStore transaction, and takes a raw pre-import snapshot so a bad import
    // can be undone exactly. Both need the literal bytes/value on disk, not the decoded (and lossily
    // defaulted) Flow above.

    internal fun rawWatchlistGroups(prefs: Preferences): String? = prefs[watchlistGroupsKey]

    internal fun writeRawWatchlistGroups(prefs: MutablePreferences, raw: String?) {
        if (raw == null) prefs.remove(watchlistGroupsKey) else prefs[watchlistGroupsKey] = raw
    }

    internal fun rawInvestableCash(prefs: Preferences): Double? = prefs[investableCashKey]

    internal fun writeRawInvestableCash(prefs: MutablePreferences, raw: Double?) {
        if (raw == null) prefs.remove(investableCashKey) else prefs[investableCashKey] = raw
    }
}
