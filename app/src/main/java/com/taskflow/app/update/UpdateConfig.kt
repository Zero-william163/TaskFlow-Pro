package com.taskflow.app.update

/**
 * Repository coordinates for the auto-update system.
 *
 * Change these to match the actual GitHub / Gitee repository that hosts your
 * `release.json` and release APK assets. Both `release.json` (read from the repo
 * root via raw / CDN) and the native Releases API are supported.
 */
object UpdateConfig {
    const val GITHUB_OWNER = "Zero-william163"
    const val GITHUB_REPO = "TaskFlow-Pro"

    const val GITEE_OWNER = "Zero-william163"
    const val GITEE_REPO = "TaskFlow-Pro"

    const val RELEASE_FILE = "release.json"
    const val DEFAULT_BRANCH = "main"

    /** GitHub raw file url. */
    fun githubRawUrl(file: String = RELEASE_FILE): String =
        "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$DEFAULT_BRANCH/$file"

    /** Gitee raw file url (domestic). */
    fun giteeRawUrl(file: String = RELEASE_FILE): String =
        "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/raw/$DEFAULT_BRANCH/$file"

    /** jsDelivr CDN url mirroring the GitHub repo. */
    fun jsDelivrUrl(file: String = RELEASE_FILE): String =
        "https://cdn.jsdelivr.net/gh/$GITHUB_OWNER/$GITHUB_REPO@$DEFAULT_BRANCH/$file"

    /** GitHub Raw file wrapped by gh-proxy.com (domestic-accessible mirror, no CDN cache). */
    fun ghProxyRawUrl(file: String = RELEASE_FILE): String =
        "https://gh-proxy.com/https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$DEFAULT_BRANCH/$file"

    /** GitHub Raw file wrapped by ghfast.top (domestic-accessible, no cache). */
    fun ghFastRawUrl(file: String = RELEASE_FILE): String =
        "https://ghfast.top/https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$DEFAULT_BRANCH/$file"

    /** GitHub Raw file wrapped by gh-proxy.org (domestic fallback). */
    fun ghProxyOrgRawUrl(file: String = RELEASE_FILE): String =
        "https://gh-proxy.org/https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$DEFAULT_BRANCH/$file"

    /** GitHub Releases API (latest). */
    fun githubApiUrl(): String =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /** Gitee Releases API (latest). */
    fun giteeApiUrl(): String =
        "https://gitee.com/api/v5/repos/$GITEE_OWNER/$GITEE_REPO/releases/latest"
}
