package com.stocktracker.app.data.remote

import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.model.SearchResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.IOException

/**
 * Finnhub free tier: live stock quotes + symbol search.
 * Note: the free tier no longer serves historical candles — stock charts use Stooq instead.
 */
class FinnhubService(private val keyProvider: () -> String) {

    private val base = "https://finnhub.io/api/v1"

    private val apiKey: String get() = keyProvider()

    val hasKey: Boolean get() = apiKey.isNotBlank()

    suspend fun quote(symbol: String): Quote {
        val sym = symbol.uppercase()
        val body = Http.getString("$base/quote?symbol=$sym&token=$apiKey")
        val dto = Http.json.decodeFromString<FinnhubQuoteDto>(body)
        // Finnhub returns all-zeros for unknown/unsupported symbols — treat that as "no quote"
        // rather than fabricating a real-looking $0.00.
        if (dto.c == 0.0 && (dto.pc == null || dto.pc == 0.0)) {
            throw IOException("No quote for $sym (invalid or unsupported symbol, or free-tier limit)")
        }
        val change = dto.d ?: (dto.c - (dto.pc ?: dto.c))
        val pct = dto.dp ?: run {
            val pc = dto.pc ?: 0.0
            if (pc != 0.0) change / pc * 100.0 else 0.0
        }
        return Quote(
            symbol = sym,
            price = dto.c,
            change = change,
            changePercent = pct,
            open = dto.o,
            high = dto.h,
            low = dto.l,
            prevClose = dto.pc,
            currency = "USD",
            asOfEpochMs = System.currentTimeMillis(),
        )
    }

    suspend fun search(query: String): List<SearchResult> {
        val body = Http.getString("$base/search?q=${query.urlEncode()}&token=$apiKey")
        val dto = Http.json.decodeFromString<FinnhubSearchDto>(body)
        return dto.result
            // Don't filter by instrument type — that dropped ETFs/ETPs (e.g. FBTC) and warrants
            // (e.g. GME-WS). Keep any result with a symbol.
            .filter { it.symbol.isNotBlank() }
            .map { SearchResult(it.symbol, it.description.ifBlank { it.symbol }, AssetType.STOCK) }
            .take(25)
    }
}

@Serializable
data class FinnhubQuoteDto(
    val c: Double = 0.0,   // current price
    val d: Double? = null, // change
    val dp: Double? = null,// percent change
    val h: Double? = null, // high
    val l: Double? = null, // low
    val o: Double? = null, // open
    val pc: Double? = null,// previous close
    val t: Long = 0,       // timestamp
)

@Serializable
data class FinnhubSearchDto(
    val count: Int = 0,
    val result: List<FinnhubSymbol> = emptyList(),
)

@Serializable
data class FinnhubSymbol(
    val description: String = "",
    val displaySymbol: String = "",
    val symbol: String = "",
    val type: String = "",
)
