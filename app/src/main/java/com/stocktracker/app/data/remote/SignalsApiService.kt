package com.stocktracker.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Client for the self-hosted Tier-2 "signals" analyst service (see ~/stocktracker-signals). It
 * returns a Claude-authored buy/sell verdict for a symbol. Optional — only used when the user has
 * set a base URL in Settings. Decision support only, not advice.
 */
class SignalsApiService {

    /**
     * Every Signals request goes through [sGet]/[sPost] so reachability is tracked in ONE place —
     * [SignalsHealth] can then drive a single "backend unreachable" banner instead of each screen
     * inventing its own message. Deliberately NOT hooked into [Http] itself: that client is shared with
     * Yahoo/Finnhub/CoinGecko, and their outages say nothing about the self-hosted service.
     */
    // Health is reported at COMPLETION, never from a start timestamp: the two are not comparable, and
    // ordering a slow call's failure by when it BEGAN discarded evidence that was actually the newest
    // (a 240s analyst call dying is later news than a 1s call that succeeded while it was in flight).
    private suspend fun sGet(url: String, slow: Boolean = false): String {
        if (slow) SignalsHealth.slowCallsInFlight.incrementAndGet()
        try {
            return Http.getString(url, slow).also { SignalsHealth.reportSuccess() }
        } catch (e: Throwable) {
            SignalsHealth.reportFailure(e); throw e
        } finally {
            if (slow) SignalsHealth.slowCallsInFlight.decrementAndGet()
        }
    }

    private suspend fun sPost(url: String, body: String, slow: Boolean = false): String {
        if (slow) SignalsHealth.slowCallsInFlight.incrementAndGet()
        try {
            return Http.postJson(url, body, slow).also { SignalsHealth.reportSuccess() }
        } catch (e: Throwable) {
            SignalsHealth.reportFailure(e); throw e
        } finally {
            if (slow) SignalsHealth.slowCallsInFlight.decrementAndGet()
        }
    }

    /**
     * @param baseUrl e.g. "http://your-host:8000"; blank returns null (feature off).
     * @param deep    true asks the backend for the deep (Opus) model instead of the cheap scan.
     */
    suspend fun verdict(
        baseUrl: String,
        symbol: String,
        crypto: Boolean,
        deep: Boolean = false,
        shares: Double? = null,
        avgCost: Double? = null,
        ruleScore: Int? = null,
        refresh: Boolean = false,
    ): AiSignalResponse? {
        if (baseUrl.isBlank()) return null
        // The backend fetches via Yahoo, whose crypto symbols take a -USD suffix.
        val sym = if (crypto) "${symbol.uppercase()}-USD" else symbol.uppercase()
        // When the user holds this asset, pass the position so the verdict is framed add/hold/trim.
        val pos = if (shares != null && avgCost != null && shares > 0 && avgCost > 0) {
            "&shares=$shares&avg_cost=$avgCost"
        } else {
            ""
        }
        // The on-device rule score rides along so the analyst reconciles a diverging read.
        val rs = ruleScore?.let { "&rule_score=$it" } ?: ""
        val rf = if (refresh) "&refresh=true" else ""
        val url = "${baseUrl.trimEnd('/')}/signal/$sym?crypto=$crypto&deep=$deep$pos$rs$rf"
        val body = sGet(url, slow = true) // LLM latency, not a quote endpoint
        return Http.json.decodeFromString<AiSignalResponse>(body)
    }

    /** The latest nightly-scan result (for the flip-notification check). */
    suspend fun latestScan(baseUrl: String): ScanLatest? {
        if (baseUrl.isBlank()) return null
        val body = sGet("${baseUrl.trimEnd('/')}/scan/latest")
        return Http.json.decodeFromString<ScanLatest>(body)
    }

    /** Push the app's watchlist up so the backend's nightly scan tracks what the user tracks. */
    suspend fun syncWatchlist(baseUrl: String, stocks: List<String>, cryptos: List<String>) {
        if (baseUrl.isBlank()) return
        sPost("${baseUrl.trimEnd('/')}/api/settings", Http.json.encodeToString(WatchlistSync(stocks, cryptos)))
    }

    /** Short-pressure read (FINRA SI + short volume + SEC FTDs) — free, no LLM call. Stocks only. */
    suspend fun shortPressure(baseUrl: String, symbol: String): ShortPressureResponse? {
        if (baseUrl.isBlank()) return null
        val body = sGet("${baseUrl.trimEnd('/')}/shorts/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<ShortPressureResponse>(body)
    }

    /** Daily history fallback (Yahoo → Webull on the server) for symbols the app's own Yahoo fetch
     *  can't chart — e.g. warrants/OTC. Returns bars + the source used, or null. */
    suspend fun history(baseUrl: String, symbol: String): HistoryResponse? {
        if (baseUrl.isBlank()) return null
        val body = sGet("${baseUrl.trimEnd('/')}/history/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<HistoryResponse>(body)
    }

    /** Crypto long-term context: halving-cycle position (BTC), multi-year trend, halving dates. */
    suspend fun cycleInfo(baseUrl: String, symbol: String): CycleResponse? {
        if (baseUrl.isBlank()) return null
        val sym = "${symbol.uppercase()}-USD"
        val body = sGet("${baseUrl.trimEnd('/')}/cycle/$sym", slow = true)
        return Http.json.decodeFromString<CycleResponse>(body)
    }

    /** Below-the-200-week-line trend for a STOCK — the equity mirror of the crypto cycle card:
     *  200w SMA, %-from-line, below-line zone, recovering/deepening direction, 14-week RSI. Free, no
     *  LLM. Null (404) for names with under ~4 years of weekly history. */
    suspend fun trend(baseUrl: String, symbol: String): TrendResponse? {
        if (baseUrl.isBlank()) return null
        val body = sGet("${baseUrl.trimEnd('/')}/trend/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<TrendResponse>(body)
    }

    /** Tile data for the heat map. FREE (no LLM). `mode` is "market" (area = market cap, colour =
     *  today's move) or "signals" (area = distance below the 52-week high, colour = the dip tier).
     *  The server returns VALUES only — the squarified layout runs on-device against the real
     *  viewport. */
    suspend fun heatmap(baseUrl: String, mode: String = "market", limit: Int = 80,
                        refresh: Boolean = false): HeatmapResponse? {
        if (baseUrl.isBlank()) return null
        val body = sGet("${baseUrl.trimEnd('/')}/heatmap?mode=$mode&limit=$limit&refresh=$refresh",
                        slow = true)
        return Http.json.decodeFromString<HeatmapResponse>(body)
    }

    /** Settings changelog for an arm, newest first. FREE (no LLM). */
    suspend fun sandboxChanges(baseUrl: String, limit: Int = 50, arm: String = "main"): List<SandboxChange>? {
        if (baseUrl.isBlank()) return null
        val body = sGet("${baseUrl.trimEnd('/')}/sandbox/changes?limit=$limit&arm=$arm")
        return Http.json.decodeFromString<SandboxChangesResponse>(body).changes
    }

    /** Sector + industry per ticker, for grouping the watchlist into verticals. FREE (no LLM).
     *
     *  A symbol the server could not classify comes back PRESENT with a null sector; a symbol
     *  missing from the map was never looked up. The caller must keep those apart — the first is
     *  legitimately "Other", the second is a gap it should retry rather than label. */
    suspend fun sectors(baseUrl: String, symbols: List<String>): Map<String, SectorProfile>? {
        if (baseUrl.isBlank() || symbols.isEmpty()) return null
        val q = symbols.joinToString(",") { it.uppercase() }
        val body = sGet("${baseUrl.trimEnd('/')}/sectors?symbols=$q")
        return Http.json.decodeFromString<SectorsResponse>(body).sectors
    }

    /** Theme C — where insiders and members of Congress have been BUYING across the watchlist.
     *  FREE (no LLM). Corroborating context, not a buy signal: insider buys are disclosed within two
     *  business days, congressional filings lag up to ~45 days and report amount ranges. */
    suspend fun smartMoney(baseUrl: String, limit: Int = 15, refresh: Boolean = false): SmartMoneyResponse? {
        if (baseUrl.isBlank()) return null
        val body = sGet("${baseUrl.trimEnd('/')}/smart_money?limit=$limit&refresh=$refresh", slow = true)
        return Http.json.decodeFromString<SmartMoneyResponse>(body)
    }

    /** MB-17 — discount or deteriorating? Reasons over FCF trend, share count, debt/ROE, insider
     *  buying and the 200-week direction for one name. FREE (no LLM). Evidence, not a
     *  recommendation: an "unclear" with a non-empty `missing` means the data was unavailable, NOT
     *  that the business looks fine. */
    suspend fun valueTrap(baseUrl: String, symbol: String): ValueTrapResponse? {
        if (baseUrl.isBlank()) return null
        val body = sGet("${baseUrl.trimEnd('/')}/valuetrap/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<ValueTrapResponse>(body)
    }

    /** MB-15/MB-18 — the 200-week value screen: a value-tilted universe ranked by how far below its
     *  own 200-week trend each name sits. FREE (no LLM), so it works with the AI analyst switched
     *  off. Context, not a buy signal — the payload carries that caveat and so must the UI. */
    suspend fun valueScreen(
        baseUrl: String, limit: Int = 15, belowLineOnly: Boolean = true, refresh: Boolean = false,
    ): ValueScreenResponse? {
        if (baseUrl.isBlank()) return null
        val body = sGet(
            "${baseUrl.trimEnd('/')}/screener/value?limit=$limit" +
                "&below_line_only=$belowLineOnly&refresh=$refresh",
            slow = true,
        )
        return Http.json.decodeFromString<ValueScreenResponse>(body)
    }

    /** Historical 200-week-line touch study — forward 12/24-month returns after past dips below the
     *  line, vs the S&P 500. Evidence context, not a signal. Free, no LLM. Null (404) if too new. */
    suspend fun touchStudy(baseUrl: String, symbol: String): TouchStudyResponse? {
        if (baseUrl.isBlank()) return null
        val body = sGet("${baseUrl.trimEnd('/')}/touches/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<TouchStudyResponse>(body)
    }

    /** Insider buying (SEC Form 4 via Finnhub) — open-market purchases in the last 12 months, the
     *  bullish smart-money read. Free (needs a Finnhub key on the service). Null on 404/no-key. */
    suspend fun insider(baseUrl: String, symbol: String): InsiderResponse? {
        if (baseUrl.isBlank()) return null
        val body = sGet("${baseUrl.trimEnd('/')}/insider/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<InsiderResponse>(body)
    }

    /** Congressional / political trades in a stock over the last 12 months (House+Senate+cabinet, from
     *  the free kadoa dataset). Free, no LLM. Lagging (~45-day STOCK Act window) — weak, debated
     *  "smart money" context, never a signal. Returns null on any failure or when nobody traded it. */
    suspend fun congress(baseUrl: String, symbol: String): CongressBlock? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val body = sGet("${baseUrl.trimEnd('/')}/congress/${symbol.uppercase()}", slow = true)
            Http.json.decodeFromString<CongressResponse>(body).congress
        }.getOrNull()
    }

    /** Per-calendar-month seasonal price action (~10y): avg return + hit rate per month, current-month
     *  tendency, best/worst months. Free, no LLM. Weak, sample-limited context. Null under ~2y history. */
    suspend fun seasonality(baseUrl: String, symbol: String): SeasonalityBlock? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val body = sGet("${baseUrl.trimEnd('/')}/seasonality/${symbol.uppercase()}", slow = true)
            Http.json.decodeFromString<SeasonalityResponse>(body).seasonality
        }.getOrNull()
    }

    /** Theme C — a concrete, sized rebalance plan: sell/buy N shares to bring the book under
     *  [maxPositionPct] and redeploy proceeds + cash into the best-setup holdings. Runs the analyst →
     *  gate on the AI switch. Crypto holdings must be sent as <SYM>-USD. Null on a blank URL / no holdings. */
    suspend fun rebalance(
        baseUrl: String, cash: Double, maxPositionPct: Int, holdings: List<HoldingSync>, deep: Boolean = false,
        refresh: Boolean = false,
    ): RebalanceResponse? {
        if (baseUrl.isBlank() || holdings.isEmpty()) return null
        val body = Http.json.encodeToString(
            RebalanceRequestBody(cash, deep, refresh, maxPositionPct.toDouble(), holdings),
        )
        return Http.json.decodeFromString<RebalanceResponse>(
            sPost("${baseUrl.trimEnd('/')}/portfolio/rebalance", body, slow = true),
        )
    }

    /** AIE-4 — "why it moved": the stock's notable recent daily moves each correlated with a dated
     *  headline (or flagged as no-catalyst), plus a one-line read. Runs the analyst → gate on the AI
     *  switch. Returns the full response (block may be null with a [note], e.g. crypto). Null on failure. */
    suspend fun newsMoves(
        baseUrl: String, symbol: String, deep: Boolean = false, refresh: Boolean = false,
    ): NewsMovesResponse? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val q = "?deep=$deep" + if (refresh) "&refresh=true" else ""
            val body = sGet("${baseUrl.trimEnd('/')}/news_moves/${symbol.uppercase()}$q", slow = true)
            Http.json.decodeFromString<NewsMovesResponse>(body)
        }.getOrNull()
    }

