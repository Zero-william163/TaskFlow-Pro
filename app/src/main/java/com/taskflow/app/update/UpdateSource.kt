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

/** Reads release.json through the jsDelivr CDN. */
class JSDelivrSource : UpdateSource {
    override fun fetch(client: OkHttpClient): UpdateInfo? {
        val text = client.getBody(UpdateConfig.jsDelivrUrl()) ?: return null
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
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        val sources = release.assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .mapNotNull { asset ->
                asset.browserDownloadUrlSafe?.let {
                    DownloadSource(name = asset.name, url = it, region = "international")
                }
            }
        return UpdateInfo(
            version = tag,
            code = 0,
            apk = apk?.browserDownloadUrlSafe,
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
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        return UpdateInfo(
            version = tag,
            code = 0,
            apk = apk?.browserDownloadUrlSafe,
            log = release.body.orEmpty(),
            size = apk?.size,
            versionTag = release.tagName
        )
    }
}
