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
     * Sources ordered by region. GitHubApiSource is placed first because the
     * Releases API always returns the latest published release, while raw
     * release.json files can become stale. However, fetchLatest() no longer
     * stops at the first success — it probes ALL sources and returns the
     * HIGHEST version found, preventing "downgrade" prompts when a domestic
     * mirror is out of date.
     *
     * Domestic users get the three GH-proxy-wrapped raw sources (gh-proxy.com,
     * ghfast.top, gh-proxy.org) early, because raw.githubusercontent.com is
     * blocked in China and jsDelivr caches branch files for up to 7 days —
     * using proxy mirrors lets domestic users immediately see new releases
     * without waiting for CDN invalidation.
     */
    fun getSortedSources(isDomestic: Boolean): List<UpdateSource> =
        if (isDomestic) {
            listOf(
                GitHubApiSource(),
                GhProxyRawSource(), GhFastRawSource(), GhProxyOrgRawSource(),
                GiteeApiSource(),
                GitHubRawSource(), GiteeRawSource(),
                JSDelivrSource()
            )
        } else {
            listOf(
                GitHubApiSource(),
                GitHubRawSource(), JSDelivrSource(),
                GhProxyRawSource(), GhFastRawSource(), GhProxyOrgRawSource(),
                GiteeApiSource(), GiteeRawSource()
            )
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

    /**
     * Tries every source, collects all successful results, and returns the
     * HIGHEST version found (via semantic version comparison). This prevents
     * "downgrade" prompts when a domestic mirror (e.g. Gitee) is out of date
     * while GitHub already has a newer release.
     *
     * Version comparison uses semantic version parsing only — mixing code
     * values from different sources (API returns 0, raw returns build code)
     * would produce incorrect ordering.
     */
    fun fetchLatest(): UpdateInfo? {
        val isDomestic = probeNetworkRegion()
        UpdateLogger.i("Update region: ${if (isDomestic) "domestic" else "international"}")
        val sources = getSortedSources(isDomestic)
        val candidates = mutableListOf<Pair<UpdateSource, UpdateInfo>>()
        for (source in sources) {
            val info = runCatching { source.fetch(fetchClient) }.getOrNull()
            if (info != null) {
                UpdateLogger.i("Fetched update info from ${source.javaClass.simpleName}: ${info.version} (code=${info.code})")
                candidates.add(source to info)
            }
        }
        if (candidates.isEmpty()) {
            UpdateLogger.w("fetchLatest: no source returned valid update info")
            return null
        }
        val best = candidates.maxByOrNull { (_, info) ->
            val sv = SemanticVersion.parse(info.version)
            if (sv != null) {
                // Weighted score: major*1_000_000 + minor*10_000 + patch
                sv.major.toLong() * 1_000_000L + sv.minor.toLong() * 10_000L + sv.patch.toLong()
            } else 0L
        }?.second
        if (best == null) {
            UpdateLogger.w("fetchLatest: could not determine best version from ${candidates.size} candidates")
            return null
        }
        UpdateLogger.i("fetchLatest: selected best version = ${best.version}")
        // Clear the `apk` field so resolvedUrls only returns the sorted list.
        // Otherwise apk (GitHub direct, region="auto") would always be first,
        // defeating the region-based sort for domestic users.
        return sortDownloadUrls(best.resolvedUrls, isDomestic)
            .let { best.copy(apk = null, downloadUrls = it) }
    }
}