    /** Whole-portfolio AI review: overall health, concentration flags, a per-holding action list, and a
     *  cash-deployment note. POSTs the holdings (crypto must be sent as <SYM>-USD). Gate on the AI switch. */
    suspend fun portfolioReview(
        baseUrl: String, cash: Double, holdings: List<HoldingSync>, deep: Boolean = false,
        refresh: Boolean = false,
    ): PortfolioReviewResponse? {
        if (baseUrl.isBlank() || holdings.isEmpty()) return null
        val body = Http.json.encodeToString(PortfolioReviewRequest(cash, deep, refresh, holdings))
        return Http.json.decodeFromString<PortfolioReviewResponse>(
            sPost("${baseUrl.trimEnd('/')}/portfolio/review", body, slow = true),
        )
    }

    /** Quality tags (Finnhub basic-financials) — ROE/margins/D-E + Buffett/wide-moat/aristocrat flags.
     *  Stance-neutral business descriptors. Free. Null on 404. */
    suspend fun quality(baseUrl: String, symbol: String): QualityResponse? {
        if (baseUrl.isBlank()) return null
        val body = sGet("${baseUrl.trimEnd('/')}/quality/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<QualityResponse>(body)
    }

    /** Whole-market top movers (biggest gainers + losers on the day) for the market-close recap. Free,
     *  no LLM. Returns null on any failure (blank URL, network, parse) so the caller can fall back to
     *  the watchlist; on the backend's own failure the lists come back empty. */
    suspend fun movers(baseUrl: String, count: Int = 6): MoversResponse? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val body = sGet("${baseUrl.trimEnd('/')}/movers?count=$count")
            Http.json.decodeFromString<MoversResponse>(body)
        }.getOrNull()
    }

    /**
     * AIE-5 — an instant AI overview of what the markets are doing RIGHT NOW: US session phase, the
     * major indices + VIX, sector rotation, and the user's watchlist movers, narrated by the analyst.
     * One LLM call (the server caches it ~3 min, so repeated taps are instant). Gate on the AI switch.
     * [deep]=true asks for the Opus read (slower, richer). Returns null on a blank URL.
     */
    suspend fun marketNow(baseUrl: String, deep: Boolean = false): MarketNowResponse? {
        if (baseUrl.isBlank()) return null
        val d = if (deep) "?deep=true" else ""
        val body = sGet("${baseUrl.trimEnd('/')}/market_now$d", slow = true) // LLM latency
        return Http.json.decodeFromString<MarketNowResponse>(body)
    }

    /**
     * AIE-3 — the AI morning brief: a push-ready title + 2-3 sentences + tone, from the same live
     * snapshot [marketNow] uses plus today's watchlist catalysts. Server-side watchlist; cached ~30 min.
     * Runs the analyst, so gate on the AI switch. Returns null on a blank URL / any failure so the
     * notifier just skips this morning rather than erroring.
     */
    suspend fun dailyBrief(baseUrl: String, deep: Boolean = false): DailyBriefResponse? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val d = if (deep) "?deep=true" else ""
            val body = sGet("${baseUrl.trimEnd('/')}/daily_brief$d", slow = true) // LLM latency
            Http.json.decodeFromString<DailyBriefResponse>(body)
        }.getOrNull()
    }

    // ---- AI Sandbox (autonomous paper trader) ----

    /** Live-marked sandbox state (cash, positions, equity, vs-benchmark, settings, strategy note). */
    suspend fun sandboxState(baseUrl: String, arm: String = "main"): SandboxState? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            Http.json.decodeFromString<SandboxState>(
                sGet("${baseUrl.trimEnd('/')}/sandbox/state?arm=$arm", slow = true))
        }.getOrNull()
    }

    /** Every comparison arm with a scoreboard. Empty list (not null) is a real answer — a server
     *  without the arms endpoint yet. Null means the call failed. */
    suspend fun sandboxArms(baseUrl: String): List<SandboxArm>? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            Http.json.decodeFromString<SandboxArmsResponse>(
                sGet("${baseUrl.trimEnd('/')}/sandbox/arms", slow = true)).arms
        }.getOrNull()
    }

    /** Every arm's equity curve on one shared date axis, for charting them against each other. */
    suspend fun sandboxArmsNav(baseUrl: String, days: Int = 180): SandboxArmsNav? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            Http.json.decodeFromString<SandboxArmsNav>(
                sGet("${baseUrl.trimEnd('/')}/sandbox/arms/nav?days=$days", slow = true))
        }.getOrNull()
    }

    /** The equity-curve series (NAV + benchmark) for the value chart. */
    /** NULL when the call failed; an empty list genuinely means "no points yet". Collapsing the two
     *  made a failed fetch render as an empty equity curve beside a stale, confident equity figure. */
    suspend fun sandboxNav(baseUrl: String, days: Int = 120, arm: String = "main"): List<SandboxNavPoint>? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            Http.json.decodeFromString<SandboxNavResponse>(
                sGet("${baseUrl.trimEnd('/')}/sandbox/nav?days=$days&arm=$arm")).series
        }.getOrNull()
    }

    /** The trade log (executed + skipped, newest first). */
    /** NULL when the call failed; empty means genuinely no trades — see [sandboxNav]. */
    suspend fun sandboxTrades(baseUrl: String, limit: Int = 100, arm: String = "main"): List<SandboxTrade>? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            Http.json.decodeFromString<SandboxTradesResponse>(
                sGet("${baseUrl.trimEnd('/')}/sandbox/trades?limit=$limit&arm=$arm")).trades
        }.getOrNull()
    }

    /**
     * Add (or withdraw, negative) fictional cash. Returns null on success, or the reason it failed.
     *
     * It used to collapse to a Boolean, so the UI could only ever say "Couldn't update funds" — while
     * the server returns the specific, actionable reason: a 422 "withdrawal exceeds cash on hand
     * ($X)" naming the real balance, or a 503 saying the benchmark couldn't be priced so the deposit
     * was deliberately not applied. Those are the only place the actual numbers appear.
     */
    suspend fun sandboxFund(baseUrl: String, amount: Double): String? {
        if (baseUrl.isBlank()) return "No Signals service URL is set"
        return runCatching {
            sPost("${baseUrl.trimEnd('/')}/sandbox/fund",
                Http.json.encodeToString(SandboxFundRequest(amount)))
            null
        }.getOrElse { e ->
            analystErrorDetail(e) ?: "Couldn't update funds"
        }
    }

    /** Patch the sandbox settings (risk, dates, caps, master switch). Returns the new settings. */
    suspend fun sandboxUpdateSettings(baseUrl: String, patch: SandboxSettingsPatch): SandboxSettings? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            Http.json.decodeFromString<SandboxSettings>(
                sPost("${baseUrl.trimEnd('/')}/sandbox/settings", Http.json.encodeToString(patch)))
        }.getOrNull()
    }

    /** Run one decision cycle now (manual "run a tick"). `force` bypasses the once-a-day + session gates. */
    suspend fun sandboxTick(baseUrl: String, force: Boolean = true): SandboxTickResult? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            Http.json.decodeFromString<SandboxTickResult>(
                sPost("${baseUrl.trimEnd('/')}/sandbox/tick",
                    Http.json.encodeToString(SandboxTickRequest(force = force, manual = true)), slow = true))
        }.getOrNull()
    }

    /** Wipe the sandbox back to fresh (rotates the logs). */
    suspend fun sandboxReset(baseUrl: String): Boolean {
        if (baseUrl.isBlank()) return false
        return runCatching {
            sPost("${baseUrl.trimEnd('/')}/sandbox/reset", Http.json.encodeToString(SandboxResetRequest(true)))
            true
        }.getOrDefault(false)
    }

    /** Theme D — the current market REGIME: a short label + trend + volatility + positioning note, from
     *  the market snapshot plus the S&P's 50/200-day structural trend. One market-wide LLM call, cached
     *  ~30 min server-side. Gate on the AI switch. Returns null on a blank URL / any failure. */
    suspend fun regime(baseUrl: String): RegimeResponse? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val body = sGet("${baseUrl.trimEnd('/')}/regime", slow = true) // LLM latency
            Http.json.decodeFromString<RegimeResponse>(body)
        }.getOrNull()
    }

    /** The macro / geopolitical backdrop (NEWS): market-moving events graded from a news wire —
     *  wars, sanctions, energy and shipping disruption, central-bank moves, tariffs. Free (a stored
     *  blob, no LLM call), refreshed server-side a few times a day.
     *
     *  Returns null on a blank URL or any failure — which the UI must render as "couldn't load",
     *  NOT as a calm backdrop. The payload draws the same distinction itself via `available`. */
    suspend fun macroCatalysts(baseUrl: String): MacroState? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            Http.json.decodeFromString<MacroState>(sGet("${baseUrl.trimEnd('/')}/macro/catalysts"))
        }.getOrNull()
    }

    /** How the AI's own past calls actually performed against the index. Free (a local DB read, no
     *  LLM), so it costs nothing to show. Returns null on a blank URL / any failure. */
    suspend fun memoryStats(baseUrl: String): MemoryStats? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            Http.json.decodeFromString<MemoryStats>(sGet("${baseUrl.trimEnd('/')}/memory/stats"))
        }.getOrNull()
    }

    /** Catalyst calendar (SI dates, OPEX, earnings). Whole watchlist by
     *  default; pass [symbol] for a single stock's calendar. Free. */
    suspend fun calendar(baseUrl: String, symbol: String? = null): CalendarResponse? {
        if (baseUrl.isBlank()) return null
        val q = symbol?.let { "?symbol=${it.uppercase()}" } ?: ""
        val body = sGet("${baseUrl.trimEnd('/')}/calendar$q", slow = true)
        return Http.json.decodeFromString<CalendarResponse>(body)
    }

    /**
     * Scenario: "if I deployed [cash] into this symbol" — one asset's entry plan. Optional
     * shares+avgCost tell the analyst the asset is already held (concentration awareness).
     */
    suspend fun planEntry(
        baseUrl: String,
        symbol: String,
        crypto: Boolean,
        cash: Double,
        deep: Boolean = false,
        shares: Double? = null,
        avgCost: Double? = null,
        refresh: Boolean = false,
    ): PlanResponse? {
        if (baseUrl.isBlank() || cash <= 0) return null
        val sym = if (crypto) "${symbol.uppercase()}-USD" else symbol.uppercase()
        val pos = if (shares != null && avgCost != null && shares > 0 && avgCost > 0) {
            "&shares=$shares&avg_cost=$avgCost"
        } else {
            ""
        }
        val rf = if (refresh) "&refresh=true" else ""
        val url = "${baseUrl.trimEnd('/')}/plan/$sym?cash=$cash&crypto=$crypto&deep=$deep$pos$rf"
        return Http.json.decodeFromString<PlanResponse>(sGet(url, slow = true))
    }

    /**
     * "Play with calls" suggester (OC-2): a beginner-friendly long-call structuring read for a STOCK.
     * Pure math on the server (Yahoo options chain + Black-Scholes) — NO LLM, so it works even with the
     * AI kill-switch off. [budget] is the max loss the user is OK with (caps the contract count);
     * [style] is safer | balanced | cheaper (which delta bucket to lead with). [deep]=true asks the
     * backend to attach an Opus-authored [analyst][OptionsResponse.analyst] paragraph (costs an LLM
     * call — gate on the AI kill-switch). Throws [HttpStatusException] (HTTP 400) for crypto / symbols
     * with no options chain — the caller surfaces [analystErrorDetail]. Quotes are ~15-min delayed and
     * stale outside market hours (quote_delayed).
     */
    suspend fun options(
        baseUrl: String,
        symbol: String,
        budget: Double,
        style: String,
        deep: Boolean = false,
    ): OptionsResponse? {
        if (baseUrl.isBlank()) return null
        val d = if (deep) "&deep=true" else ""
        val url = "${baseUrl.trimEnd('/')}/options/${symbol.uppercase()}?budget=$budget&style=$style$d"
        val body = sGet(url, slow = true) // chain fetch + greeks, not a quote endpoint
        return Http.json.decodeFromString<OptionsResponse>(body)
    }

    /**
     * "Get paid to buy" cash-secured put suggester (OC-8) — the acquire-shares-cheaply half of the
     * wheel. Pure server-side math (Yahoo chain + Black-Scholes), NO LLM, so it works even with the AI
     * kill-switch off. [cash] is what the user will set aside as collateral; [style] is
     * aggressive | balanced | conservative (aggressive = a strike closer to spot, so more premium and
     * more likely you're assigned the shares). Throws [HttpStatusException] (HTTP 400) for crypto /
     * symbols with no options chain — the caller surfaces [analystErrorDetail]. Quotes are ~15-min
     * delayed and stale outside market hours (quote_delayed).
     */
    suspend fun puts(baseUrl: String, symbol: String, cash: Double, style: String): PutsResponse? {
        if (baseUrl.isBlank()) return null
        val url = "${baseUrl.trimEnd('/')}/puts/${symbol.uppercase()}?cash=$cash&style=$style"
        val body = sGet(url, slow = true) // chain fetch + greeks, not a quote endpoint
        return Http.json.decodeFromString<PutsResponse>(body)
    }

    /**
     * "Sell covered calls" income suggester (OC-8) — the income-on-shares-you-hold half of the wheel.
     * Only valid at ≥100 shares (the server returns HTTP 400 below that, and for crypto / no chain).
     * [shares] comes from the user's holdings; [target] is an optional target sell price (null → the
     * server picks a ~0.30-delta strike). No LLM. Throws [HttpStatusException] (HTTP 400) so the caller
     * can surface [analystErrorDetail]. Quotes are ~15-min delayed (quote_delayed).
     */
    suspend fun coveredCall(
        baseUrl: String,
        symbol: String,
        shares: Int,
        target: Double? = null,
    ): CoveredCallResponse? {
        if (baseUrl.isBlank()) return null
        val t = target?.let { "&target=$it" } ?: ""
        val url = "${baseUrl.trimEnd('/')}/covered_call/${symbol.uppercase()}?shares=$shares$t"
        val body = sGet(url, slow = true) // chain fetch + greeks, not a quote endpoint
        return Http.json.decodeFromString<CoveredCallResponse>(body)
    }

    /**
     * Re-price ONE specific option contract for the manual call tracker (OC-3). GET
     * /option_quote/{SYMBOL}?expiry_ts=&strike=&type=call — the live (~15-min delayed) quote used to
     * show a tracked position's unrealized P/L. Uses the slow client (the server fetches the chain).
     * Returns null on HTTP 404 (contract not found — e.g. expired/rolled) and HTTP 400 (crypto / no
     * options chain) so the list degrades to a "—" instead of crashing; other failures propagate so
     * the caller can tell a transient miss (retry / show last-known) from a genuine "gone".
     */
    suspend fun optionQuote(
        baseUrl: String,
        symbol: String,
        expiryTs: Long,
        strike: Double,
        type: String = "call",
    ): OptionQuoteResponse? {
        if (baseUrl.isBlank()) return null
        val url = "${baseUrl.trimEnd('/')}/option_quote/${symbol.uppercase()}" +
            "?expiry_ts=$expiryTs&strike=$strike&type=$type"
        val body = try {
            sGet(url, slow = true) // chain fetch + greeks, not a quote endpoint
        } catch (e: HttpStatusException) {
            if (e.code == 404 || e.code == 400) return null
            throw e
        }
        return Http.json.decodeFromString<OptionQuoteResponse>(body)
    }

    /**
     * Rank the synced watchlist for NEW money: top 2-4 picks with the cash spread across them.
     * Holdings ride along transiently so the analyst can weigh existing exposure — never stored.
     */
    suspend fun recommendations(
        baseUrl: String,
        cash: Double,
        deep: Boolean = false,
        holdings: List<HoldingSync> = emptyList(),
        market: Boolean = false,
    ): RecommendationsResponse? {
        if (baseUrl.isBlank() || cash <= 0) return null
        val body = Http.json.encodeToString(
            RecommendRequest(cash, deep, holdings, if (market) "market" else "watchlist"),
        )
        return Http.json.decodeFromString<RecommendationsResponse>(
            sPost("${baseUrl.trimEnd('/')}/recommendations", body, slow = true),
        )
    }
}

