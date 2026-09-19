package com.stocktracker.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DATA-5: the 429 retry strategy used to amplify the problem it reacted to — it ignored
 * `Retry-After` entirely, it slept after the final (already-exhausted) attempt for nothing, and
 * Yahoo's query1→query2 host failover retried the *same* rate limit against a second host, doubling
 * request volume exactly when the remote was asking for less of it.
 *
 * These are the pure decision functions [RetryPolicy] extracted so this math is checkable without a
 * fake server or a real clock.
 */
class RetryPolicyTest {

    private companion object {
        // An arbitrary fixed instant, in millis -- equal to Fri, 15 Jan 2027 08:00:00 GMT.
        const val NOW = 1_800_000_000_000L
    }

    // -------- Retry-After parsing: delta-seconds form --------

    @Test fun `delta-seconds form is read as whole seconds converted to millis`() {
        assertEquals(120_000L, RetryPolicy.parseRetryAfterMillis("120", NOW))
        assertEquals(0L, RetryPolicy.parseRetryAfterMillis("0", NOW))
    }

    @Test fun `delta-seconds form tolerates surrounding whitespace`() {
        assertEquals(5_000L, RetryPolicy.parseRetryAfterMillis("  5  ", NOW))
    }

    @Test fun `a negative delta-seconds value is rejected rather than producing a negative wait`() {
        assertNull(RetryPolicy.parseRetryAfterMillis("-30", NOW))
    }

    // -------- Retry-After parsing: HTTP-date form --------

    @Test fun `HTTP-date form is read as the gap to that instant`() {
        // NOW is Fri, 15 Jan 2027 08:00:00 GMT; ask for a date 90 seconds later.
        val header = "Fri, 15 Jan 2027 08:01:30 GMT"
        assertEquals(90_000L, RetryPolicy.parseRetryAfterMillis(header, NOW))
    }

    @Test fun `an HTTP-date already in the past means wait zero, not a negative number`() {
        val header = "Fri, 15 Jan 2027 07:58:00 GMT" // 2 minutes before NOW
        assertEquals(0L, RetryPolicy.parseRetryAfterMillis(header, NOW))
    }

    // -------- Retry-After parsing: absent / unparseable --------

    @Test fun `a missing header parses to null so the caller falls back to its own backoff`() {
        assertNull(RetryPolicy.parseRetryAfterMillis(null, NOW))
    }

    @Test fun `a blank header parses to null`() {
        assertNull(RetryPolicy.parseRetryAfterMillis("", NOW))
        assertNull(RetryPolicy.parseRetryAfterMillis("   ", NOW))
    }

    @Test fun `garbage that is neither a number nor a valid HTTP-date parses to null`() {
        assertNull(RetryPolicy.parseRetryAfterMillis("not-a-real-value", NOW))
        assertNull(RetryPolicy.parseRetryAfterMillis("120 seconds please", NOW))
    }

    // -------- The cap --------

    @Test fun `a huge Retry-After is clamped to the cap, not honoured verbatim`() {
        // A mistaken or hostile header ("3600" instead of "60", say) must not be able to park the
        // app for an hour when the honest worst case is "try again shortly".
        val oneHourMillis = 3_600_000L
        assertEquals(
            RetryPolicy.MAX_RETRY_AFTER_MILLIS,
            RetryPolicy.backoffDelayMillis(attempt = 0, retryAfterMillis = oneHourMillis),
        )
    }

    @Test fun `a Retry-After under the cap is honoured exactly`() {
        assertEquals(12_000L, RetryPolicy.backoffDelayMillis(attempt = 0, retryAfterMillis = 12_000L))
    }

    @Test fun `with no Retry-After the previous exponential backoff still applies, itself capped`() {
        assertEquals(1_000L, RetryPolicy.backoffDelayMillis(attempt = 0, retryAfterMillis = null))
        assertEquals(2_000L, RetryPolicy.backoffDelayMillis(attempt = 1, retryAfterMillis = null))
        assertEquals(3_000L, RetryPolicy.backoffDelayMillis(attempt = 2, retryAfterMillis = null))
    }

