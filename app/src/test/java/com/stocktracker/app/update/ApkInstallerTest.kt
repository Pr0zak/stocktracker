package com.stocktracker.app.update

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [ApkInstaller.download] itself needs a real Context/OkHttp round trip and isn't covered here;
 * this pins the pure size-check it delegates to -- the part that catches a truncated transfer
 * before a corrupt-but-plausible APK reaches the system installer.
 */
class ApkInstallerTest {

    @Test
    fun `matching size passes`() {
        validateDownloadSize(expectedBytes = 14_949_395L, actualBytes = 14_949_395L)
    }

    @Test
    fun `a short download is rejected`() {
        val e = assertThrows(java.io.IOException::class.java) {
            validateDownloadSize(expectedBytes = 14_949_395L, actualBytes = 9_000_000L)
        }
        assert(e.message?.contains("9000000") == true)
        assert(e.message?.contains("14949395") == true)
    }

    @Test
    fun `an over-long download is also rejected`() {
        // Not just truncation -- any mismatch (e.g. a proxy injecting an interstitial page instead
        // of the binary) should be caught, not just "fewer bytes than expected".
        assertThrows(java.io.IOException::class.java) {
            validateDownloadSize(expectedBytes = 100L, actualBytes = 500L)
        }
    }

    @Test
    fun `an unknown expected size (0) skips the check`() {
        // GitHub's release API is expected to always report a size, but if it ever doesn't, a
        // download must not be rejected over a value we never actually had.
        validateDownloadSize(expectedBytes = 0L, actualBytes = 9_000_000L)
    }

    @Test
    fun `a negative expected size also skips the check`() {
        validateDownloadSize(expectedBytes = -1L, actualBytes = 9_000_000L)
    }
}
