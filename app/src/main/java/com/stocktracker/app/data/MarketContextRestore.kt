package com.stocktracker.app.data

/**
 * DATA-9 — the pure decision behind restoring a persisted nightly scan / VIX reading across a
 * process restart.
 *
 * Both used to live in memory only: [MarketContextStore] started every process at
 * `DipRadarState.Loading` and `vix = null`, so an offline cold start showed "Checking the latest
 * scan…" even when the device held a perfectly good reading from thirty minutes — or three hours —
 * earlier. Loading claims nothing is known yet; the app knew something, and said the wrong thing
 * about it.
 *
 * Kept free of Android/DataStore so the age rule itself — including the "so old it must not be
 * shown at all" edge — is unit-testable without a Context.
 */
object MarketContextRestore {

    /**
     * Long enough to survive a normal weekend or a holiday close (the market having nothing new to
     * say for three days is not the same as the reading having gone bad — a Friday-night scan and a
     * Friday VIX close are still "the last one" all the way through Monday's pre-market), short
     * enough that an install nobody has opened in a week does not resurrect ancient numbers under a
     * merely-stale label. Shared by the scan and the VIX: neither has a meaningfully different
     * answer to "how old is too old to be worth showing at all", as opposed to "too old to call
     * current" (a much shorter bar the UI's own age labelling already enforces).
     */
    const val MAX_AGE_TO_SHOW_MS = 4L * 24 * 60 * 60 * 1000

    /**
     * [value] if it is young enough to restore, else null.
     *
     * [fetchedAtMs] `<= 0` means "never actually persisted" and is never restorable regardless of
     * [nowMs] — a zero/missing timestamp must not be treated as "just now" the way an unclamped
     * subtraction would. A [nowMs] that is BEFORE [fetchedAtMs] (a clock moved backwards) is treated
     * as age zero rather than a negative age that would otherwise pass any [maxAgeMs] check by
     * accident.
     */
    fun <T> restorable(value: T?, fetchedAtMs: Long, nowMs: Long, maxAgeMs: Long = MAX_AGE_TO_SHOW_MS): T? {
        if (value == null || fetchedAtMs <= 0L) return null
        val age = (nowMs - fetchedAtMs).coerceAtLeast(0L)
        return value.takeIf { age <= maxAgeMs }
    }

    /**
     * The TTL check behind [MarketContextStore.refreshScan]/`.refreshVix`: is a held reading, last
     * confirmed at [fetchedAtMs], still recent enough that a refresh may skip the network entirely?
     *
     * Pulled out into its own pure function so this — the thing that decides whether a screen open
     * costs the self-hosted service a request at all — is unit-testable on its own, the same way
     * [restorable] is. `fetchedAtMs <= 0` (nothing held yet) is never "within TTL": there is nothing
     * to skip a refetch in favour of.
     */
    fun withinTtl(fetchedAtMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
        fetchedAtMs > 0L && nowMs - fetchedAtMs < maxAgeMs
}
