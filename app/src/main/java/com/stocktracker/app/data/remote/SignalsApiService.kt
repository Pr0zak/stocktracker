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
)

@Serializable
data class ScanResult(
    val symbol: String,
    val signal: String = "",
    val conviction: Int = 0,
    val flipped: Boolean = false,
    @SerialName("prev_signal") val prevSignal: String? = null,
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
