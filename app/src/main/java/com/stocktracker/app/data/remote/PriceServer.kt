package com.stocktracker.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * PX-1 — the self-hosted signals service as the FIRST place prices come from.
 *
 * Every price used to be fetched from Yahoo and CoinGecko by the phone itself, once per screen and
 * once more per widget. On 2026-09-29 CoinGecko refused this household's IP for hours and four
 * watchlist rows sat 16 hours old. The service now serves Yahoo's own chart/search responses from a
 * shared cache (`/prices/yahoo/...`) and builds crypto rows (`/prices/crypto`), so the phone and the
 * widgets share one fetch and one back-off.
 *
 * The phone still knows how to ask directly, and does whenever this returns null: no URL configured,
 * off the home network/tailnet, or the service answered with anything but a usable body. A price the
 * server could not get is therefore never a blank row — only a slower one.
 */
object PriceServer {

    /** Kept in sync with Settings → Signals service URL by the ServiceLocator. Blank = off. */
    @Volatile var baseUrl: String = ""

    /**
     * After the service fails to CONNECT, skip it for this long. Without it, a phone off the tailnet
     * would wait out a connect timeout on every one of ~60 symbols before each direct fetch.
     */
    private const val SKIP_AFTER_DOWN_MS = 60_000L
    @Volatile private var downUntilMs = 0L

    /** A short connect timeout for the same reason: the service is on the LAN or it is not there. */
    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    enum class Source { SERVER, DIRECT }

    /** Which source answered the most recent price read, and when. Null until the first one. */
    data class Served(val source: Source, val atMs: Long)
    private val _lastServed = MutableStateFlow<Served?>(null)
    val lastServed: StateFlow<Served?> = _lastServed.asStateFlow()

    fun noteDirect() { _lastServed.value = Served(Source.DIRECT, System.currentTimeMillis()) }
    private fun noteServer() { _lastServed.value = Served(Source.SERVER, System.currentTimeMillis()) }

    /** Whether it is worth asking the service right now. */
    fun available(nowMs: Long = System.currentTimeMillis()): Boolean =
        baseUrl.isNotBlank() && nowMs >= downUntilMs

    /**
     * GET `{base}/{pathAndQuery}` and return the body, or null for "ask the source yourself".
     * Only a 200 counts, plus — for [yahoo] — Yahoo's own 404, which the service marks with an
     * `X-Price-Cache` header. An unmarked 404 is an older service with no `/prices` route and must not
     * read as "this symbol has no data", so it is a null like any other miss.
     */
    suspend fun get(pathAndQuery: String, passYahoo404: Boolean = false): String? {
        if (!available()) return null
        val url = baseUrl.trimEnd('/') + "/" + pathAndQuery.trimStart('/')
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                    .execute().use { r ->
                        val yahoo404 = passYahoo404 && r.code == 404 && r.header("X-Price-Cache") != null
                        if (r.code != 200 && !yahoo404) return@use null
                        r.body?.string()?.also { noteServer() }
                    }
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                throw ce
            } catch (_: IOException) {
                downUntilMs = System.currentTimeMillis() + SKIP_AFTER_DOWN_MS
                null
            } catch (_: IllegalArgumentException) {
                null // a malformed URL in Settings — nothing to retry
            }
        }
    }

    /** Yahoo's own v8 chart / v1 search body for [path] (e.g. `v8/finance/chart/AAPL?range=1d...`). */
    suspend fun yahoo(path: String): String? = get("prices/yahoo/$path", passYahoo404 = true)

    /** Crypto rows for (CoinGecko id, symbol) pairs; null = ask CoinGecko/Yahoo directly. */
    suspend fun crypto(pairs: List<Pair<String, String>>): List<ServerCoinRow>? {
        if (pairs.isEmpty()) return emptyList()
        val q = pairs.joinToString(",") { (id, sym) -> "$id:$sym" }
        val body = get("prices/crypto?coins=$q") ?: return null
        return runCatching { Http.json.decodeFromString<ServerCryptoResponse>(body).rows }.getOrNull()
    }
}

@Serializable
data class ServerCryptoResponse(val rows: List<ServerCoinRow> = emptyList())

@Serializable
data class ServerCoinRow(
    val id: String,
    val symbol: String,
    val price: Double,
    val change: Double,
    @SerialName("change_percent") val changePercent: Double,
    val sparkline: List<Double> = emptyList(),
    /** Epoch SECONDS of the newest data behind the row. */
    @SerialName("as_of") val asOf: Double,
    val source: String = "",
)
