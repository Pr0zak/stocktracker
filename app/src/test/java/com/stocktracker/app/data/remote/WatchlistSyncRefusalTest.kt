package com.stocktracker.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OPS-3 (app half): a sync refused by the backend's removal guard comes back as HTTP 409 with a
 * `{"detail": {...}}` body (see ~/stocktracker-signals app/settings_store.py
 * `WatchlistSyncRefused.detail()`). [watchlistSyncRefusal] is what turns that body into something
 * the Settings screen's confirm/cancel dialog can render — this is the parsing half of that, kept
 * separate from anything Compose or network so it's checkable directly.
 */
class WatchlistSyncRefusalTest {

    // A body shaped exactly like the backend's real WatchlistSyncRefused.detail() output for the
    // 2026-09-11 incident (54 symbols culled to 14 by a client that had never synced before).
    private val incidentShapedBody = """
        {"detail": {
            "error": "watchlist_sync_refused",
            "field": "watchlist",
            "n_before": 54,
            "n_after": 14,
            "removed_count": 40,
            "removed": ["AAPL", "MSFT", "NVDA"],
            "threshold": 5,
            "message": "refused watchlist sync: would remove 40 symbols (n_before=54, n_after=14, threshold=5) from a client that didn't write the current list; resend with replace=true to force"
        }}
    """.trimIndent()

    @Test fun `parses a real 409 body into its fields`() {
        val refusal = watchlistSyncRefusal(HttpStatusException(409, "http://x/api/settings", incidentShapedBody))
        assertEquals("watchlist", refusal?.field)
        assertEquals(54, refusal?.nBefore)
        assertEquals(14, refusal?.nAfter)
        assertEquals(40, refusal?.removedCount)
        assertEquals(listOf("AAPL", "MSFT", "NVDA"), refusal?.removed)
        assertEquals(5, refusal?.threshold)
    }

    @Test fun `a non-409 status is not treated as a refusal even with the same body`() {
        assertNull(watchlistSyncRefusal(HttpStatusException(400, "http://x/api/settings", incidentShapedBody)))
    }

    @Test fun `a plain network failure is not a refusal`() {
        assertNull(watchlistSyncRefusal(java.io.IOException("timeout")))
    }

    @Test fun `null is not a refusal`() {
        assertNull(watchlistSyncRefusal(null))
    }

    @Test fun `a 409 with an unrelated body does not crash and is not a refusal`() {
        assertNull(watchlistSyncRefusal(HttpStatusException(409, "http://x/api/settings", "{\"detail\": \"nope\"}")))
        assertNull(watchlistSyncRefusal(HttpStatusException(409, "http://x/api/settings", null)))
        assertNull(watchlistSyncRefusal(HttpStatusException(409, "http://x/api/settings", "not json at all")))
    }
}
