package com.stocktracker.app.update

import com.stocktracker.app.BuildConfig
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/**
 * Checks GitHub Releases for a newer StockTracker build. The repo is public, so we hit the API
 * directly with no token (unauthenticated: 60 req/hr/IP — plenty for a launch-time check).
 */
object UpdateChecker {

    private const val OWNER = "Pr0zak"
    private const val REPO = "stocktracker"

    data class Update(
        val newVersion: String,
        val current: String,
        val apkUrl: String,
        val releaseUrl: String?,
        val notes: String?,
    )

    /** Returns an [Update] if the latest release is newer than the running build, else null. */
    suspend fun check(): Update? = runCatching {
        val body = Http.getString("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
        val release = Http.json.decodeFromString<GitHubRelease>(body)
        val latest = release.tagName?.removePrefix("v")?.trim().takeUnless { it.isNullOrBlank() }
            ?: return@runCatching null
        val current = BuildConfig.VERSION_NAME
        if (compareSemver(latest, current) <= 0) return@runCatching null

        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return@runCatching null

        Update(
            newVersion = latest,
            current = current,
            apkUrl = apk.browserDownloadUrl,
            releaseUrl = release.htmlUrl,
            notes = release.body?.take(800)?.trim(),
        )
    }.getOrNull()

    /** Positive if a > b, negative if a < b, 0 if equal (ignores any -suffix). */
    private fun compareSemver(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)
