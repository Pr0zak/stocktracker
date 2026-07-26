package com.stocktracker.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test

/**
 * Regression guard for the v0.50.0 health-poll busy-spin.
 *
 * `SignalsHealth.start()` sleeps on `select { wake.onReceive; onTimeout(naptime) }`, and `check()`'s
 * own failure path ends in `reportFailure` → `wake.trySend(Unit)`. Because `wake` is CONFLATED, a
 * `trySend` with no receiver parked BUFFERS the element rather than dropping it — so by the time the
 * loop reached `select`, a wake was already pending, `onReceive` won instantly, and the loop probed
 * again with no delay. While the backend was unreachable it never slept at all: measured at over 11
 * million iterations in 3 seconds against a connection-refused backend, burning a core and strobing
 * the banner for as long as the service stayed down.
 *
 * The fix drains stale wakes before sleeping. This test reproduces both loops literally so the
 * property is enforced rather than argued about.
 */
class HealthPollLoopTest {

    /** The real loop shape, with `check()` standing in as an instantly-failing probe. */
    private fun runLoop(drainStaleWakes: Boolean, forMs: Long): Long = runBlocking {
        val probes = AtomicLong()
        val wake = Channel<Unit>(Channel.CONFLATED)
        val job = launch(Dispatchers.Default) {
            while (isActive) {
                probes.incrementAndGet()          // check() — fails immediately, as on ECONNREFUSED
                wake.trySend(Unit)                // ...which routes through reportFailure()
                val naptime = POLL_WHEN_OFFLINE_MS
                if (drainStaleWakes) {
                    while (wake.tryReceive().isSuccess) { /* discard */ }
                }
                select<Unit> {
                    wake.onReceive { }
                    onTimeout(naptime) { }
                }
            }
        }
        delay(forMs)
        job.cancelAndJoin()
        probes.get()
    }

    @Test
    fun `offline poll respects its cadence instead of spinning`() {
        val probes = runLoop(drainStaleWakes = true, forMs = RUN_MS)
        // With a 30s cadence and a 3s run there is exactly one probe: the initial one.
        assertTrue(
            "offline poll ran $probes times in ${RUN_MS}ms — it is spinning, not sleeping",
            probes <= 2,
        )
    }

    @Test
    fun `the un-drained loop really does spin (guards the test itself)`() {
        // If this ever stops holding, the test above has stopped proving anything — either the
        // reproduction drifted from the real loop or Channel/select semantics changed.
        val probes = runLoop(drainStaleWakes = false, forMs = RUN_MS)
        assertTrue(
            "expected the un-drained loop to spin, but it only ran $probes times — reproduction is stale",
            probes > 1_000,
        )
    }

    private companion object {
        const val POLL_WHEN_OFFLINE_MS = 30_000L   // mirrors SignalsHealth
        const val RUN_MS = 3_000L
    }
}