@Serializable
data class ShortPressureResponse(
    val symbol: String = "",
    val state: String = "quiet", // quiet | fuel | ignition
    @SerialName("days_to_cover") val daysToCover: Double? = null,
    @SerialName("short_interest") val shortInterest: Long? = null,
    @SerialName("si_change_pct") val siChangePct: Double? = null,
    @SerialName("si_date") val siDate: String? = null,
    @SerialName("short_vol_ratio_5d") val shortVolRatio5d: Double? = null,
    @SerialName("ftd_trend") val ftdTrend: String? = null,
    @SerialName("ftd_series") val ftdSeries: List<FtdPoint> = emptyList(),
    @SerialName("ftd_spike_dates") val ftdSpikeDates: List<String> = emptyList(),
    @SerialName("si_history") val siHistory: List<SiPoint> = emptyList(),
    @SerialName("event_study") val eventStudy: FtdEventStudy? = null,
    val upcoming: List<UpcomingDate> = emptyList(),
    val reasons: List<String> = emptyList(),
)

@Serializable
data class FtdPoint(val date: String, val qty: Long)

@Serializable
data class SiPoint(val date: String, val dtc: Double? = null)

@Serializable
data class HistoryResponse(
    val symbol: String = "",
    val source: String = "", // "yahoo" | "webull"
    val bars: List<HistoryBar> = emptyList(),
)

@Serializable
data class HistoryBar(
    val t: Long,          // epoch ms
    val c: Double,        // close
    val v: Double = 0.0,  // volume
)

@Serializable
data class CycleResponse(
    val symbol: String = "",
    @SerialName("long_term_trend") val longTermTrend: LongTermTrend? = null,
    @SerialName("btc_halving_cycle") val halvingCycle: HalvingCycle? = null,
    @SerialName("halving_dates") val halvingDates: List<String> = emptyList(),
    @SerialName("next_halving_est") val nextHalvingEst: String? = null,
)

@Serializable
data class LongTermTrend(
    @SerialName("history_years") val historyYears: Double? = null,
    @SerialName("price_vs_200w_sma_pct") val priceVs200wSmaPct: Double? = null,
    @SerialName("pct_off_all_time_high") val pctOffAllTimeHigh: Double? = null,
    @SerialName("cagr_3y_pct") val cagr3yPct: Double? = null,
    @SerialName("mayer_multiple") val mayerMultiple: Double? = null,
)

/** Flat response of GET /trend/{symbol} — the stock 200-week-line block. */
@Serializable
data class TrendResponse(
    val symbol: String = "",
    val close: Double? = null,
    @SerialName("history_years") val historyYears: Double? = null,
    @SerialName("sma_200w") val sma200w: Double? = null,
    @SerialName("price_vs_200w_sma_pct") val priceVs200wSmaPct: Double? = null,
    @SerialName("below_line") val belowLine: Boolean? = null,
    val zone: String? = null,
    @SerialName("price_vs_200w_wow_pp") val priceVs200wWowPp: Double? = null,
    val direction: String? = null, // recovering | deepening | approaching | moving_away
    @SerialName("rsi_14w") val rsi14w: Double? = null,
    @SerialName("weekly_oversold") val weeklyOversold: Boolean? = null,
    @SerialName("pct_off_all_time_high") val pctOffAllTimeHigh: Double? = null,
    @SerialName("drawdown_z") val drawdownZ: Double? = null, // today's drawdown-from-peak, standardized vs the symbol's own history (very negative = unusually deep)
    @SerialName("cagr_3y_pct") val cagr3yPct: Double? = null,
    @SerialName("mayer_multiple") val mayerMultiple: Double? = null,
    @SerialName("volume_signal") val volumeSignal: String? = null, // quiet_accumulation | capitulation | breakout_volume | distribution | accumulation | neutral
    @SerialName("rvol_14") val rvol14: Double? = null,
    @SerialName("accumulation_ratio") val accumulationRatio: Double? = null,
)

