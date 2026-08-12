package com.taskflow.app.update

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A single source from which the latest version info can be fetched. All sources
 * normalize into the same [UpdateInfo] shape so the manager can try them in order.
 */
fun interface UpdateSource {
    fun fetch(client: OkHttpClient): UpdateInfo?
}

/** Shared helper that performs a GET and returns the response body text or null. */
internal fun OkHttpClient.getBody(url: String, timeoutMs: Long = 6000): String? {
    return runCatching {
        newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "TaskFlow-Updater")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }.getOrNull()
}

/** Reads release.json from Gitee raw (domestic-first). */
class GiteeRawSource : UpdateSource {
    override fun fetch(client: OkHttpClient): UpdateInfo? {
        val text = client.getBody(UpdateConfig.giteeRawUrl()) ?: return null
        return parseReleaseJson(text)
    }
}

/** Reads release.json from GitHub raw (international-first). */
class GitHubRawSource : UpdateSource {
    override fun fetch(client: OkHttpClient): UpdateInfo? {
        val text = client.getBody(UpdateConfig.githubRawUrl()) ?: return null
        return parseReleaseJson(text)
    }
}

/** Reads release.json through the jsDelivr CDN. */
class JSDelivrSource : UpdateSource {
    override fun fetch(client: OkHttpClient): UpdateInfo? {
        val text = client.getBody(UpdateConfig.jsDelivrUrl()) ?: return null
        return parseReleaseJson(text)
    }
}

/**
 * Reads release.json from GitHub Raw through the gh-proxy.com domestic mirror.
 * Bypasses both the raw.githubusercontent.com Great-Firewall block AND the
 * jsDelivr CDN multi-day cache, so domestic users always see the real latest
 * version within minutes of a push.
 */
class GhProxyRawSource : UpdateSource {
    override fun fetch(client: OkHttpClient): UpdateInfo? {
        val text = client.getBody(UpdateConfig.ghProxyRawUrl()) ?: return null
        return parseReleaseJson(text)
    }
}

/** Same as [GhProxyRawSource] but using the ghfast.top CDN mirror. */
class GhFastRawSource : UpdateSource {
    override fun fetch(client: OkHttpClient): UpdateInfo? {
        val text = client.getBody(UpdateConfig.ghFastRawUrl()) ?: return null
        return parseReleaseJson(text)
    }
}

/** Same as [GhProxyRawSource] but using the gh-proxy.org mirror. */
class GhProxyOrgRawSource : UpdateSource {
    override fun fetch(client: OkHttpClient): UpdateInfo? {
        val text = client.getBody(UpdateConfig.ghProxyOrgRawUrl()) ?: return null
        return parseReleaseJson(text)
    }
}

/** Queries the GitHub Releases API and maps the latest release to [UpdateInfo]. */
class GitHubApiSource : UpdateSource {
    override fun fetch(client: OkHttpClient): UpdateInfo? {
        val text = client.getBody(UpdateConfig.githubApiUrl()) ?: return null
        val release = runCatching { updateJson.decodeFromString<GitHubRelease>(text) }.getOrNull()
            ?: return null
        val tag = release.tagName?.removePrefix("v").orEmpty()
        if (tag.isBlank()) return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        val ghUrl = apk?.browserDownloadUrl
        // Build download URLs: GitHub direct + domestic mirrors.
        // When GitHubApiSource wins over raw sources, release.json mirrors
        // would be lost — so we inject them here to ensure domestic users
        // always have fallback URLs.
        val sources = mutableListOf<DownloadSource>()
        if (ghUrl != null) {
            val proxyOrgUrl = ghUrl.replace("gh-proxy.com", "gh-proxy.org")
            sources.add(DownloadSource(name = "GH Proxy", url = "https://gh-proxy.com/$ghUrl", region = "domestic"))
            sources.add(DownloadSource(name = "GH Fast", url = "https://ghfast.top/$ghUrl", region = "cdn"))
            sources.add(DownloadSource(name = "GH Proxy Org", url = "https://gh-proxy.org/$ghUrl", region = "domestic"))
            sources.add(DownloadSource(name = "GitHub", url = ghUrl, region = "international"))
        }
        // code = 0 forces SemanticVersion comparison in UpdateChecker.compare(),
        // avoiding mismatch between tag-derived codes and build.gradle versionCode.
        return UpdateInfo(
            version = tag,
            code = 0,
            apk = ghUrl,
            log = release.body.orEmpty(),
            size = apk?.size,
            downloadUrls = sources,
            versionTag = release.tagName
        )
    }
}

/** Queries the Gitee Releases API (domestic fallback). */
class GiteeApiSource : UpdateSource {
    override fun fetch(client: OkHttpClient): UpdateInfo? {
        val text = client.getBody(UpdateConfig.giteeApiUrl()) ?: return null
        val release = runCatching { updateJson.decodeFromString<GitHubRelease>(text) }.getOrNull()
            ?: return null
        val tag = release.tagName?.removePrefix("v").orEmpty()
        if (tag.isBlank()) return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        return UpdateInfo(
            version = tag,
            code = 0,
            apk = apk?.browserDownloadUrl,
            log = release.body.orEmpty(),
            size = apk?.size,
            versionTag = release.tagName
        )
    }
}
