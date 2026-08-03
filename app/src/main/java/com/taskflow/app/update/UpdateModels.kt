package com.taskflow.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Update data models. The on-disk [release.json] is the source of truth and is read
 * from the repository root (via raw / CDN). GitHub Releases API is used as a fallback.
 *
 * Supported release.json shape (extended from the minimal spec, backward compatible):
 * {
 *   "version": "1.2.0",
 *   "code": 12,
 *   "apk": "https://.../app-release.apk",   // single url OR omitted when downloadUrls given
 *   "log": "新增 Widget",
 *   "sha256": "...",                         // optional
 *   "size": 11458564,                        // optional
 *   "downloadUrls": [ { "name":"...", "url":"...", "region":"domestic" } ] // optional
 * }
 */
@Serializable
data class UpdateInfo(
    val version: String,
    val code: Int,
    val apk: String? = null,
    val log: String = "",
    val sha256: String? = null,
    val size: Long? = null,
    val downloadUrls: List<DownloadSource> = emptyList(),
    val versionTag: String? = null
) {
    /** All usable download URLs, single `apk` first then explicit list. */
    val resolvedUrls: List<DownloadSource>
        get() {
            val result = mutableListOf<DownloadSource>()
            apk?.takeIf { it.isNotBlank() }?.let {
                result.add(DownloadSource(name = "Primary", url = it, region = "auto"))
            }
            result.addAll(downloadUrls)
            return result
        }
}

@Serializable
data class DownloadSource(
    val name: String,
    val url: String,
    val region: String = "auto"
)

@Serializable
internal data class GitHubRelease(
    val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
internal data class GitHubAsset(
    val name: String,
    val browserDownloadUrl: String? = null,
    val size: Long? = null
) {
    @SerialName("browser_download_url")
    val browserDownloadUrlSafe: String? get() = browserDownloadUrl
}

/** Result of an update check. */
sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()
    data class UpToDate(val current: String, val latest: String) : UpdateCheckResult()
    data class LocalNewer(val current: String, val latest: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

internal val updateJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Parse a release.json payload into [UpdateInfo]. */
internal fun parseReleaseJson(text: String): UpdateInfo? = runCatching {
    updateJson.decodeFromString<UpdateInfo>(text)
}.getOrNull()