    @Test fun `a custom cap is respected too, not just the default constant`() {
        assertEquals(500L, RetryPolicy.backoffDelayMillis(attempt = 0, retryAfterMillis = 10_000L, capMillis = 500L))
    }

    // -------- No sleep after the last attempt --------

    @Test fun `sleeping is worthwhile before every attempt except the last`() {
        assertTrue(RetryPolicy.shouldSleepAfterAttempt(attempt = 0, maxAttempts = 3))
        assertTrue(RetryPolicy.shouldSleepAfterAttempt(attempt = 1, maxAttempts = 3))
        assertFalse(
            "the 3rd (index 2) attempt is the last of 3 -- sleeping after it wastes time waiting " +
                "for a 4th attempt that never happens",
            RetryPolicy.shouldSleepAfterAttempt(attempt = 2, maxAttempts = 3),
        )
    }

    @Test fun `the rule holds for a single-attempt policy too`() {
        assertFalse(RetryPolicy.shouldSleepAfterAttempt(attempt = 0, maxAttempts = 1))
    }

    // -------- No retrying a 429 across the host fallback --------

    @Test fun `a 429 must not fail over to the fallback host`() {
        assertFalse(
            "retrying the identical ladder against query2 doubles request volume during a rate limit",
            RetryPolicy.shouldFailoverToOtherHost(429),
        )
    }

    @Test fun `every other failure status is still allowed to fail over`() {
        listOf(500, 502, 503, 504, 404, 400, 401, 403).forEach { code ->
            assertTrue("status $code should still fail over", RetryPolicy.shouldFailoverToOtherHost(code))
        }
    }

    // -------- Circuit breaker: opening and closing --------

    @Test fun `a host that has never tripped has a closed breaker`() {
        assertFalse(RetryPolicy.isBreakerOpen(NOW, trippedAtMillis = null))
    }

    @Test fun `a breaker is open immediately after tripping`() {
        assertTrue(RetryPolicy.isBreakerOpen(NOW, trippedAtMillis = NOW, cooldownMillis = 60_000L))
    }

    @Test fun `a breaker stays open partway through its cooldown`() {
        val trippedAt = NOW
        val partway = NOW + 30_000L
        assertTrue(RetryPolicy.isBreakerOpen(partway, trippedAt, cooldownMillis = 60_000L))
    }

    @Test fun `a breaker closes itself the instant its cooldown elapses`() {
        val trippedAt = NOW
        val cooldown = 60_000L
        assertTrue(
            "one millisecond before the cooldown ends the breaker is still open",
            RetryPolicy.isBreakerOpen(trippedAt + cooldown - 1, trippedAt, cooldown),
        )
        assertFalse(
            "once the cooldown has fully elapsed the breaker is closed again, with no explicit reset",
            RetryPolicy.isBreakerOpen(trippedAt + cooldown, trippedAt, cooldown),
        )
    }

    @Test fun `a breaker stays closed long after its cooldown too`() {
        assertFalse(RetryPolicy.isBreakerOpen(NOW + 3_600_000L, trippedAtMillis = NOW, cooldownMillis = 60_000L))
    }

    @Test fun `cooldown remaining counts down to zero and never goes negative`() {
        val trippedAt = NOW
        val cooldown = 60_000L
        assertEquals(60_000L, RetryPolicy.breakerCooldownRemainingMillis(trippedAt, trippedAt, cooldown))
        assertEquals(10_000L, RetryPolicy.breakerCooldownRemainingMillis(trippedAt + 50_000L, trippedAt, cooldown))
        assertEquals(0L, RetryPolicy.breakerCooldownRemainingMillis(trippedAt + cooldown, trippedAt, cooldown))
        assertEquals(
            "long after the cooldown, remaining time must floor at 0, not go negative",
            0L,
            RetryPolicy.breakerCooldownRemainingMillis(trippedAt + cooldown + 999_999L, trippedAt, cooldown),
        )
    }
}
