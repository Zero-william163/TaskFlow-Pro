package com.taskflow.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.preferences.ThemeMode
import com.taskflow.app.data.preferences.UserPreferences
import com.taskflow.app.update.UpdateChecker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val versionName: String = ""
)

class SettingsViewModel(
    private val userPreferences: UserPreferences,
    private val updateChecker: UpdateChecker
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        userPreferences.themeMode,
        userPreferences.dynamicColor
    ) { mode, dynamic ->
        SettingsUiState(themeMode = mode, dynamicColor = dynamic, versionName = updateChecker.installedVersionName)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { userPreferences.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setDynamicColor(enabled) }
    }
}
