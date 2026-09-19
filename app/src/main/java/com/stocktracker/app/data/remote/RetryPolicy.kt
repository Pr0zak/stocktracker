package com.stocktracker.app.data.remote

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure decision functions behind [Http]'s 429 handling: how long to wait, whether to wait at all,
 * whether a 429 may retry against a fallback host, and whether a host's circuit breaker is open.
 * Kept free of I/O, wall-clock reads, and coroutines so DATA-5's retry math can be unit tested
 * without a fake server or `Thread.sleep`.
 *
 * DATA-5: the old strategy (`repeat(3)` + `delay((attempt + 1) * 1000L)`) had three defects —
 * it ignored `Retry-After` entirely, it slept after the final (already-failed) attempt for nothing,
 * and Yahoo's query1→query2 host failover retried the *same* rate limit against a second host,
 * doubling request volume at the exact moment the remote asked for less of it. This object plus the
 * per-host breaker in [Http] fix all three.
 */
object RetryPolicy {

    /** How many times [Http.getString] tries a single host before giving up. */
    const val MAX_ATTEMPTS = 3

    /**
     * Ceiling on any single retry wait, whether it comes from `Retry-After` or the exponential
     * fallback. A header is the server's own claim about itself — a hostile or simply mistaken one
     * (wrong units, a date far in the future) must not be able to park the app for an hour when the
     * honest worst case here is "try again shortly".
     */
    const val MAX_RETRY_AFTER_MILLIS = 30_000L

    /** How long a tripped per-host breaker stays open before calls to that host are tried again. */
    const val BREAKER_COOLDOWN_MILLIS = 60_000L

    /**
     * Parses a `Retry-After` header value in either form RFC 9110 allows:
     *  - delta-seconds, e.g. `"120"`
     *  - an HTTP-date, e.g. `"Wed, 21 Oct 2026 07:28:00 GMT"` (RFC 1123 / RFC 7231 format)
     *
     * Returns milliseconds to wait measured from [nowMillis], or null when [headerValue] is absent,
     * blank, or matches neither form. Never negative: a date that has already passed (clock skew, or
     * the server naming a moment that's already gone by the time we parse it) means "wait zero", not
     * "hurry up, you're late".
     */
    fun parseRetryAfterMillis(headerValue: String?, nowMillis: Long): Long? {
        val trimmed = headerValue?.trim()
        if (trimmed.isNullOrEmpty()) return null
        trimmed.toLongOrNull()?.let { seconds ->
            return if (seconds < 0) null else seconds * 1000L
        }
        return runCatching {
            val targetMillis = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
            (targetMillis - nowMillis).coerceAtLeast(0L)
        }.getOrNull()
    }

    /**
     * The delay to use before the next attempt: the server's own [retryAfterMillis] when it gave one
     * (Yahoo telling us exactly when it will listen again beats any guess of ours), else the previous
     * exponential fallback keyed by [attempt] (1s, 2s, 3s, ...). Either way, clamped to [capMillis] —
     * honouring the header must not become the same "park the app" failure mode it exists to fix.
     */
    fun backoffDelayMillis(attempt: Int, retryAfterMillis: Long?, capMillis: Long = MAX_RETRY_AFTER_MILLIS): Long {
        val requested = retryAfterMillis ?: ((attempt + 1) * 1000L)
        return requested.coerceIn(0L, capMillis)
    }

    /**
     * Whether it's worth sleeping after this attempt. False on the last attempt of [maxAttempts]:
     * sleeping after the final, already-exhausted try just burns time waiting for a next attempt
     * that will never happen.
     */
    fun shouldSleepAfterAttempt(attempt: Int, maxAttempts: Int = MAX_ATTEMPTS): Boolean =
        attempt < maxAttempts - 1

    /**
     * Whether a failure with this [statusCode] should be allowed to retry the same request against a
     * fallback host (Yahoo's query1 → query2). A 429 is the caller being rate-limited — a property of
     * the caller's IP, not a fact about query1 in particular — so retrying the identical ladder
     * against query2 would only double the request volume at exactly the moment the remote is asking
     * for less of it. Every other failure (5xx, timeout, garbled body) is a per-host problem the other
     * host may not share, so those still fail over.
     */
    fun shouldFailoverToOtherHost(statusCode: Int): Boolean = statusCode != 429

    /**
     * Whether a host's circuit breaker is currently open (still cooling down). [trippedAtMillis] is
     * the wall-clock time the breaker last tripped, or null if it never has. There is no separate
     * "close" action: the breaker simply stops matching once [cooldownMillis] has elapsed since the
     * trip, so a call after that point sees it as closed again.
     */
    fun isBreakerOpen(nowMillis: Long, trippedAtMillis: Long?, cooldownMillis: Long = BREAKER_COOLDOWN_MILLIS): Boolean =
        trippedAtMillis != null && nowMillis - trippedAtMillis < cooldownMillis

    /**
     * Milliseconds remaining before an open breaker clears, floored at 0 so a caller can drop this
     * straight into a "try again in Ns" message without checking the sign first.
     */
    fun breakerCooldownRemainingMillis(
        nowMillis: Long,
        trippedAtMillis: Long,
        cooldownMillis: Long = BREAKER_COOLDOWN_MILLIS,
    ): Long = (cooldownMillis - (nowMillis - trippedAtMillis)).coerceAtLeast(0L)
}