/** GET /touches/{symbol} — "what happened the last N times it was below its 200-week line". */
@Serializable
data class TouchStudyResponse(
    val symbol: String = "",
    @SerialName("touch_count") val touchCount: Int = 0,
    @SerialName("measured_12m") val measured12m: Int = 0,
    @SerialName("currently_below") val currentlyBelow: Boolean? = null,
    @SerialName("median_fwd_12m_pct") val medianFwd12mPct: Double? = null,
    @SerialName("avg_fwd_12m_pct") val avgFwd12mPct: Double? = null,
    @SerialName("pct_positive_12m") val pctPositive12m: Int? = null,
    @SerialName("spy_avg_fwd_12m_pct") val spyAvgFwd12mPct: Double? = null,
    @SerialName("median_fwd_24m_pct") val medianFwd24mPct: Double? = null,
    @SerialName("pct_positive_24m") val pctPositive24m: Int? = null,
    @SerialName("spy_avg_fwd_24m_pct") val spyAvgFwd24mPct: Double? = null,
)

/** GET /insider/{symbol} — open-market insider purchases (Form 4) over the last 12 months. */
@Serializable
data class InsiderResponse(
    val symbol: String = "",
    @SerialName("buy_count_12m") val buyCount12m: Int = 0,
    @SerialName("buy_total_12m") val buyTotal12m: Long = 0,
    @SerialName("largest_buy_value") val largestBuyValue: Long = 0,
    @SerialName("has_conviction_buy") val hasConvictionBuy: Boolean = false,
    @SerialName("has_cluster_buy") val hasClusterBuy: Boolean = false,
    @SerialName("latest_buys") val latestBuys: List<InsiderBuy> = emptyList(),
)

@Serializable
data class InsiderBuy(
    val name: String = "",
    val date: String = "",
    val shares: Long = 0,
    val value: Long = 0,
)

/** GET /congress/{symbol} — congressional / political trades in the name. */
@Serializable
data class CongressResponse(val symbol: String = "", val congress: CongressBlock? = null)

@Serializable
data class CongressBlock(
    @SerialName("window_months") val windowMonths: Int = 12,
    @SerialName("trade_count") val tradeCount: Int = 0,
    @SerialName("buy_count") val buyCount: Int = 0,
    @SerialName("sell_count") val sellCount: Int = 0,
    @SerialName("net_direction") val netDirection: String = "",      // buying | selling | mixed | neutral
    @SerialName("distinct_filers") val distinctFilers: Int = 0,
    @SerialName("cluster_buy") val clusterBuy: Boolean = false,      // 3+ distinct members buying within 30d
    @SerialName("largest_buy_amount_high") val largestBuyAmountHigh: Long = 0,
    val parties: Map<String, Int> = emptyMap(),                      // e.g. {"R":3,"D":6,"?":11}
    val latest: List<CongressTrade> = emptyList(),
    @SerialName("latest_filing_date") val latestFilingDate: String? = null,
)

@Serializable
data class CongressTrade(
    val filer: String = "",
    val party: String? = null,
    val chamber: String? = null,   // senate | house
    val side: String = "",         // buy | sell | other
    val amount: String? = null,    // the disclosure amount range label
    @SerialName("transaction_date") val transactionDate: String? = null,
    @SerialName("filed_days_after") val filedDaysAfter: Int? = null,
    val late: Boolean = false,
)

/** GET /quality/{symbol} — business-quality descriptors (Finnhub basic-financials). */
@Serializable
data class QualityResponse(
    val symbol: String = "",
    val roe: Double? = null,                                  // percent
    @SerialName("gross_margin") val grossMargin: Double? = null,
    @SerialName("net_margin") val netMargin: Double? = null,
    @SerialName("debt_to_equity") val debtToEquity: Double? = null,  // ratio
    @SerialName("high_roe") val highRoe: Boolean = false,
    @SerialName("low_debt") val lowDebt: Boolean = false,
    @SerialName("wide_moat") val wideMoat: Boolean = false,
    @SerialName("buffett_quality") val buffettQuality: Boolean = false,
    @SerialName("dividend_aristocrat") val dividendAristocrat: Boolean = false,
    // FCF-trend (MB-13) + share-count-trend (MB-14) from Finnhub's as-reported 10-K financials.
    @SerialName("fcf_latest") val fcfLatest: Long? = null,
    @SerialName("fcf_trend") val fcfTrend: String? = null, // rising | flat | falling
    @SerialName("fcf_positive_years") val fcfPositiveYears: Int? = null,
    @SerialName("fcf_years") val fcfYears: Int? = null,
    @SerialName("shares_change_pct") val sharesChangePct: Double? = null, // + dilution / − buybacks
    /**
     * False when the change is too large to be organic — a stock split moves raw reported share
     * counts without diluting anyone (SMCI's 10-for-1 read as "+1074% dilution"). When false the
     * number must NOT be rendered as buybacks or dilution.
     */
    @SerialName("shares_change_reliable") val sharesChangeReliable: Boolean? = null,
    @SerialName("shares_change_note") val sharesChangeNote: String? = null,
    @SerialName("shares_years") val sharesYears: Int? = null,
) {
    val hasAnyFlag: Boolean get() = highRoe || lowDebt || wideMoat || buffettQuality || dividendAristocrat
    val hasMetrics: Boolean get() = roe != null || grossMargin != null || debtToEquity != null
    val hasFundamentals: Boolean get() = fcfTrend != null || sharesChangePct != null
}

@Serializable
data class HalvingCycle(
    @SerialName("last_halving") val lastHalving: String = "",
    @SerialName("next_halving_est") val nextHalvingEst: String = "",
    @SerialName("days_since_halving") val daysSinceHalving: Int = 0,
    @SerialName("days_to_next_est") val daysToNextEst: Int = 0,
    @SerialName("cycle_pct") val cyclePct: Double? = null,
    val phase: String = "",
    @SerialName("past_cycle_analog") val pastCycleAnalog: PastCycleAnalog? = null,
)

@Serializable
data class PastCycleAnalog(
    @SerialName("prior_cycles_measured") val priorCyclesMeasured: Int = 0,
    @SerialName("median_fwd_12mo_pct") val medianFwd12moPct: Double? = null,
    @SerialName("worst_fwd_12mo_pct") val worstFwd12moPct: Double? = null,
    @SerialName("best_fwd_12mo_pct") val bestFwd12moPct: Double? = null,
)

/** GET /movers?count= — the whole market's biggest gainers/losers on the day (market-wide close recap). */
@Serializable
data class MoversResponse(
    val gainers: List<MoverQuote> = emptyList(),
    val losers: List<MoverQuote> = emptyList(),
)

@Serializable
data class MoverQuote(
    val symbol: String = "",
    @SerialName("change_percent") val changePercent: Double? = null,
    val price: Double? = null,
)

/** GET /market_now — the instant AI market-pulse overview (AIE-5). [overview] is the paragraph to show;
 *  [snapshot] backs a compact header (indices/VIX/session). Numeric fields nullable — degrade gracefully. */
@Serializable
data class MarketNowResponse(
    val overview: String = "",
    @SerialName("overview_struct") val overviewStruct: MarketOverviewStruct? = null,
    val session: String = "",   // PRE | REGULAR | AFTER | CLOSED
    val model: String = "",
    val snapshot: MarketSnapshot = MarketSnapshot(),
    val cached: Boolean = false,
    val usage: AiUsage? = null,
)

@Serializable
data class MarketOverviewStruct(
    val tone: String = "",      // risk-on | risk-off | mixed
    val headline: String = "",
    val points: List<String> = emptyList(),
)

/**
 * GET /memory/stats — the service's own scorecard.
 *
 * [buyCalls] grades what the watchlist analyst called a buy; [sandboxBuys] grades what the paper
 * trader actually bought. Both are measured over 20 trading days against simply owning the index —
 * `beatRate20d` is the honest number, and `positiveRate20d` is deliberately NOT the headline because
 * equities drift up (any 20-day window is positive ~55-60% of the time regardless of skill).
 * Each block is absent until there are enough graded decisions to mean anything.
 */
@Serializable
data class MemoryStats(
    val verdicts: Int = 0,
    val scored: Int = 0,
    val symbols: Int = 0,
    @SerialName("by_origin") val byOrigin: Map<String, Int> = emptyMap(),
    @SerialName("buy_calls") val buyCalls: Scorecard? = null,
    @SerialName("sell_calls") val sellCalls: Scorecard? = null,
    @SerialName("sandbox_buys") val sandboxBuys: Scorecard? = null,
    @SerialName("sandbox_sells") val sandboxSells: Scorecard? = null,
)

/**
 * One side of the book, graded over 20 trading days.
 *
 * [correctRate20d] means the same thing on every card — higher is better, 0.50 is a coin flip —
 * because the sell side is inverted server-side: a sell is *correct* when the name subsequently
 * UNDERperforms the index, since owning the index was the alternative to holding it. Scoring sells
 * on raw return would mark every sell in a bull market as wrong regardless of judgement.
 *
 * The size of the edge arrives as [avgExcess20dPct] for buys and [avgAvoided20dPct] for sells;
 * positive is good in both. [avgFwd20dPct] is the raw price move and is NOT evidence on its own —
 * equities drift up.
 */
@Serializable
data class Scorecard(
    val n: Int = 0,
    @SerialName("avg_fwd_20d_pct") val avgFwd20dPct: Double = 0.0,
    @SerialName("avg_excess_20d_pct") val avgExcess20dPct: Double? = null,
    @SerialName("avg_avoided_20d_pct") val avgAvoided20dPct: Double? = null,
    @SerialName("correct_rate_20d") val correctRate20d: Double? = null,
) {
    /** Edge size, whichever side this card is — already signed so positive means "good call". */
    val edgePct: Double? get() = avgExcess20dPct ?: avgAvoided20dPct
}

/** GET /regime — the market-regime read (Theme D): a structural label + trend + volatility + a
 *  positioning note, plus the S&P's 50/200-day trend. */
@Serializable
data class RegimeResponse(
    val regime: MarketRegimeBlock = MarketRegimeBlock(),
    @SerialName("spy_trend") val spyTrend: SpyTrend? = null,
    val session: String = "",
    val model: String = "",
    val cached: Boolean = false,
)

@Serializable
data class MarketRegimeBlock(
    val label: String = "",
    val trend: String = "",       // up | down | sideways
    val volatility: String = "",  // calm | normal | elevated | stressed
    val note: String = "",
)

@Serializable
data class SpyTrend(
    val price: Double? = null,
    @SerialName("pct_vs_sma50") val pctVsSma50: Double? = null,
    @SerialName("pct_vs_sma200") val pctVsSma200: Double? = null,
    @SerialName("above_200d") val above200d: Boolean? = null,
    val rsi14: Double? = null,
)

/** GET /macro/catalysts — the exogenous-risk backdrop (NEWS).
 *
 *  [available] is the field that matters: false means no read exists, which is NOT the same as a read
 *  with no catalysts. "We couldn't look" and "nothing is happening" must never render alike, so the UI
 *  branches on [available] and [degraded] before it renders anything reassuring. */
