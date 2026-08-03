package com.taskflow.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "taskflow_prefs")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** App-level preferences persisted across launches, backups and reboots. */
class UserPreferences private constructor(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val WIDGET_GUIDE_SHOWN = booleanPreferencesKey("widget_guide_shown")
        val WIDGET_ADDED = booleanPreferencesKey("widget_added")
        val IGNORED_UPDATE = stringPreferencesKey("ignored_update_version")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: true }

    val widgetGuideShown: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.WIDGET_GUIDE_SHOWN] ?: false }

    val widgetAdded: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.WIDGET_ADDED] ?: false }

    val ignoredUpdateVersion: Flow<String?> =
        context.dataStore.data.map { it[Keys.IGNORED_UPDATE] }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setWidgetGuideShown(shown: Boolean) {
        context.dataStore.edit { it[Keys.WIDGET_GUIDE_SHOWN] = shown }
    }

    suspend fun setWidgetAdded(added: Boolean) {
        context.dataStore.edit { it[Keys.WIDGET_ADDED] = added }
    }

    suspend fun setIgnoredUpdateVersion(version: String?) {
        context.dataStore.edit { prefs ->
            if (version == null) prefs.remove(Keys.IGNORED_UPDATE)
            else prefs[Keys.IGNORED_UPDATE] = version
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: UserPreferences? = null

        fun get(context: Context): UserPreferences =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserPreferences(context.applicationContext).also { INSTANCE = it }
            }
    }
}
