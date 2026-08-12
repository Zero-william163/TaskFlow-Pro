package com.taskflow.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "taskflow_prefs")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 交互音效类型。每项对应 SoundEffectManager 中合成的一段短促轻量音色。
 * - WOOD_FISH  清脆木鱼  : 低频短促敲击
 * - MECHANICAL 机械轴体  : 高频清脆 click
 * - BUBBLE     柔和气泡  : 中频软 pop
 * - TICK       经典滴答  : 时钟锐利 tick
 */
enum class SoundType(val key: String, val label: String) {
    WOOD_FISH("wood_fish", "清脆木鱼"),
    MECHANICAL("mechanical", "机械轴体"),
    BUBBLE("bubble", "柔和气泡"),
    TICK("tick", "经典滴答"),
    CUSTOM("custom", "自定义");

    companion object {
        fun fromKey(key: String?): SoundType =
            entries.firstOrNull { it.key == key } ?: WOOD_FISH
    }
}

/** App-level preferences persisted across launches, backups and reboots. */
class UserPreferences private constructor(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val WIDGET_GUIDE_SHOWN = booleanPreferencesKey("widget_guide_shown")
        val IGNORED_UPDATE = stringPreferencesKey("ignored_update_version")
        val LAST_UPDATE_CHECK_TIME = longPreferencesKey("last_update_check_time")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        // ====== 交互音效 (SoundEffectManager) ======
        // SOUND_ENABLED: 总开关 (默认开启)
        // SOUND_TYPE: 音效类型 (wood_fish / mechanical / bubble / tick)
        // SOUND_VOLUME: 0~100 音量百分比 (默认 70)
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val SOUND_TYPE = stringPreferencesKey("sound_type")
        val SOUND_VOLUME = intPreferencesKey("sound_volume")
        val SOUND_CUSTOM_URI = stringPreferencesKey("sound_custom_uri")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: true }

    val widgetGuideShown: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.WIDGET_GUIDE_SHOWN] ?: false }

    val ignoredUpdateVersion: Flow<String?> =
        context.dataStore.data.map { it[Keys.IGNORED_UPDATE] }

    val lastUpdateCheckTime: Flow<Long> =
        context.dataStore.data.map { it[Keys.LAST_UPDATE_CHECK_TIME] ?: 0L }

    val onboardingCompleted: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ONBOARDING_COMPLETED] ?: false }

    // ====== 交互音效偏好 ======
    /** 音效总开关 (默认开启). */
    val soundEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SOUND_ENABLED] ?: true }

    /** 当前选中的音效类型 (默认 清脆木鱼). */
    val soundType: Flow<SoundType> =
        context.dataStore.data.map { SoundType.fromKey(it[Keys.SOUND_TYPE]) }

    /** 音效音量 0~100 (默认 70). */
    val soundVolume: Flow<Int> =
        context.dataStore.data.map { it[Keys.SOUND_VOLUME] ?: 70 }

    /** 自定义音效文件 URI (content:// 格式, 由文件选择器写入). */
    val soundCustomUri: Flow<String?> =
        context.dataStore.data.map { it[Keys.SOUND_CUSTOM_URI] }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setWidgetGuideShown(shown: Boolean) {
        context.dataStore.edit { it[Keys.WIDGET_GUIDE_SHOWN] = shown }
    }

    suspend fun setIgnoredUpdateVersion(version: String?) {
        context.dataStore.edit { prefs ->
            if (version == null) prefs.remove(Keys.IGNORED_UPDATE)
            else prefs[Keys.IGNORED_UPDATE] = version
        }
    }

    suspend fun setLastUpdateCheckTime(timestamp: Long) {
        context.dataStore.edit { it[Keys.LAST_UPDATE_CHECK_TIME] = timestamp }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUND_ENABLED] = enabled }
    }

    suspend fun setSoundType(type: SoundType) {
        context.dataStore.edit { it[Keys.SOUND_TYPE] = type.key }
    }

    suspend fun setSoundVolume(volume: Int) {
        context.dataStore.edit { it[Keys.SOUND_VOLUME] = volume.coerceIn(0, 100) }
    }

    suspend fun setSoundCustomUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri != null) prefs[Keys.SOUND_CUSTOM_URI] = uri
            else prefs.remove(Keys.SOUND_CUSTOM_URI)
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
