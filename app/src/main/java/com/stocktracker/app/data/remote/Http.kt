package com.stocktracker.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Shared OkHttp client + JSON parser. */
object Http {

    // Browser-like UA: Yahoo's chart endpoint (and some others) reject unknown clients.
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) StockTracker/1.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** GET [url] on the IO dispatcher; retries a couple of times on HTTP 429 with backoff. */
    suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        repeat(3) { attempt ->
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/csv, */*")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    lastError = IOException("HTTP 429 (rate limited) for $url")
                    // fall through to backoff + retry below
                } else {
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} for $url: ${body?.take(200)}")
                    }
                    return@withContext body ?: throw IOException("Empty body for $url")
                }
            }
            delay(((attempt + 1) * 2L) * 1000L) // 2s, 4s
        }
        throw lastError ?: IOException("Request failed after retries: $url")
    }
}

fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
