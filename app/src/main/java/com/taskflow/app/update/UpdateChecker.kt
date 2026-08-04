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

    private fun compare(info: UpdateInfo): UpdateCheckResult {
        val currentCode = installedVersionCode
        val latestCode = info.code
        val currentName = installedVersionName

        // Prefer numeric code when the remote publishes one.
        if (latestCode > 0) {
            return when {
                latestCode > currentCode -> UpdateCheckResult.UpdateAvailable(info)
                latestCode < currentCode -> UpdateCheckResult.LocalNewer(currentName, info.version)
                else -> UpdateCheckResult.UpToDate(currentName, info.version)
            }
        }

        // Fall back to semantic version name comparison.
        val current = SemanticVersion.parse(currentName)
        val latest = SemanticVersion.parse(info.version)
        if (current == null || latest == null) {
            return UpdateCheckResult.Error("版本号解析失败")
        }
        return when {
            latest > current -> UpdateCheckResult.UpdateAvailable(info)
            latest < current -> UpdateCheckResult.LocalNewer(currentName, info.version)
            else -> UpdateCheckResult.UpToDate(currentName, info.version)
        }
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
