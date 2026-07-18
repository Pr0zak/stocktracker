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

    /** Quality tags (Finnhub basic-financials) — ROE/margins/D-E + Buffett/wide-moat/aristocrat flags.
     *  Stance-neutral business descriptors. Free. Null on 404. */
    suspend fun quality(baseUrl: String, symbol: String): QualityResponse? {
        if (baseUrl.isBlank()) return null
        val body = Http.getString("${baseUrl.trimEnd('/')}/quality/${symbol.uppercase()}", slow = true)
        return Http.json.decodeFromString<QualityResponse>(body)
    }

    /** Catalyst calendar (SI dates, OPEX, earnings, speculative T+35 echoes). Whole watchlist by
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
) {
    val hasAnyFlag: Boolean get() = highRoe || lowDebt || wideMoat || buffettQuality || dividendAristocrat
    val hasMetrics: Boolean get() = roe != null || grossMargin != null || debtToEquity != null
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
    /** Today/tomorrow key-date warnings (SI publication, OPEX, earnings, speculative T+35). */
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
)
