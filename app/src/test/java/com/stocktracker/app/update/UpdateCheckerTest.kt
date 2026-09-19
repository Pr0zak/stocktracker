package com.stocktracker.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateChecker.shouldPrompt] is the pure decision behind persisting a "Later" tap (the actual
 * DataStore read/write lives in SettingsStore and needs a Context, so it isn't covered here) --
 * this pins that a dismissal survives a cold start without swallowing a further release.
 */
class UpdateCheckerTest {

    private fun update(version: String) = UpdateChecker.Update(
        newVersion = version,
        current = "1.0.0",
        apkUrl = "https://example.com/app.apk",
        releaseUrl = null,
        notes = null,
    )

    @Test
    fun `nothing dismissed yet always prompts`() {
        assertTrue(UpdateChecker.shouldPrompt(update("1.1.0"), dismissedVersion = null))
    }

    @Test
    fun `the exact version dismissed with Later is suppressed`() {
        assertFalse(UpdateChecker.shouldPrompt(update("1.1.0"), dismissedVersion = "1.1.0"))
    }

    @Test
    fun `a newer release after a dismissal still prompts`() {
        // Choosing "Later" on 1.1.0 must not silently swallow 1.2.0 once that ships too.
        assertTrue(UpdateChecker.shouldPrompt(update("1.2.0"), dismissedVersion = "1.1.0"))
    }

    @Test
    fun `dismissing an older version does not suppress the current one`() {
        assertTrue(UpdateChecker.shouldPrompt(update("1.1.0"), dismissedVersion = "1.0.5"))
    }
}
