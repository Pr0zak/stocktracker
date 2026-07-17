package com.stocktracker.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

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
    ): AiSignalResponse? {
        if (baseUrl.isBlank()) return null
        // The backend fetches via Yahoo, whose crypto symbols take a -USD suffix.
        val sym = if (crypto) "${symbol.uppercase()}-USD" else symbol.uppercase()
        val url = "${baseUrl.trimEnd('/')}/signal/$sym?crypto=$crypto&deep=$deep"
        val body = Http.getString(url)
        return Http.json.decodeFromString<AiSignalResponse>(body)
    }
}

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
