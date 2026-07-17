package com.stocktracker.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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

    /** GET [url] on the IO dispatcher; retries a couple of times on HTTP 429 with backoff.
     *  [slow] switches to the long-timeout client for analyst (LLM) endpoints. */
    suspend fun getString(url: String, slow: Boolean = false): String = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        repeat(3) { attempt ->
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/csv, */*")
                .build()
            (if (slow) slowClient else client).newCall(request).execute().use { response ->
                if (response.code == 429) {
                    lastError = IOException("HTTP 429 (rate limited) for $url")
                    // fall through to backoff + retry below
                } else {
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        throw HttpStatusException(response.code, url, body)
                    }
                    return@withContext body ?: throw IOException("Empty body for $url")
                }
            }
            delay((attempt + 1) * 1000L) // 1s, 2s
        }
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
    IOException("HTTP $code for $url: ${body?.take(200)}")

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
