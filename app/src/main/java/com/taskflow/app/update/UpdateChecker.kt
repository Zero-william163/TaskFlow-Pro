package com.taskflow.app.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compares the installed version against the latest published one.
 *
 * Comparison prefers versionCode (numeric), falling back to semantic version name
 * comparison when the remote did not publish a code (e.g. GitHub Releases API).
 */
class UpdateChecker(private val context: Context) {

    private val manager = UpdateSourceManager()
    val preferences = UpdatePreferences(context)

    private val installedVersionCode: Int
        get() = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .let { it.longVersionCode.toInt() }
        }.getOrDefault(0)

    val installedVersionName: String
        get() = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
        }.getOrDefault("1.0.0")

    /** Background check used by the app (startup / foreground / manual button). */
    suspend fun checkUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val info = manager.fetchLatest()
                ?: return@withContext UpdateCheckResult.Error("无法获取版本信息")
            // Record check timestamp on every successful fetch.
            preferences.setLastCheckTime(System.currentTimeMillis())
            compare(info)
        }.getOrElse { UpdateCheckResult.Error(it.message ?: "检查更新失败") }
    }

    /**
     * Silent auto-check that respects the 24h throttle. Only hits the network if
     * the last check was >24h ago (or never done). Returns null when throttled
     * or when the network silently fails — callers should treat null as "no action".
     */
    suspend fun checkUpdateIfDue(): UpdateCheckResult? = withContext(Dispatchers.IO) {
        if (preferences.shouldThrottle()) return@withContext null
        runCatching {
            val info = manager.fetchLatest() ?: return@withContext null
            preferences.setLastCheckTime(System.currentTimeMillis())
            compare(info)
        }.getOrElse { null }
    }

    /**
     * Compare installed version against the remote update info.
     *
     * Strategy: prefer semantic version string comparison (e.g. "2.2.0" > "2.1.3")
     * because all sources provide a version string, while the numeric [info.code]
     * may come from different numbering schemes (e.g. release.json uses 20200
     * while build.gradle uses versionCode 31). Semver comparison is the only
     * reliable cross-source comparison method. Code comparison is only used
     * as a last resort when semver parsing fails.
     */
    private fun compare(info: UpdateInfo): UpdateCheckResult {
        val currentName = installedVersionName
        val current = SemanticVersion.parse(currentName)
        val latest = SemanticVersion.parse(info.version)

        // Primary path: semantic version comparison (works with all sources).
        if (current != null && latest != null) {
            return when {
                latest > current -> UpdateCheckResult.UpdateAvailable(info)
                latest < current -> UpdateCheckResult.LocalNewer(currentName, info.version)
                else -> UpdateCheckResult.UpToDate(currentName, info.version)
            }
        }

        // Fallback: try numeric code comparison (only when semver parsing fails).
        val currentCode = installedVersionCode
        val latestCode = info.code
        if (latestCode > 0 && currentCode > 0) {
            return when {
                latestCode > currentCode -> UpdateCheckResult.UpdateAvailable(info)
                latestCode < currentCode -> UpdateCheckResult.LocalNewer(currentName, info.version)
                else -> UpdateCheckResult.UpToDate(currentName, info.version)
            }
        }

        return UpdateCheckResult.Error("版本号解析失败")
    }
}

/** Convenience accessor for the installed version code/name without a context. */
fun Context.installedVersionCode(): Int =
    runCatching {
        packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

fun Context.installedVersionName(): String =
    runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrDefault("1.0.0")
