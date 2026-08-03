package com.taskflow.app.ui.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.preferences.UserPreferences
import com.taskflow.app.update.DownloadService
import com.taskflow.app.update.UpdateCheckResult
import com.taskflow.app.update.UpdateChecker
import com.taskflow.app.update.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data class Available(val info: UpdateInfo) : UpdateUiState()
    data class UpToDate(val current: String, val latest: String) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
    data object Downloading : UpdateUiState()
}

class UpdateViewModel(
    private val updateChecker: UpdateChecker,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    val currentVersion: String get() = updateChecker.installedVersionName

    fun check(context: Context) {
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            when (val result = updateChecker.checkUpdate()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    val ignored = userPreferences.ignoredUpdateVersion.first()
                    if (ignored == result.info.version) {
                        _state.value = UpdateUiState.UpToDate(updateChecker.installedVersionName, result.info.version)
                    } else {
                        _state.value = UpdateUiState.Available(result.info)
                    }
                }
                is UpdateCheckResult.UpToDate -> _state.value = UpdateUiState.UpToDate(result.current, result.latest)
                is UpdateCheckResult.LocalNewer -> _state.value = UpdateUiState.UpToDate(result.current, result.latest)
                is UpdateCheckResult.Error -> _state.value = UpdateUiState.Error(result.message)
            }
        }
    }

    fun startDownload(context: Context, info: UpdateInfo) {
        val urls = info.resolvedUrls.map { it.url }
        if (urls.isEmpty()) {
            _state.value = UpdateUiState.Error("没有可用的下载地址")
            return
        }
        _state.value = UpdateUiState.Downloading
        DownloadService.start(context, urls, info.sha256)
    }

    fun ignore(info: UpdateInfo) {
        viewModelScope.launch { userPreferences.setIgnoredUpdateVersion(info.version) }
    }
}
