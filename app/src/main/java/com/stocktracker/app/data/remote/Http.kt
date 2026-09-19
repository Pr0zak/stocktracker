package com.stocktracker.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Shared OkHttp client + JSON parser. */
object Http {

    // Browser-like UA: Yahoo's chart endpoint (and some others) reject unknown clients.
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) StockTracker/1.0"

    // Yahoo's chart endpoint hands out a session/consent cookie on the first hit and expects it
    // echoed back; without a jar every request looks brand-new and is likelier to draw a 401/429.
    // In-memory only (dies with the process), keyed by name@domain, and we defer to OkHttp's own
    // domain/path matching on the way out.
    private val cookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { store["${it.name}@${it.domain}"] = it }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            // Drop expired cookies (session cookies have expiresAt = Long.MAX_VALUE, so they stay) —
            // re-sending a stale consent cookie is itself a way to draw the 401 this jar prevents.
            return store.values.filter { it.matches(url) && it.expiresAt > now }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS) // hard ceiling so a stuck call fails fast to cached data
        .cookieJar(cookieJar)
        .build()

    // Analyst (LLM) calls legitimately run 30-120s — deep dives think, recommendations read the whole
    // watchlist in one call — so they get a patient client instead of the quote-endpoint ceiling.
    private val slowClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build()
    }

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // Per-host circuit breaker (DATA-5): once a host answers 429, [throwIfBreakerOpen] makes every
    // further call to that host fail immediately — no network round-trip, no wait behind whatever
    // semaphore a caller put in front of Http — until RetryPolicy.BREAKER_COOLDOWN_MILLIS has passed.
    // Keyed by host only (query1.finance.yahoo.com vs query2.finance.yahoo.com are separate breakers)
    // so one rate-limited host doesn't block calls to an unrelated one (Finnhub, CoinGecko, Signals).
    private val breakerTrippedAtMillis = ConcurrentHashMap<String, Long>()

    private fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host

    private fun tripBreaker(url: String) {
        hostOf(url)?.let { breakerTrippedAtMillis[it] = System.currentTimeMillis() }
    }

    /**
     * Throws an [HttpStatusException] (code 429, exactly what a live rate limit would throw) if
     * [url]'s host is inside its circuit-breaker cooldown. Callers that gate requests behind their
     * own semaphore (e.g. [YahooFinanceService]'s 2-permit gate) should call this *before* acquiring
     * a permit, so a known-bad host fails fast instead of queueing to find out the same thing later.
     * A no-op once the cooldown elapses — see [RetryPolicy.isBreakerOpen].
     *
     * Throwing here (rather than returning an empty/null result) matters: this app's rule is that
     * absent data must never render as a confident number, and a silently-empty response from a
     * tripped breaker would look exactly like "this symbol has no data" instead of "rate limited,
     * try again shortly".
     */
    fun throwIfBreakerOpen(url: String) {
        val host = hostOf(url) ?: return
        val trippedAt = breakerTrippedAtMillis[host] ?: return
        val now = System.currentTimeMillis()
        if (RetryPolicy.isBreakerOpen(now, trippedAt)) {
            val remaining = RetryPolicy.breakerCooldownRemainingMillis(now, trippedAt)
            throw HttpStatusException(429, url, "circuit breaker open for $host; retry in ${remaining}ms")
        }
    }

    /** GET [url] on the IO dispatcher; retries a couple of times on HTTP 429 with backoff.
     *  Honours the response's `Retry-After` header (delta-seconds or HTTP-date) when present, falling
     *  back to the previous exponential wait otherwise; never sleeps after the last attempt. A 429
     *  also trips [url]'s host breaker (see [throwIfBreakerOpen]) so subsequent calls elsewhere fail
     *  fast instead of piling into the same rate limit.
     *  [slow] switches to the long-timeout client for analyst (LLM) endpoints. */
    suspend fun getString(url: String, slow: Boolean = false): String = withContext(Dispatchers.IO) {
        throwIfBreakerOpen(url)
        var lastError: IOException? = null
        repeat(RetryPolicy.MAX_ATTEMPTS) { attempt ->
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/csv, */*")
                .build()
            var retryAfterHeader: String? = null
            (if (slow) slowClient else client).newCall(request).execute().use { response ->
                if (response.code == 429) {
                    val body = response.body?.string()
                    lastError = HttpStatusException(429, url, body)
                    retryAfterHeader = response.header("Retry-After")
                    // fall through to backoff + retry below. The breaker is NOT tripped here:
                    // tripping on the first 429 would make one transient rejection fail the other
                    // 49 symbols of a watchlist refresh for the whole cooldown, turning "slow"
                    // into "broken". It trips only once this url's own retries are exhausted,
                    // below, which is the point at which the host is genuinely refusing us.
                } else {
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        throw HttpStatusException(response.code, url, body)
                    }
                    return@withContext body ?: throw IOException("Empty body for $url")
                }
            }
            if (RetryPolicy.shouldSleepAfterAttempt(attempt, RetryPolicy.MAX_ATTEMPTS)) {
                val retryAfterMillis = RetryPolicy.parseRetryAfterMillis(retryAfterHeader, System.currentTimeMillis())
                delay(RetryPolicy.backoffDelayMillis(attempt, retryAfterMillis))
            }
        }
        // Every attempt came back 429: the host is rate limiting in earnest, so stop the rest of
        // the refresh from queueing into the same wall.
        if (lastError is HttpStatusException && (lastError as HttpStatusException).code == 429) tripBreaker(url)
        throw lastError ?: IOException("Request failed after retries: $url")
    }

    /** POST a JSON [body] to [url] and return the response body. Throws [HttpStatusException] on non-2xx.
     *  [slow] switches to the long-timeout client for analyst (LLM) endpoints. */
    suspend fun postJson(url: String, body: String, slow: Boolean = false): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        (if (slow) slowClient else client).newCall(request).execute().use { response ->
            val respBody = response.body?.string()
            if (!response.isSuccessful) throw HttpStatusException(response.code, url, respBody)
            respBody ?: ""
        }
    }
}

fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

/**
 * A non-2xx HTTP response. Carries the status [code] so callers can tell a genuine 404 ("no data /
 * delisted") apart from a transient 429/5xx that's worth retrying, and the [body] so callers can
 * surface a server-provided error message.
 */
class HttpStatusException(val code: Int, url: String, val body: String?) :
    IOException("HTTP $code for $url: ${body?.take(200)}") {

    /**
     * Whether this status means the Signals service itself is unreachable rather than merely unhappy.
     *
     * The self-hosted service usually sits behind a reverse proxy, and a proxy that cannot reach its
     * upstream answers 502/503/504 itself. Treating "something answered with HTTP" as proof of
     * reachability therefore cleared the offline banner while nothing actually worked — a tap on
     * "retry" looked like it fixed the problem. A 4xx is a real answer from a live service; a
     * bad-gateway family response is the opposite.
     */
    val meansBackendDown: Boolean get() = code in 502..504
}

/**
 * A user-showable message for a failed signals-service call: FastAPI's {"detail": "..."} body when
 * present (e.g. "watchlist is empty — open the app to sync it"), else null for a generic fallback.
 */
fun analystErrorDetail(e: Throwable?): String? = (e as? HttpStatusException)?.body?.let { body ->
    runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(body)
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("detail")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
