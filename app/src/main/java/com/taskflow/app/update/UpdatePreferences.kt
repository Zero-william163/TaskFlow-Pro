package com.taskflow.app.update

import android.content.Context
import com.taskflow.app.data.preferences.UserPreferences
import kotlinx.coroutines.flow.first

/**
 * Update-related preferences: ignored versions + last check timestamp.
 * Delegates persistence to the shared [UserPreferences] DataStore so it
 * survives backups/reboots.
 */
class UpdatePreferences(private val context: Context) {

    private val prefs get() = UserPreferences.get(context)

    suspend fun setIgnoredVersion(version: String?) =
        prefs.setIgnoredUpdateVersion(version)

    suspend fun isIgnored(version: String): Boolean =
        prefs.ignoredUpdateVersion.first() == version

    suspend fun getLastCheckTime(): Long =
        prefs.lastUpdateCheckTime.first()

    suspend fun setLastCheckTime(timestamp: Long) =
        prefs.setLastUpdateCheckTime(timestamp)

    /**
     * Returns true if a check has been done within [intervalMs] (default 24h),
     * meaning the caller should skip to avoid spamming the GitHub API.
     */
    suspend fun shouldThrottle(intervalMs: Long = THROTTLE_INTERVAL_MS): Boolean {
        val last = getLastCheckTime()
        if (last == 0L) return false
        return (System.currentTimeMillis() - last) < intervalMs
    }

    companion object {
        /** 24 hours in milliseconds. */
        const val THROTTLE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
