package com.stocktracker.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.stocktracker.app.data.remote.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads a release APK and hands it to the system PackageInstaller via a VIEW intent.
 * The user sees Android's normal install prompt (and, on first use, the one-time
 * "allow installs from this source" toggle — gated by REQUEST_INSTALL_PACKAGES).
 */
class ApkInstaller(private val context: Context) {

    suspend fun download(url: String, version: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val dest = File(dir, "stocktracker-$version.apk")
        if (dest.exists()) dest.delete()
        val request = Request.Builder().url(url).header("User-Agent", "StockTracker-Android").build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val bytes = response.body ?: throw IOException("Empty download body")
            bytes.byteStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        }
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
}
