package com.stocktracker.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads a release APK and hands it to the system PackageInstaller via a VIEW intent.
 * The user sees Android's normal install prompt (and, on first use, the one-time
 * "allow installs from this source" toggle — gated by REQUEST_INSTALL_PACKAGES).
 */
class ApkInstaller(private val context: Context) {

    suspend fun download(url: String, version: String, expectedBytes: Long = 0L): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "updates").apply { mkdirs() }
            val dest = File(dir, "stocktracker-$version.apk")
            if (dest.exists()) dest.delete()
            val request = Request.Builder().url(url).header("User-Agent", "StockTracker-Android").build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
                val bytes = response.body ?: throw IOException("Empty download body")
                bytes.byteStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            }
            runCatching { validateDownloadSize(expectedBytes, dest.length()) }
                .onFailure { dest.delete(); throw it } // don't leave a truncated APK behind for a retry to trip over
            dest
        }

    fun install(apk: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    companion object {
        // The APK is ~15MB and this is the only caller that ever moves a file that size — Http.client
        // is shared with the quote endpoints and its 20s callTimeout exists precisely to fail those
        // fast, so it cannot also serve a payload this large without either regressing the quote path
        // or timing out every download that isn't on fast wifi (which was the original bug here).
        private val downloadClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS) // stall detector between chunks, not a whole-file ceiling
                .writeTimeout(60, TimeUnit.SECONDS)
                // Deliberately no callTimeout: a 15MB file on a slow connection can legitimately take
                // minutes end-to-end, and a hard ceiling here just reintroduces the bug under test.
                .build()
        }
    }
}

/**
 * Throws if [actualBytes] doesn't match the size GitHub reported for the asset ([expectedBytes]).
 * A truncated transfer (connection dropped mid-download, proxy cut it short, disk full) otherwise
 * reaches the installer looking like a corrupt-but-plausible APK instead of an obvious failure.
 *
 * [expectedBytes] of 0 or less means the release API didn't report a size — nothing to check
 * against, so this passes rather than rejecting every download.
 */
internal fun validateDownloadSize(expectedBytes: Long, actualBytes: Long) {
    if (expectedBytes > 0 && actualBytes != expectedBytes) {
        throw IOException(
            "Downloaded $actualBytes bytes but GitHub reported $expectedBytes — the APK is truncated"
        )
    }
}
