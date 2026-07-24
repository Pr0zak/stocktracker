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
        val url = "${baseUrl.trimEnd('/')}/signal/$sym?crypto=$crypto&deep=$deep$pos$rs"
        val body = Http.getString(url, slow = true) // LLM latency, not a quote endpoint
        return Http.json.decodeFromString<AiSignalResponse>(body)
    }

    /** The latest nightly-scan result (for the flip-notification check). */
    suspend fun latestScan(baseUrl: String): ScanLatest? {
        if (baseUrl.isBlank()) return null
        val body = Http.getString("${baseUrl.trimEnd('/')}/scan/latest")
        return Http.json.decodeFromString<ScanLatest>(body)
    }

    /** Push the app's watchlist up so the backend's nightly scan tracks what the user tracks. */
    suspend fun syncWatchlist(baseUrl: String, stocks: List<String>, cryptos: List<String>) {
        if (baseUrl.isBlank()) return
        Http.postJson("${baseUrl.trimEnd('/')}/api/settings", Http.json.encodeToString(WatchlistSync(stocks, cryptos)))
    }

    /** Short-pressure read (FINRA SI + short volume + SEC FTDs) — free, no LLM call. Stocks only. */
    suspend fun shortPressure(baseUrl: String, symbol: String): ShortPressureResponse? {
        if (baseUrl.isBlank()) return null
        val body = Http.getString("${baseUrl.trimEnd('/')}/shorts/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<ShortPressureResponse>(body)
    }

    /** Daily history fallback (Yahoo → Webull on the server) for symbols the app's own Yahoo fetch
     *  can't chart — e.g. warrants/OTC. Returns bars + the source used, or null. */
    suspend fun history(baseUrl: String, symbol: String): HistoryResponse? {
        if (baseUrl.isBlank()) return null
        val body = Http.getString("${baseUrl.trimEnd('/')}/history/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<HistoryResponse>(body)
    }

    /** Crypto long-term context: halving-cycle position (BTC), multi-year trend, halving dates. */
    suspend fun cycleInfo(baseUrl: String, symbol: String): CycleResponse? {
        if (baseUrl.isBlank()) return null
        val sym = "${symbol.uppercase()}-USD"
        val body = Http.getString("${baseUrl.trimEnd('/')}/cycle/$sym", slow = true)
        return Http.json.decodeFromString<CycleResponse>(body)
    }

    /** Below-the-200-week-line trend for a STOCK — the equity mirror of the crypto cycle card:
     *  200w SMA, %-from-line, below-line zone, recovering/deepening direction, 14-week RSI. Free, no
     *  LLM. Null (404) for names with under ~4 years of weekly history. */
    suspend fun trend(baseUrl: String, symbol: String): TrendResponse? {
        if (baseUrl.isBlank()) return null
        val body = Http.getString("${baseUrl.trimEnd('/')}/trend/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<TrendResponse>(body)
    }

    /** Historical 200-week-line touch study — forward 12/24-month returns after past dips below the
     *  line, vs the S&P 500. Evidence context, not a signal. Free, no LLM. Null (404) if too new. */
    suspend fun touchStudy(baseUrl: String, symbol: String): TouchStudyResponse? {
        if (baseUrl.isBlank()) return null
        val body = Http.getString("${baseUrl.trimEnd('/')}/touches/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<TouchStudyResponse>(body)
    }

    /** Insider buying (SEC Form 4 via Finnhub) — open-market purchases in the last 12 months, the
     *  bullish smart-money read. Free (needs a Finnhub key on the service). Null on 404/no-key. */
    suspend fun insider(baseUrl: String, symbol: String): InsiderResponse? {
        if (baseUrl.isBlank()) return null
        val body = Http.getString("${baseUrl.trimEnd('/')}/insider/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<InsiderResponse>(body)
    }

    /** Congressional / political trades in a stock over the last 12 months (House+Senate+cabinet, from
     *  the free kadoa dataset). Free, no LLM. Lagging (~45-day STOCK Act window) — weak, debated
     *  "smart money" context, never a signal. Returns null on any failure or when nobody traded it. */
    suspend fun congress(baseUrl: String, symbol: String): CongressBlock? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val body = Http.getString("${baseUrl.trimEnd('/')}/congress/${symbol.uppercase()}", slow = true)
            Http.json.decodeFromString<CongressResponse>(body).congress
        }.getOrNull()
    }

    /** Per-calendar-month seasonal price action (~10y): avg return + hit rate per month, current-month
     *  tendency, best/worst months. Free, no LLM. Weak, sample-limited context. Null under ~2y history. */
    suspend fun seasonality(baseUrl: String, symbol: String): SeasonalityBlock? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val body = Http.getString("${baseUrl.trimEnd('/')}/seasonality/${symbol.uppercase()}", slow = true)
            Http.json.decodeFromString<SeasonalityResponse>(body).seasonality
        }.getOrNull()
    }

    /** Whole-portfolio AI review: overall health, concentration flags, a per-holding action list, and a
     *  cash-deployment note. POSTs the holdings (crypto must be sent as <SYM>-USD). Gate on the AI switch. */
    suspend fun portfolioReview(
        baseUrl: String, cash: Double, holdings: List<HoldingSync>, deep: Boolean = false,
    ): PortfolioReviewResponse? {
        if (baseUrl.isBlank() || holdings.isEmpty()) return null
        val body = Http.json.encodeToString(PortfolioReviewRequest(cash, deep, holdings))
        return Http.json.decodeFromString<PortfolioReviewResponse>(
            Http.postJson("${baseUrl.trimEnd('/')}/portfolio/review", body, slow = true),
        )
    }

    /** Quality tags (Finnhub basic-financials) — ROE/margins/D-E + Buffett/wide-moat/aristocrat flags.
     *  Stance-neutral business descriptors. Free. Null on 404. */
    suspend fun quality(baseUrl: String, symbol: String): QualityResponse? {
        if (baseUrl.isBlank()) return null
        val body = Http.getString("${baseUrl.trimEnd('/')}/quality/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<QualityResponse>(body)
    }

    /** Whole-market top movers (biggest gainers + losers on the day) for the market-close recap. Free,
     *  no LLM. Returns null on any failure (blank URL, network, parse) so the caller can fall back to
     *  the watchlist; on the backend's own failure the lists come back empty. */
    suspend fun movers(baseUrl: String, count: Int = 6): MoversResponse? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val body = Http.getString("${baseUrl.trimEnd('/')}/movers?count=$count")
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
        val body = Http.getString("${baseUrl.trimEnd('/')}/market_now$d", slow = true) // LLM latency
        return Http.json.decodeFromString<MarketNowResponse>(body)
    }

    /** Catalyst calendar (SI dates, OPEX, earnings). Whole watchlist by
     *  default; pass [symbol] for a single stock's calendar. Free. */
    suspend fun calendar(baseUrl: String, symbol: String? = null): CalendarResponse? {
        if (baseUrl.isBlank()) return null
        val q = symbol?.let { "?symbol=${it.uppercase()}" } ?: ""
        val body = Http.getString("${baseUrl.trimEnd('/')}/calendar$q", slow = true)
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
    ): PlanResponse? {
        if (baseUrl.isBlank() || cash <= 0) return null
        val sym = if (crypto) "${symbol.uppercase()}-USD" else symbol.uppercase()
        val pos = if (shares != null && avgCost != null && shares > 0 && avgCost > 0) {
            "&shares=$shares&avg_cost=$avgCost"
        } else {
            ""
        }
        val url = "${baseUrl.trimEnd('/')}/plan/$sym?cash=$cash&crypto=$crypto&deep=$deep$pos"
        return Http.json.decodeFromString<PlanResponse>(Http.getString(url, slow = true))
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
        val body = Http.getString(url, slow = true) // chain fetch + greeks, not a quote endpoint
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
        val body = Http.getString(url, slow = true) // chain fetch + greeks, not a quote endpoint
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
        val body = Http.getString(url, slow = true) // chain fetch + greeks, not a quote endpoint
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
            Http.getString(url, slow = true) // chain fetch + greeks, not a quote endpoint
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
            Http.postJson("${baseUrl.trimEnd('/')}/recommendations", body, slow = true),
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
    @SerialName("cash_pct") val cashPct: Double = 0.0,
    val positions: List<PortfolioPosition> = emptyList(),
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
    @SerialName("entry_low") val entryLow: Double = 0.0,
    @SerialName("entry_high") val entryHigh: Double = 0.0,
    @SerialName("suggested_shares") val suggestedShares: Double = 0.0,
    @SerialName("allocation_usd") val allocationUsd: Double = 0.0,
    val stop: Double = 0.0,
    val target: Double = 0.0,
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
