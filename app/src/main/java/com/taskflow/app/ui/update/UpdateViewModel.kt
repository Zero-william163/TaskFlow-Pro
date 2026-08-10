package com.taskflow.app.ui.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    /** Separate flag for the startup auto-check dialog (doesn't disturb manual screen). */
    private val _autoUpdateInfo = MutableStateFlow<UpdateInfo?>(null)
    val autoUpdateInfo: StateFlow<UpdateInfo?> = _autoUpdateInfo.asStateFlow()

    val currentVersion: String get() = updateChecker.installedVersionName

    /**
     * BroadcastReceiver that listens for download result broadcasts from
     * [DownloadService]. When the download fails, updates the UI state to Error
     * so the user isn't stuck on "Downloading…" forever.
     */
    private val downloadResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadService.ACTION_DOWNLOAD_RESULT) return
            val success = intent.getBooleanExtra(DownloadService.EXTRA_DOWNLOAD_SUCCESS, false)
            if (!success) {
                val error = intent.getStringExtra(DownloadService.EXTRA_DOWNLOAD_ERROR)
                    ?: "下载失败"
                _state.value = UpdateUiState.Error(error)
            }
            // On success, DownloadService launches the system installer Activity
            // which will be on top of our UI — no state change needed here.
        }
    }

    /**
     * Register the download result receiver. Call from the screen's onResume.
     * The receiver is scoped to the application package (DownloadService sends
     * with setPackage), so it won't leak.
     */
    fun registerDownloadReceiver(context: Context) {
        context.registerReceiver(
            downloadResultReceiver,
            IntentFilter(DownloadService.ACTION_DOWNLOAD_RESULT),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregisterDownloadReceiver(context: Context) {
        runCatching { context.unregisterReceiver(downloadResultReceiver) }
    }

    /**
     * Silent background check called on app startup. Respects the 24h throttle
     * in [UpdateChecker.checkUpdateIfDue]. Only surfaces a result when an update
     * is available AND the user hasn't ignored this version. Network failures are
     * silently swallowed (returns null internally → no UI change).
     */
    fun autoCheck() {
        viewModelScope.launch {
            val result = updateChecker.checkUpdateIfDue() ?: return@launch
            if (result is UpdateCheckResult.UpdateAvailable) {
                val ignored = userPreferences.ignoredUpdateVersion.first()
                if (ignored != result.info.version) {
                    _autoUpdateInfo.value = result.info
                }
            }
            // UpToDate / LocalNewer / Error → do nothing, no popup.
        }
    }

    /** Dismiss the auto-check dialog (e.g. user clicks "稍后提醒"). */
    fun dismissAutoUpdate() {
        _autoUpdateInfo.value = null
    }

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

    override fun onCleared() {
        super.onCleared()
    }
}
