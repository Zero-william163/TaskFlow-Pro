package com.taskflow.app.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Picks update sources and download URLs based on the user's network region.
 *
 * Region is probed by racing HEAD requests to gitee.com (domestic) and api.github.com
 * (international); whichever responds first wins, defaulting to domestic on failure.
 */
class UpdateSourceManager {

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    val fetchClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** True when the device appears to be on a domestic (China) network. */
    fun probeNetworkRegion(): Boolean {
        val gitee = Thread { raceHost("https://gitee.com", Result.GITEE) }
        val github = Thread { raceHost("https://api.github.com", Result.GITHUB) }
        gitee.start(); github.start()
        gitee.join(4500); github.join(4500)
        return when (winner) {
            Result.GITHUB -> false
            Result.GITEE -> true
            null -> true // default to domestic (Gitee) when both time out
        }
    }

    private enum class Result { GITEE, GITHUB }
    @Volatile private var winner: Result? = null

    private fun raceHost(url: String, result: Result) {
        runCatching {
            probeClient.newCall(Request.Builder().url(url).head().build()).execute().use {
                if (it.isSuccessful) winner = result
            }
        }
    }

    /**
     * Sources ordered by region.
     * GitHubApiSource is prioritized over raw files because raw files (release.json)
     * can become stale if not updated, while the Releases API always returns the
     * latest published release.
     */
    fun getSortedSources(isDomestic: Boolean): List<UpdateSource> =
        if (isDomestic) {
            listOf(GiteeApiSource(), GitHubApiSource(), GiteeRawSource(), GitHubRawSource(), JSDelivrSource())
        } else {
            listOf(GitHubApiSource(), GitHubRawSource(), JSDelivrSource(), GiteeApiSource(), GiteeRawSource())
        }

    /**
     * Re-orders the resolved download URLs for the current region. Domestic users get
     * Gitee / mirror URLs first; international users get GitHub first.
     */
    fun sortDownloadUrls(urls: List<DownloadSource>, isDomestic: Boolean): List<DownloadSource> {
        fun score(s: DownloadSource): Int {
            val u = s.url.lowercase()
            val region = s.region.lowercase()
            return when {
                region == "auto" -> 5
                isDomestic && (region == "domestic" || "gitee" in u || "gh-proxy" in u || "ghfast" in u || "jsdelivr" in u) -> 0
                !isDomestic && (region == "international" || "github" in u) -> 0
                else -> 9
            }
        }
        return urls.sortedBy { score(it) }
    }

    /** Tries every source in order and returns the first successful [UpdateInfo]. */
    fun fetchLatest(): UpdateInfo? {
        val isDomestic = probeNetworkRegion()
        UpdateLogger.i("Update region: ${if (isDomestic) "domestic" else "international"}")
        for (source in getSortedSources(isDomestic)) {
            val info = runCatching { source.fetch(fetchClient) }.getOrNull()
            if (info != null) {
                UpdateLogger.i("Fetched update info from ${source.javaClass.simpleName}: ${info.version}")
                return sortDownloadUrls(info.resolvedUrls, isDomestic)
                    .let { info.copy(downloadUrls = it) }
            }
        }
        return null
    }
}
