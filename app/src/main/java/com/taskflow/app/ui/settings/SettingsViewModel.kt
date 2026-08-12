package com.taskflow.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.preferences.SoundType
import com.taskflow.app.data.preferences.ThemeMode
import com.taskflow.app.data.preferences.UserPreferences
import com.taskflow.app.notification.SoundEffectManager
import com.taskflow.app.update.UpdateChecker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val versionName: String = "",
    // ====== 交互音效 ======
    val soundEnabled: Boolean = true,
    val soundType: SoundType = SoundType.WOOD_FISH,
    val soundVolume: Int = 70,
    val soundCustomUri: String? = null
)

class SettingsViewModel(
    private val userPreferences: UserPreferences,
    private val updateChecker: UpdateChecker,
    private val soundEffectManager: SoundEffectManager
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        userPreferences.themeMode,
        userPreferences.dynamicColor,
        userPreferences.soundEnabled,
        userPreferences.soundType,
        userPreferences.soundVolume,
        userPreferences.soundCustomUri
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        SettingsUiState(
            themeMode = values[0] as ThemeMode,
            dynamicColor = values[1] as Boolean,
            versionName = updateChecker.installedVersionName,
            soundEnabled = values[2] as Boolean,
            soundType = values[3] as SoundType,
            soundVolume = values[4] as Int,
            soundCustomUri = values[5] as String?
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { userPreferences.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setDynamicColor(enabled) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setSoundEnabled(enabled) }
    }

    fun setSoundType(type: SoundType) {
        viewModelScope.launch { userPreferences.setSoundType(type) }
    }

    fun setSoundVolume(volume: Int) {
        viewModelScope.launch { userPreferences.setSoundVolume(volume) }
    }

    fun setSoundCustomUri(uri: String?) {
        viewModelScope.launch { userPreferences.setSoundCustomUri(uri) }
    }

    /** 试听当前选定类型的音效 (设置页点击选项时调用). */
    fun previewSound(type: SoundType) {
        soundEffectManager.playClick(override = type)
    }
}
