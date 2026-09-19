package com.stocktracker.app.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPS-3 (app half): two clients that both omit `client_id` look like the same client to the
 * backend's removal guard, so the guard only works once the app sends a stable per-install id.
 * [InstallId.resolve] is the pure "keep what's already there, else mint one" decision that
 * [SettingsStore.installId] wraps around DataStore — tested here without a Context so the actual
 * "is it stable" property is checkable directly.
 */
class InstallIdTest {

    @Test fun `an existing id is returned unchanged`() {
        assertEquals(
            "existing-install-id",
            InstallId.resolve("existing-install-id") { "should not be called" },
        )
    }

    @Test fun `a blank stored value is treated the same as absent`() {
        assertEquals("generated", InstallId.resolve("") { "generated" })
        assertEquals("generated", InstallId.resolve("   ") { "generated" })
    }

    @Test fun `absent mints via the generator`() {
        assertEquals("generated-id", InstallId.resolve(null) { "generated-id" })
    }

    @Test fun `the real generator produces a well-formed random UUID`() {
        val id = InstallId.resolve(null)
        assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test fun `two fresh installs never collapse into the same id`() {
        // The exact bug OPS-3 needs closed: without a per-install id, two clients that both omit
        // one are indistinguishable to the backend's removal guard.
        val a = InstallId.resolve(null)
        val b = InstallId.resolve(null)
        assertNotEquals(a, b)
    }

    @Test fun `stable across repeated reads once persisted`() {
        // A "read" is modelled here as calling resolve again with whatever the previous call
        // returned -- the same thing SettingsStore.installId() does against DataStore on every
        // later call, including after a process restart. It must never drift.
        val first = InstallId.resolve(null)
        val second = InstallId.resolve(first)
        val third = InstallId.resolve(second)
        assertEquals(first, second)
        assertEquals(second, third)
    }
}