@Serializable
data class MacroState(
    val available: Boolean = false,
    @SerialName("risk_level") val riskLevel: String? = null,   // low | elevated | high
    /** One short line — the backdrop at a glance. */
    val headline: String? = null,
    /** 3-5 one-line facts. Replaced a paragraph that read as a wall of text on a phone. */
    val bullets: List<String> = emptyList(),
    val catalysts: List<MacroCatalyst> = emptyList(),
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("age_seconds") val ageSeconds: Long? = null,
    val stale: Boolean = false,
    /** The last refresh failed but an older read survives — show the age, trust it less. */
    val degraded: Boolean = false,
    @SerialName("last_error") val lastError: String? = null,
) {
    /** Catalysts naming this exact ticker. Deliberately does NOT fuzzy-match the sector words in
     *  [MacroCatalyst.affected] — mapping "airlines" onto a ticker is how a confident wrong claim
     *  ends up on a stock's page. The backend applies the same rule. */
    fun forSymbol(symbol: String): List<MacroCatalyst> {
        val s = symbol.trim().uppercase().removeSuffix("-USD")
        return catalysts.filter { c -> c.tickers.any { it.trim().uppercase() == s } }
    }
}

@Serializable
data class MacroCatalyst(
    val key: String = "",
    val title: String = "",
    val category: String = "",      // geopolitics | monetary | fiscal | trade | energy | other
    val severity: Int = 0,          // 0-100 broad-market impact
    val direction: String = "",     // risk_off | risk_on | mixed
    val horizon: String = "",       // days | weeks | months
    val affected: List<String> = emptyList(),
    val tickers: List<String> = emptyList(),
    val confidence: Int = 0,
    val why: String = "",
    @SerialName("seen_count") val seenCount: Int = 0,
)

// ---- AI Sandbox models ----

@Serializable
data class SandboxState(
    val arm: String = "main",
    val label: String = "",
    val engine: String = "llm",          // llm | rules — a rules arm takes no view, so no AI reasons
    val cash: Double = 0.0,
    val equity: Double = 0.0,
    @SerialName("positions_value") val positionsValue: Double = 0.0,
    @SerialName("funded_total") val fundedTotal: Double = 0.0,
    @SerialName("realized_pl_total") val realizedPlTotal: Double = 0.0,
    @SerialName("total_return_pct") val totalReturnPct: Double? = null,
    @SerialName("cash_pct") val cashPct: Double? = null,
    @SerialName("benchmark_value") val benchmarkValue: Double? = null,
    @SerialName("vs_benchmark_pct") val vsBenchmarkPct: Double? = null,
    val positions: List<SandboxPosition> = emptyList(),
    val settings: SandboxSettings = SandboxSettings(),
    val enabled: Boolean = false,
    @SerialName("last_tick_date") val lastTickDate: String? = null,
    /** One-line read of what the last tick decided. Null on an account that has not ticked since the
     *  field was added — a hold with no posture is not the same as a hold that explained itself. */
    @SerialName("last_posture") val lastPosture: String? = null,
    /** Peak-to-trough risk, from the NAV series. Null on a curve too short to have one: "no drawdown
     *  yet" and "measured, and it was zero" are different claims and only the second is earned. */
    @SerialName("max_drawdown_pct") val maxDrawdownPct: Double? = null,
    @SerialName("current_drawdown_pct") val currentDrawdownPct: Double? = null,
    @SerialName("peak_equity") val peakEquity: Double? = null,
    @SerialName("days_underwater") val daysUnderwater: Int? = null,
    @SerialName("last_weekly_review_date") val lastWeeklyReviewDate: String? = null,
    @SerialName("last_strategy_note") val strategyNote: SandboxStrategyNote? = null,
    @SerialName("created_at") val createdAt: Double? = null,
)

@Serializable
data class SandboxPosition(
    val symbol: String = "",
    val shares: Double = 0.0,
    @SerialName("avg_cost") val avgCost: Double = 0.0,
    @SerialName("exposure_group") val exposureGroup: String = "",
    val price: Double = 0.0,
    val value: Double = 0.0,
    @SerialName("unrealized_pct") val unrealizedPct: Double? = null,
)

@Serializable
data class SandboxSettings(
    @SerialName("master_enabled") val masterEnabled: Boolean = false,
    @SerialName("risk_tolerance") val riskTolerance: String = "balanced",
    @SerialName("retirement_date") val retirementDate: String? = null,
    /** Birth date (ISO yyyy-mm-dd). The source of truth for the glidepath's runway; [currentAge] is
     *  derived from it server-side on every read, so the age shown here is always today's. */
    @SerialName("birth_date") val birthDate: String? = null,
    @SerialName("current_age") val currentAge: Int? = null,
    @SerialName("retirement_age") val retirementAge: Int? = null,
    @SerialName("account_type") val accountType: String = "cash",
    @SerialName("avoid_wash_sales") val avoidWashSales: Boolean = true,
    @SerialName("exit_date") val exitDate: String? = null,
    @SerialName("goal_amount") val goalAmount: Double? = null,
    @SerialName("goal_date") val goalDate: String? = null,
    @SerialName("monthly_deposit") val monthlyDeposit: Double = 0.0,
    @SerialName("max_position_pct") val maxPositionPct: Double = 20.0,
    @SerialName("cash_floor_pct") val cashFloorPct: Double = 10.0,
    @SerialName("allow_crypto") val allowCrypto: Boolean = false,
    @SerialName("allow_crypto_etf") val allowCryptoEtf: Boolean = true,
    /** Which spot-bitcoin ETF to buy for BTC exposure. All hold the same asset, so this is a custody
     *  and fee choice, not an exposure one. Blank = no preference. */
    @SerialName("preferred_btc_etf") val preferredBtcEtf: String = "FBTC",
    @SerialName("allow_etf") val allowEtf: Boolean = true,
    /** Smallest company the market screen may propose, in USD of market cap. 0 = no floor.
     *  ETFs are exempt server-side — they report AUM, not market cap. */
    @SerialName("min_market_cap") val minMarketCap: Double = 2_000_000_000.0,
    val exclusions: List<String> = emptyList(),
    val cadence: String = "daily",
    @SerialName("allow_after_hours") val allowAfterHours: Boolean = false,
    @SerialName("max_turnover_pct") val maxTurnoverPct: Double = 25.0,
    @SerialName("notify_on_trade") val notifyOnTrade: Boolean = true,
    @SerialName("max_trades_per_tick") val maxTradesPerTick: Int = 4,
    @SerialName("max_new_positions_per_tick") val maxNewPositionsPerTick: Int = 2,
    @SerialName("min_conviction_to_trade") val minConvictionToTrade: Int = 55,
    @SerialName("respect_entry_zones") val respectEntryZones: Boolean = true,
    @SerialName("slippage_bps") val slippageBps: Int = 5,
)

@Serializable
data class SandboxStrategyNote(
    val stance: String = "",
    @SerialName("cash_target_pct") val cashTargetPct: Double = 0.0,
    val targets: List<SandboxTarget> = emptyList(),
    val themes: List<String> = emptyList(),
    val avoid: List<String> = emptyList(),
    val notes: String = "",
)

@Serializable
data class SandboxTarget(
    @SerialName("exposure_group") val exposureGroup: String = "",
    @SerialName("target_pct") val targetPct: Double = 0.0,
)

@Serializable
data class SandboxNavResponse(val series: List<SandboxNavPoint> = emptyList())

@Serializable
data class SandboxNavPoint(
    val ts: Double = 0.0,
    val date: String = "",
    val equity: Double = 0.0,
    val cash: Double = 0.0,
    @SerialName("positions_value") val positionsValue: Double = 0.0,
    @SerialName("benchmark_value") val benchmarkValue: Double? = null,
    /** Total fictional cash put IN by this point — the initial funding plus every recurring deposit.
     *  Required to tell performance from contributions: a $500/mo deposit into a $10k account raises
     *  equity ~5% a month on its own, which a trend fitted on raw equity reports as a rally. */
    @SerialName("funded_total") val fundedTotal: Double? = null,
    @SerialName("num_positions") val numPositions: Int = 0,
) {
    /** Equity per dollar contributed — an index that starts at 1.0 and moves ONLY on performance.
     *  Null when the row predates funded_total being recorded. */
    val perDollar: Double? get() = fundedTotal?.takeIf { it > 0 }?.let { equity / it }
}

@Serializable
data class SandboxArmsResponse(val arms: List<SandboxArm> = emptyList())

/** All arms' curves on a shared date axis. [dates] is the union of every arm's observations and each
 *  arm's [SandboxArmSeries.equity] is padded to it with nulls, so index i is the same day in every
 *  series. [commonStartIndex] is the first index where every arm has a value — the only honest base
 *  for an indexed comparison, and null when the arms have no overlapping history yet. */
@Serializable
data class SandboxArmsNav(
    val dates: List<String> = emptyList(),
    @SerialName("common_start") val commonStart: String? = null,
    @SerialName("common_start_index") val commonStartIndex: Int? = null,
    val arms: List<SandboxArmSeries> = emptyList(),
)

@Serializable
data class SandboxArmSeries(
    val arm: String = "",
    val label: String = "",
    val engine: String = "llm",
    val equity: List<Double?> = emptyList(),
    @SerialName("benchmark_value") val benchmarkValue: List<Double?> = emptyList(),
)

/** One comparison arm's scoreboard. [vsBenchmarkPct] is the only figure comparable ACROSS arms —
 *  raw equity is not, since arms can be funded with different amounts on different days, so each
 *  carries its own "same money in the S&P" shadow and the excess over it is what lines up. */
@Serializable
data class SandboxArm(
    val arm: String = "main",
    val label: String = "",
    val engine: String = "llm",          // llm | rules
    val enabled: Boolean = false,
    val cash: Double = 0.0,
    val equity: Double = 0.0,
    @SerialName("positions_value") val positionsValue: Double = 0.0,
    @SerialName("funded_total") val fundedTotal: Double = 0.0,
    val positions: Int = 0,
    @SerialName("cash_pct") val cashPct: Double? = null,
    @SerialName("total_return_pct") val totalReturnPct: Double? = null,
    @SerialName("benchmark_value") val benchmarkValue: Double? = null,
    @SerialName("vs_benchmark_pct") val vsBenchmarkPct: Double? = null,
    @SerialName("last_tick_date") val lastTickDate: String? = null,
)

@Serializable
data class SandboxTradesResponse(val trades: List<SandboxTrade> = emptyList())

@Serializable
data class SandboxTrade(
    val ts: Double = 0.0,
    val date: String = "",
    val symbol: String = "",
    val side: String = "",       // buy | sell | deposit | withdraw
    val status: String = "",     // filled | skipped
    val shares: Double = 0.0,
    val price: Double? = null,
    val gross: Double? = null,
    @SerialName("realized_pl") val realizedPl: Double? = null,
    val conviction: Int? = null,
    val source: String = "",
    val reason: String = "",
    @SerialName("skip_reason") val skipReason: String? = null,
    @SerialName("entry_low") val entryLow: Double? = null,
    @SerialName("entry_high") val entryHigh: Double? = null,
    /** Set only when the executed size differs from the size the analyst asked for — currently the
     *  round-up of a buy that came in a few cents under one share. Absent on ordinary fills. */
    @SerialName("size_note") val sizeNote: String? = null,
)

