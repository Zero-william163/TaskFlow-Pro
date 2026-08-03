package com.taskflow.app.update

import android.content.Context
import com.taskflow.app.data.preferences.UserPreferences
import kotlinx.coroutines.flow.first

/**
 * Update-related preferences: ignored versions. Delegates persistence to the shared
 * [UserPreferences] DataStore so it survives backups/reboots.
 */
class UpdatePreferences(private val context: Context) {

    private val prefs get() = UserPreferences.get(context)

    suspend fun setIgnoredVersion(version: String?) =
        prefs.setIgnoredUpdateVersion(version)

    suspend fun isIgnored(version: String): Boolean =
        prefs.ignoredUpdateVersion.first() == version
}
