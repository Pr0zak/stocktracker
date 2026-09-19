package com.stocktracker.app.data.prefs

import java.util.UUID

/**
 * The per-install id this app sends as `client_id` on every watchlist sync (OPS-3 — see
 * ~/stocktracker-signals app/settings_store.py). It exists so the backend can tell "the same
 * install pruning its own watchlist" apart from "a different install silently overwriting it" —
 * the 2026-09-11 incident where an emulator's 14-name debug list clobbered the real 54-name one
 * right before the nightly scan.
 *
 * Deliberately NOT derived from ANDROID_ID, the advertising id, or any other hardware- or
 * user-identifying source: the backend only ever compares two opaque ids for equality, never
 * displays one, and a value that could be traced back to a device or a person is more than this
 * guard needs.
 *
 * [resolve] is split out as pure logic — "keep what's already there, else mint one" — so it's
 * testable without a DataStore-backed Context. [SettingsStore.installId] is the persisted wrapper
 * around it: generated once, on first use, and stable across every later read (including app
 * restarts).
 */
object InstallId {
    fun resolve(existing: String?, generate: () -> String = { UUID.randomUUID().toString() }): String =
        if (!existing.isNullOrBlank()) existing else generate()
}