@Serializable
data class SandboxTickResult(
    val status: String = "",
    val date: String = "",
    val posture: String = "",
    @SerialName("orders_filled") val ordersFilled: List<SandboxTrade> = emptyList(),
    @SerialName("orders_skipped") val ordersSkipped: List<SandboxTrade> = emptyList(),
    @SerialName("weekly_review_ran") val weeklyReviewRan: Boolean = false,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class SandboxFundRequest(val amount: Double)

@Serializable
/**
 * No default values, deliberately.
 *
 * `Http.json` leaves `encodeDefaults` at false, so a field equal to its class default is OMITTED —
 * and `SandboxTickRequest(force = true)` serialized to `{}`. The server then applied its normal
 * cadence gate, so "Run a decision cycle now" silently did nothing outside the scheduled window
 * while still returning 200. Required parameters can't be dropped.
 */
data class SandboxTickRequest(val force: Boolean, val manual: Boolean)

@Serializable
data class SandboxResetRequest(val confirm: Boolean)

@Serializable
data class SandboxSettingsPatch(
    @SerialName("master_enabled") val masterEnabled: Boolean? = null,
    @SerialName("risk_tolerance") val riskTolerance: String? = null,
    @SerialName("retirement_date") val retirementDate: String? = null,
    @SerialName("birth_date") val birthDate: String? = null,
    @SerialName("current_age") val currentAge: Int? = null,
    @SerialName("retirement_age") val retirementAge: Int? = null,
    // Nullable like every other field here. As non-nullable defaults they vanished from the JSON
    // whenever set to "cash"/true, so margin could never be switched OFF and wash-sale avoidance
    // could never be switched back ON — the POST returned 200 with the settings unchanged.
    @SerialName("account_type") val accountType: String? = null,
    @SerialName("avoid_wash_sales") val avoidWashSales: Boolean? = null,
    @SerialName("exit_date") val exitDate: String? = null,
    @SerialName("goal_amount") val goalAmount: Double? = null,
    @SerialName("goal_date") val goalDate: String? = null,
    @SerialName("monthly_deposit") val monthlyDeposit: Double? = null,
    @SerialName("max_position_pct") val maxPositionPct: Double? = null,
    @SerialName("cash_floor_pct") val cashFloorPct: Double? = null,
    @SerialName("allow_crypto") val allowCrypto: Boolean? = null,
    @SerialName("allow_crypto_etf") val allowCryptoEtf: Boolean? = null,
    @SerialName("preferred_btc_etf") val preferredBtcEtf: String? = null,
    @SerialName("allow_etf") val allowEtf: Boolean? = null,
    @SerialName("min_market_cap") val minMarketCap: Double? = null,
    val exclusions: List<String>? = null,
    val cadence: String? = null,
    @SerialName("allow_after_hours") val allowAfterHours: Boolean? = null,
    @SerialName("max_turnover_pct") val maxTurnoverPct: Double? = null,
    @SerialName("notify_on_trade") val notifyOnTrade: Boolean? = null,
    @SerialName("min_conviction_to_trade") val minConvictionToTrade: Int? = null,
    @SerialName("respect_entry_zones") val respectEntryZones: Boolean? = null,
)

/** GET /daily_brief — the AI morning brief (AIE-3): a notification [title] + [body] + [tone], plus the
 *  watchlist names reporting earnings today. All defaulted so a partial payload still deserializes. */
@Serializable
data class DailyBriefResponse(
    val title: String = "",
    val body: String = "",
    val tone: String = "",      // risk-on | risk-off | mixed
    @SerialName("catalysts_today") val catalystsToday: List<String> = emptyList(),
    val session: String = "",   // PRE | REGULAR | AFTER | CLOSED
    val model: String = "",
    val cached: Boolean = false,
)

/** GET /news_moves/{symbol} — "why it moved" (AIE-4): notable recent daily moves correlated with dated
 *  headlines. [newsMoves] is null (with a [note]) for crypto/unsupported symbols. */
@Serializable
data class NewsMovesResponse(
    val symbol: String = "",
    @SerialName("news_moves") val newsMoves: NewsMovesBlock? = null,
    val note: String? = null,
    val model: String = "",
    val cached: Boolean = false,
)

@Serializable
data class NewsMovesBlock(
    val summary: String = "",
    val drivers: List<NewsDriver> = emptyList(),
)

@Serializable
data class NewsDriver(
    val date: String = "",                              // YYYY-MM-DD
    @SerialName("move_pct") val movePct: Double = 0.0,
    val headline: String? = null,                       // the driving headline, or null if none fit
    val explanation: String = "",
)

@Serializable
data class MarketSnapshot(
    val session: String = "",
    @SerialName("as_of_et") val asOfEt: String = "",
    val indices: List<MarketPulseQuote> = emptyList(),
    val vix: VixNow = VixNow(),
    @SerialName("sector_leaders") val sectorLeaders: List<MarketPulseQuote> = emptyList(),
    @SerialName("sector_laggards") val sectorLaggards: List<MarketPulseQuote> = emptyList(),
    @SerialName("watchlist_movers") val watchlistMovers: PulseMovers = PulseMovers(),
    @SerialName("market_movers") val marketMovers: MoversResponse? = null,
)

@Serializable
data class MarketPulseQuote(val name: String = "", val symbol: String = "", val pct: Double? = null)

@Serializable
data class VixNow(val level: Double? = null, val pct: Double? = null)

@Serializable
data class PulseMovers(
    val up: List<MarketPulseQuote> = emptyList(),
    val down: List<MarketPulseQuote> = emptyList(),
)

/** GET /seasonality/{symbol} — typical per-calendar-month price action. */
@Serializable
data class SeasonalityResponse(val symbol: String = "", val seasonality: SeasonalityBlock? = null)

@Serializable
data class SeasonalityBlock(
    val years: Int = 0,
    @SerialName("sample_note") val sampleNote: String = "",
    val months: List<SeasonMonth> = emptyList(),
    @SerialName("current_month") val currentMonth: SeasonMonth? = null,
    @SerialName("best_month") val bestMonth: SeasonExtreme? = null,
    @SerialName("worst_month") val worstMonth: SeasonExtreme? = null,
)

@Serializable
data class SeasonMonth(
    val month: Int = 0,
    val name: String = "",
    val n: Int = 0,
    @SerialName("avg_pct") val avgPct: Double? = null,
    @SerialName("hit_rate") val hitRate: Int? = null,
    @SerialName("best_pct") val bestPct: Double? = null,
    @SerialName("worst_pct") val worstPct: Double? = null,
)

@Serializable
data class SeasonExtreme(
    val name: String = "",
    @SerialName("avg_pct") val avgPct: Double? = null,
    @SerialName("hit_rate") val hitRate: Int? = null,
)

/** POST /portfolio/review — whole-portfolio AI read. */
@Serializable
data class PortfolioReviewRequest(
    val cash: Double,
    val deep: Boolean = false,
    /** No default ON PURPOSE: Http.json leaves encodeDefaults false, so a field equal to its class
     *  default is omitted from the body. Three request bodies in this app shipped broken that way. */
    val refresh: Boolean,
    val holdings: List<HoldingSync>,
)

@Serializable
data class PortfolioReviewResponse(
    val review: PortfolioReview = PortfolioReview(),
    val portfolio: PortfolioSummary = PortfolioSummary(),
    val model: String = "",
    val cached: Boolean = false,
    val usage: AiUsage? = null,
)

@Serializable
data class PortfolioReview(
    val health: String = "",
    val concentration: List<String> = emptyList(),
    val actions: List<PortfolioAction> = emptyList(),
    @SerialName("cash_note") val cashNote: String = "",
)

@Serializable
data class PortfolioAction(
    val symbol: String = "",
    val action: String = "",   // trim | hold | add | watch
    val reason: String = "",
)

@Serializable
data class PortfolioSummary(
    @SerialName("total_value") val totalValue: Double = 0.0,
    /**
     * Null when the book contains an [unvalued] holding: the denominator is missing a whole
     * position, so no percentage in this book is computable. Non-nullable it would coerce to a
     * confident "0% cash".
     */
    @SerialName("cash_pct") val cashPct: Double? = null,
    val positions: List<PortfolioPosition> = emptyList(),
    /**
     * Holdings the backend could not price. They are carried at cost in [totalValue], so the review's
     * weights are approximate whenever this is non-empty — the user has to be told, or the screen
     * asserts an allocation that isn't theirs.
     */
    val unpriced: List<UnpricedHolding> = emptyList(),
    /** Holdings with neither a price nor a cost basis — while non-empty, every weight here is null. */
    val unvalued: List<String> = emptyList(),
    /** Non-USD currencies summed into [totalValue] at face value — no FX rate is applied. */
    @SerialName("mixed_currencies") val mixedCurrencies: List<String> = emptyList(),
    @SerialName("weights_approximate") val weightsApproximate: Boolean = false,
)

@Serializable
data class UnpricedHolding(
    val symbol: String = "",
    val shares: Double = 0.0,
    @SerialName("value_at_cost") val valueAtCost: Double = 0.0,
)

@Serializable
data class PortfolioPosition(
    val symbol: String = "",
    @SerialName("weight_pct") val weightPct: Double? = null,
    @SerialName("unrealized_gain_pct") val unrealizedGainPct: Double? = null,
    val value: Double = 0.0,
)

@Serializable
data class CalendarResponse(val events: List<CalendarEvent> = emptyList())

@Serializable
data class CalendarEvent(
    val date: String,
    val symbol: String? = null, // null = market-wide (SI dates, OPEX)
    val label: String = "",
    val kind: String = "",
)

/** What this symbol's own price history did after past FTD spikes. */
@Serializable
data class FtdEventStudy(
    val events: Int = 0,
    @SerialName("fwd5_median_pct") val fwd5MedianPct: Double? = null,
    @SerialName("fwd10_median_pct") val fwd10MedianPct: Double? = null,
    @SerialName("fwd10_hit_rate") val fwd10HitRate: Double? = null,
)

@Serializable
data class UpcomingDate(
    val date: String,
    val label: String,
    val kind: String = "",
)

@Serializable
data class HoldingSync(
    val symbol: String,
    val shares: Double,
    @SerialName("avg_cost") val avgCost: Double,
)

/** POST /portfolio/rebalance — a concrete sized rebalance plan (Theme C). */
@Serializable
data class RebalanceRequestBody(
    val cash: Double,
    val deep: Boolean = false,
    /** No default — see PortfolioReviewRequest.refresh. */
    val refresh: Boolean,
    @SerialName("max_position_pct") val maxPositionPct: Double,
    val holdings: List<HoldingSync>,
)

@Serializable
data class RebalanceResponse(
    val plan: RebalancePlan = RebalancePlan(),
    /**
     * What the server had to correct or drop in the model's plan — an over-sell capped to the shares
     * actually held, an unaffordable buy trimmed, a move on a symbol not in the book removed, or the
     * plan failing to reach its own target weight. Silence here means the plan passed clean.
     */
    @SerialName("plan_warnings") val planWarnings: List<String> = emptyList(),
    @SerialName("max_position_pct") val maxPositionPct: Double = 0.0,
    val portfolio: PortfolioSummary = PortfolioSummary(),
    val model: String = "",
    val cached: Boolean = false,
    val usage: AiUsage? = null,
)

@Serializable
data class RebalancePlan(
    val summary: String = "",
    val moves: List<RebalanceMove> = emptyList(),
    @SerialName("resulting_top_weight_pct") val resultingTopWeightPct: Double? = null,
    @SerialName("cash_after") val cashAfter: Double? = null,
)

@Serializable
data class RebalanceMove(
    val symbol: String = "",
    val action: String = "",   // sell | buy | hold
    val shares: Double = 0.0,
    val dollars: Double = 0.0,
    val reason: String = "",
)

@Serializable
data class RecommendRequest(
    val cash: Double,
    val deep: Boolean = false,
    val holdings: List<HoldingSync> = emptyList(),
    val scope: String = "watchlist", // "watchlist" | "market" (adds live-screened candidates)
)

/** One asset's entry plan: what to do, at what price, with how many shares of the cash. */
@Serializable
data class EntryPlan(
    val symbol: String = "",
    val action: String = "", // buy_now | buy_on_pullback | wait | avoid
    val conviction: Int = 0,
    // NULLABLE, deliberately. These were non-nullable Doubles defaulting to 0.0, and Http.json sets
    // coerceInputValues = true, so both an omitted key and an explicit null from the analyst decoded
    // to 0.0 — which then rendered as a confident "Stop $0 · target $0" on a money decision. The
    // analyst legitimately returns null when it can't justify a level; absence has to be
    // representable so the UI can omit it instead of inventing zero.
    @SerialName("entry_low") val entryLow: Double? = null,
    @SerialName("entry_high") val entryHigh: Double? = null,
    @SerialName("suggested_shares") val suggestedShares: Double? = null,
    @SerialName("allocation_usd") val allocationUsd: Double? = null,
    val stop: Double? = null,
    val target: Double? = null,
    val timing: String = "",
    val thesis: String = "",
)

@Serializable
data class PlanResponse(
    val symbol: String,
    val model: String = "",
    val cash: Double = 0.0,
    val plan: EntryPlan,
    val cached: Boolean = false,
    val usage: AiUsage? = null,
)

/**
 * GET /options/{symbol}?budget=&style= — the "Play with calls" suggester (OC-2). Every numeric field
 * is nullable on purpose: options quotes go stale/zero outside market hours, so the card must degrade
 * gracefully rather than crash. No LLM — pure server-side math.
 */
@Serializable
data class OptionsResponse(
    val symbol: String = "",
    val spot: Double? = null,
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("quote_delayed") val quoteDelayed: Boolean = false,
    val light: String = "", // green | yellow | red
    @SerialName("light_reason") val lightReason: String = "",
    val expiry: OptionExpiry? = null,
    @SerialName("expected_move") val expectedMove: Double? = null,
    val structure: String = "long_call",
    @SerialName("structure_note") val structureNote: String = "",
    val candidates: List<OptionCandidate> = emptyList(),
    val warnings: List<String> = emptyList(),
    val earnings: OptionEarnings? = null,
    // OC-6/OC-7 additive fields (all nullable — degrade gracefully on older responses):
    @SerialName("iv_rank") val ivRank: Float? = null,                     // 0-100, null while "building"
    @SerialName("recommend_alternative") val recommendAlternative: Boolean = false,
    val alternative: DebitSpread? = null,                                 // cheaper debit call spread, or null
    val analyst: String? = null,                                          // Opus paragraph, only when deep=true
)

/** The cheaper debit-call-spread alternative the server suggests when IV is rich (OC-6). Two legs, so
 *  no single copy-pasteable order ticket — the card just shows the numbers. All numerics nullable. */
@Serializable
data class DebitSpread(
    val structure: String = "debit_call_spread",
    @SerialName("long_strike") val longStrike: Double? = null,
    @SerialName("short_strike") val shortStrike: Double? = null,
    @SerialName("net_debit") val netDebit: Double? = null,   // per-share debit
    val cost: Double? = null,                                // total debit paid (net_debit × 100 × contracts)
    @SerialName("max_profit") val maxProfit: Double? = null,
    @SerialName("max_loss") val maxLoss: Double? = null,
    val breakeven: Double? = null,
    val note: String = "",
)

@Serializable
data class OptionExpiry(
    val ts: Long? = null,
    val iso: String? = null,
    val dte: Int? = null,
    val rationale: String = "",
)

@Serializable
data class OptionCandidate(
    val profile: String = "", // safer | balanced | cheaper
    @SerialName("contract_symbol") val contractSymbol: String = "",
    val strike: Double? = null,
    @SerialName("limit_price") val limitPrice: Double? = null,
    val cost: Double? = null,               // premium × 100 per contract
    @SerialName("max_loss") val maxLoss: Double? = null, // total premium at risk (cost × contracts)
    val contracts: Int? = null,
    val breakeven: Double? = null,
    @SerialName("breakeven_pct") val breakevenPct: Double? = null,
    val delta: Double? = null,
    val theta: Double? = null,              // $/day per contract (typically negative)
    val iv: Double? = null,                 // implied vol as a fraction (0.33 = 33%)
    @SerialName("spread_pct") val spreadPct: Double? = null,
    @SerialName("open_interest") val openInterest: Long? = null,
    @SerialName("expected_move") val expectedMove: Double? = null,
    @SerialName("order_ticket") val orderTicket: String = "",
)

@Serializable
data class OptionEarnings(
    val date: String? = null,
    @SerialName("in_window") val inWindow: Boolean = false,
)

/**
 * GET /puts/{symbol}?cash=&style= — the "Get paid to buy" cash-secured put suggester (OC-8). Every
 * numeric field is nullable on purpose: options quotes go stale/zero outside market hours, so the card
 * must degrade gracefully rather than crash. No LLM — pure server-side math. [note] is the honest
 * risk-framing sentence to surface verbatim; [earnings] flags an earnings date inside the option's life.
 */
@Serializable
data class PutsResponse(
    val symbol: String = "",
    val spot: Double? = null,
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("quote_delayed") val quoteDelayed: Boolean = false,
    val expiry: OptionExpiry? = null,
    val candidates: List<PutCandidate> = emptyList(),
    val warnings: List<String> = emptyList(),
    val earnings: OptionEarnings? = null,
    val note: String = "",
)

@Serializable
data class PutCandidate(
    val profile: String = "", // aggressive | balanced | conservative
    @SerialName("contract_symbol") val contractSymbol: String = "",
    val strike: Double? = null,
    @SerialName("limit_price") val limitPrice: Double? = null,
    @SerialName("premium_income") val premiumIncome: Double? = null,        // total premium collected now
    @SerialName("net_cost_per_share") val netCostPerShare: Double? = null,  // strike − premium/share, if assigned
    @SerialName("discount_vs_spot_pct") val discountVsSpotPct: Double? = null, // how far net cost sits below spot
    @SerialName("cash_to_reserve") val cashToReserve: Double? = null,       // collateral to set aside
    val contracts: Int? = null,
    @SerialName("static_yield_pct") val staticYieldPct: Double? = null,     // premium ÷ collateral, over the hold
    @SerialName("annualized_yield_pct") val annualizedYieldPct: Double? = null,
    @SerialName("assignment_prob_pct") val assignmentProbPct: Double? = null, // chance you're put the shares
    val breakeven: Double? = null,
    val delta: Double? = null,
    val theta: Double? = null,              // $/day per contract (a short put's theta is positive to you)
    val iv: Double? = null,                 // implied vol as a fraction (0.33 = 33%)
    @SerialName("open_interest") val openInterest: Long? = null,
    @SerialName("spread_pct") val spreadPct: Double? = null,
    @SerialName("order_ticket") val orderTicket: String = "",
)

/**
 * GET /covered_call/{symbol}?shares=&target= — the "Sell covered calls" income suggester (OC-8).
 * Returns a single [candidate] (unlike /puts which ladders several). Every numeric field is nullable
 * on purpose (stale/zero quotes outside market hours). No LLM. [shares]/[contracts] echo the position
 * the server sized the call against.
 */
@Serializable
data class CoveredCallResponse(
    val symbol: String = "",
    val spot: Double? = null,
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("quote_delayed") val quoteDelayed: Boolean = false,
    val shares: Int? = null,
    val contracts: Int? = null,
    val expiry: OptionExpiry? = null,
    val candidate: CoveredCallCandidate? = null,
    val warnings: List<String> = emptyList(),
    val note: String = "",
)

@Serializable
data class CoveredCallCandidate(
    @SerialName("contract_symbol") val contractSymbol: String = "",
    val strike: Double? = null,
    @SerialName("limit_price") val limitPrice: Double? = null,
    @SerialName("premium_income") val premiumIncome: Double? = null,        // total premium collected now
    @SerialName("premium_yield_pct") val premiumYieldPct: Double? = null,   // premium ÷ position value
    @SerialName("annualized_yield_pct") val annualizedYieldPct: Double? = null,
    @SerialName("assignment_prob_pct") val assignmentProbPct: Double? = null, // chance you're called away
    @SerialName("called_away_gain_from_here") val calledAwayGainFromHere: Double? = null, // $ gain if called at strike
    val delta: Double? = null,
    val theta: Double? = null,
    val iv: Double? = null,
    @SerialName("open_interest") val openInterest: Long? = null,
    @SerialName("spread_pct") val spreadPct: Double? = null,
    @SerialName("order_ticket") val orderTicket: String = "",
)

/**
 * GET /option_quote/{symbol} — a live re-price of ONE tracked contract (OC-3). Every numeric field is
 * nullable on purpose: quotes zero out / go stale outside market hours, so the My Calls list must show
 * a "—" rather than crash. [dte] is days-to-expiry; [spot] the underlying's last price.
 */
@Serializable
data class OptionQuoteResponse(
    val symbol: String = "",
    val spot: Double? = null,
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("quote_delayed") val quoteDelayed: Boolean = false,
    val dte: Double? = null,
    val contract: OptionQuoteContract? = null,
)

@Serializable
data class OptionQuoteContract(
    @SerialName("contract_symbol") val contractSymbol: String = "",
    val type: String = "call",
    val strike: Double? = null,
    val expiration: String? = null,
    val bid: Double? = null,
    val ask: Double? = null,
    @SerialName("last_price") val lastPrice: Double? = null,
    val mid: Double? = null,
    @SerialName("limit_price") val limitPrice: Double? = null,
    @SerialName("implied_volatility") val impliedVolatility: Double? = null,
    val delta: Double? = null,
    val theta: Double? = null,
    @SerialName("open_interest") val openInterest: Long? = null,
    @SerialName("in_the_money") val inTheMoney: Boolean? = null,
    @SerialName("spread_pct") val spreadPct: Double? = null,
) {
    /** Best available current premium per share: the server's limit_price, else the mid, else last. */
    val currentPrice: Double? get() = limitPrice ?: mid ?: lastPrice
}

@Serializable
data class RecommendationsResponse(
    val model: String = "",
    val cash: Double = 0.0,
    val scope: String = "watchlist",
    val discovered: List<String> = emptyList(),
    val considered: Int = 0,
    val overview: String = "",
    val picks: List<EntryPlan> = emptyList(),
    val passed: List<String> = emptyList(),
    val cached: Boolean = false,
    val usage: AiUsage? = null,
)

@Serializable
data class WatchlistSync(
    val watchlist: List<String>,
    @SerialName("crypto_watchlist") val cryptoWatchlist: List<String>,
)

@Serializable
data class ScanLatest(
    @SerialName("generated_at") val generatedAt: Double? = null,
    val results: List<ScanResult> = emptyList(),
    val flips: List<String> = emptyList(),
    /** Symbols that newly closed below their 200-week line since the prior scan (mungbeans' signal). */
    @SerialName("crossed_below_200wma") val crossedBelow200wma: List<String> = emptyList(),
    /** Symbols that newly entered a "good time to add" dip tier this scan. */
    @SerialName("dip_alerts") val dipAlerts: List<DipAlert> = emptyList(),
    /** Today/tomorrow key-date warnings (SI publication, OPEX, earnings). */
    @SerialName("date_alerts") val dateAlerts: List<String> = emptyList(),
)

@Serializable
data class ScanResult(
    val symbol: String,
    val signal: String = "",
    val conviction: Int = 0,
    val flipped: Boolean = false,
    @SerialName("prev_signal") val prevSignal: String? = null,
    /** Short-pressure state (quiet/fuel/ignition) and whether it changed vs the prior scan. */
    val squeeze: String? = null,
    @SerialName("squeeze_changed") val squeezeChanged: Boolean = false,
    /** Below its 200-week line this scan, and whether that's newly-crossed vs the prior scan. */
    @SerialName("below_200wma") val below200wma: Boolean? = null,
    @SerialName("crossed_below_200wma") val crossedBelow200wma: Boolean = false,
    /** "Good time to add" tier: mega_dip | below_line | oversold | pullback_10 | pullback_5 | null. */
    val dip: String? = null,
    @SerialName("pct_off_recent_high") val pctOffRecentHigh: Double? = null,
    @SerialName("pct_off_52w_high") val pctOff52wHigh: Double? = null,
)

/** A symbol that newly entered a dip tier this scan — the "good time to add" event. */
@Serializable
data class DipAlert(
    val symbol: String = "",
    val dip: String = "",
    @SerialName("pct_off_recent_high") val pctOffRecentHigh: Double? = null,
    @SerialName("pct_off_52w_high") val pctOff52wHigh: Double? = null,
)

@Serializable
data class AiSignalResponse(
    val symbol: String,
    val model: String = "",
    val cached: Boolean = false,
    /** Epoch SECONDS when the backend produced this verdict. It always sent this; the app just never
     *  modelled it, so a server-cached verdict up to 4h old rendered identically to a fresh read. */
    @SerialName("as_of") val asOf: Double = 0.0,
    val verdict: AiVerdict,
    val usage: AiUsage? = null,
)

/** Token usage + estimated USD cost of the Claude call that produced a verdict. */
@Serializable
data class AiUsage(
    val model: String = "",
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cost_usd") val costUsd: Double = 0.0,
)

@Serializable
data class AiVerdict(
    val signal: String,
    val conviction: Int,
    val horizon: String = "",
    val thesis: String = "",
    val rationale: List<String> = emptyList(),
    @SerialName("key_risks") val keyRisks: List<String> = emptyList(),
    val invalidation: String = "",
    val catalysts: List<String> = emptyList(),
    /** Numeric chart levels (AIE-1) the app overlays on the price chart. Null on older responses. */
    val levels: AiLevels? = null,
)

@Serializable
data class AiLevels(
    val support: Double? = null,
    val resistance: Double? = null,
    @SerialName("invalidation_price") val invalidationPrice: Double? = null,
    val target: Double? = null,
)

/** GET /screener/value — the 200-week value screen (free, no LLM). */
@Serializable
data class ValueScreenResponse(
    @SerialName("universe_size") val universeSize: Int = 0,
    /**
     * Which pool the screen actually ran over: "curated" (the primary-sourced universe) or
     * "yahoo_screens" (the fallback sampler, rebuilt server-side per call). Not rendering it made a
     * fallback run look identical to a curated one.
     */
    @SerialName("universe_source") val universeSource: String = "",
    /** True when the curated universe is older than its refresh window. */
    @SerialName("universe_stale") val universeStale: Boolean = false,
    /** Symbols whose data could not be FETCHED — distinct from [skipped], which lack history. */
    @SerialName("fetch_failed") val fetchFailed: List<String> = emptyList(),
    val scored: Int = 0,
    /** Symbols that could NOT be scored (usually under ~4 years of weekly history). Named rather
     *  than dropped: "unscoreable" and "scored badly" are different facts. */
    val skipped: List<String> = emptyList(),
    val results: List<ValueScreenRow> = emptyList(),
    /** The server's own framing. Rendered verbatim — this is context, not a buy signal. */
    val note: String = "",
    val cached: Boolean = false,
    @SerialName("cached_age_seconds") val cachedAgeSeconds: Long? = null,
)

@Serializable
data class ValueScreenRow(
    val symbol: String = "",
    @SerialName("value_score") val valueScore: Double = 0.0,
    @SerialName("below_line") val belowLine: Boolean = false,
    @SerialName("price_vs_200w_sma_pct") val priceVs200wPct: Double? = null,
    @SerialName("rsi_14w") val rsi14w: Double? = null,
    val direction: String? = null,
    val zone: String? = null,
    @SerialName("pct_off_10y_high") val pctOff10yHigh: Double? = null,
    /** Inputs that were not available; each contributed 0 to the score but was not measured. */
    val unmeasured: List<String> = emptyList(),
)

/** GET /valuetrap/{symbol} — discount vs deterioration (MB-17). Free, no LLM. */
@Serializable
data class ValueTrapResponse(
    val symbol: String = "",
    /** "discount" | "deteriorating" | "unclear". */
    val verdict: String = "unclear",
    val confidence: String = "low",
    /**
     * False when there was not enough evidence to judge. "Unclear because balanced" and "unclear
     * because we saw almost nothing" are different facts and must not render the same way.
     */
    val assessable: Boolean = false,
    val red: List<String> = emptyList(),
    val green: List<String> = emptyList(),
    /** What could not be seen. Rendered, so an absence never passes as a clean bill of health. */
    val missing: List<String> = emptyList(),
    val note: String = "",
    @SerialName("below_line") val belowLine: Boolean? = null,
)

/** GET /smart_money — Theme C. Free, no LLM. */
@Serializable
data class SmartMoneyResponse(
    @SerialName("watchlist_size") val watchlistSize: Int = 0,
    val results: List<SmartMoneyRow> = emptyList(),
    /** Names we LOOKED at and found nothing for — distinct from ones we could not look at. */
    @SerialName("no_evidence") val noEvidence: List<String> = emptyList(),
    @SerialName("fetch_failed") val fetchFailed: List<String> = emptyList(),
    /**
     * False when no Finnhub key is set. The insider feed then returns null rather than raising,
     * which would render as "nobody is buying" across the entire watchlist — so the card must say
     * the ranking is congressional disclosures only.
     */
    @SerialName("insider_feed_configured") val insiderFeedConfigured: Boolean = true,
    val warning: String? = null,
    val note: String = "",
    val cached: Boolean = false,
    @SerialName("cached_age_seconds") val cachedAgeSeconds: Long? = null,
)

@Serializable
data class SmartMoneyRow(
    val symbol: String = "",
    val score: Double = 0.0,
    /** Every contributing reason, so a rank can be interrogated rather than taken on faith. */
    val reasons: List<String> = emptyList(),
    @SerialName("sources_seen") val sourcesSeen: List<String> = emptyList(),
    /** Feeds that could not be read for this name. */
    val unavailable: List<String> = emptyList(),
    /** Newest DISCLOSED trade date and when it was filed — congressional filings lag ~45 days. */
    @SerialName("congress_newest_trade") val congressNewestTrade: String? = null,
    @SerialName("congress_latest_filing") val congressLatestFiling: String? = null,
)

/** GET /sandbox/changes — the settings changelog. The companion to the trade log: that says what the
 *  account DID, this says what it was told to do it with. */
@Serializable
data class SandboxChange(
    val ts: Double = 0.0,
    val date: String = "",
    val arm: String = "main",
    val source: String = "api",
    /** setting name -> {from, to}. Only keys that actually moved are recorded, so an empty map
     *  never appears — a no-op write produces no row at all. */
    val changed: Map<String, SandboxChangeValue> = emptyMap(),
)

@Serializable
data class SandboxChangeValue(
    /** Deliberately JsonElement, not String: a setting's value may be a number, bool, string, null
     *  or a list, and coercing them all to text would render `false` and `"false"` identically. */
    val from: kotlinx.serialization.json.JsonElement? = null,
    val to: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class SandboxChangesResponse(
    val arm: String = "main",
    val changes: List<SandboxChange> = emptyList(),
)

/** GET /sectors — the classification the watchlist groups by. Free, no LLM. */
@Serializable
data class SectorsResponse(val sectors: Map<String, SectorProfile> = emptyMap())

@Serializable
data class SectorProfile(
    /** Null when the server looked and could not classify the symbol — ETFs and warrants have no
     *  sector at Yahoo. Null is a real answer here and renders as "Other"; a symbol ABSENT from the
     *  response is a different thing entirely and must not be treated as this. */
    val sector: String? = null,
    val industry: String? = null,
)

/** GET /heatmap — tile values for the treemap. Free, no LLM. */
@Serializable
data class HeatmapResponse(
    val mode: String = "market",
    /**
     * When this data was produced. In signals mode it is the NIGHTLY scan's timestamp — always
     * hours old, sometimes a day — so the screen has to age it or a stale scan reads as live.
     */
    @SerialName("as_of") val asOf: Double? = null,
    val session: String? = null,
    val tiles: List<HeatmapTile> = emptyList(),
    /** Names that could not be priced — a fact about the fetch, not about the stock. */
    val unpriced: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val advancing: Int? = null,
    val declining: Int? = null,
    @SerialName("universe_stale") val universeStale: Boolean? = null,
    val scale: String = "price",
    val note: String = "",
    val cached: Boolean = false,
    @SerialName("cached_age_seconds") val cachedAgeSeconds: Long? = null,
)

@Serializable
data class HeatmapTile(
    val symbol: String = "",
    val name: String = "",
    /** The AREA. Always positive — the server refuses zero/absent sizes rather than emitting them. */
    val size: Double = 0.0,
    /** The COLOUR. Its meaning depends on [scale]. */
    val value: Double = 0.0,
    /**
     * "price" (green/red — the market moved) or "signal" (amber — this system has an opinion).
     * Carried per tile so a dip tier can never be rendered on the price scale.
     */
    val scale: String = "price",
    /**
     * The block this tile belongs to, e.g. "Technology". Null when the symbol could not be
     * classified — rendered as "Other" rather than dropped, since being unclassified is a fact about
     * our data and not a reason for a stock to vanish from a map of the market.
     */
    val sector: String? = null,
    val industry: String? = null,
    val price: Double? = null,
    val dip: String? = null,
    val signal: String? = null,
    val conviction: Int? = null,
    @SerialName("pct_off_52w_high") val pctOff52wHigh: Double? = null,
    @SerialName("below_200wma") val below200wma: Boolean = false,
)
