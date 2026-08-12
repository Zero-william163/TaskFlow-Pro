package com.taskflow.app.ui.pomodoro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.repository.FocusHistoryRepository
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the Pomodoro focus screen. Owns the countdown timer (a coroutine tick,
 * not AlarmManager, so it pauses cleanly with the screen) and persists a
 * completed session to `focus_history` when the ring reaches 100%.
 *
 * @param taskId the task being focused on (used to load title + duration and
 *   to tag the recorded focus session).
 */
class PomodoroViewModel(
    private val taskId: Long,
    private val taskRepository: TaskRepository,
    private val focusHistoryRepository: FocusHistoryRepository
) : ViewModel() {

    data class UiState(
        val taskTitle: String = "专注",
        val totalSeconds: Int = 25 * 60,
        val remainingSeconds: Int = 25 * 60,
        val isRunning: Boolean = false,
        val completed: Boolean = false,
        val keepScreenOn: Boolean = false,
        val sessionsCompleted: Int = 0
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var tickJob: Job? = null

    init {
        loadTask()
    }

    private fun loadTask() {
        viewModelScope.launch {
            val task: Task? = taskRepository.getTask(taskId)
            val minutes = task?.focusDurationMinutes?.takeIf { it > 0 } ?: 25
            val secs = minutes * 60
            _state.update {
                it.copy(
                    taskTitle = task?.title?.ifBlank { "专注" } ?: "专注",
                    totalSeconds = secs,
                    remainingSeconds = secs
                )
            }
        }
    }

    fun toggleRunning() {
        if (_state.value.completed) return
        if (_state.value.isRunning) pause() else start()
    }

    private fun start() {
        _state.update { it.copy(isRunning = true) }
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (_state.value.isRunning && _state.value.remainingSeconds > 0) {
                delay(1000L)
                _state.update { it.copy(remainingSeconds = it.remainingSeconds - 1) }
            }
            if (_state.value.remainingSeconds <= 0) {
                onComplete()
            }
        }
    }

    private fun pause() {
        _state.update { it.copy(isRunning = false) }
        tickJob?.cancel()
    }

    fun reset() {
        tickJob?.cancel()
        _state.update {
            it.copy(
                isRunning = false,
                completed = false,
                remainingSeconds = it.totalSeconds
            )
        }
    }

    private fun onComplete() {
        _state.update {
            it.copy(
                isRunning = false,
                completed = true,
                sessionsCompleted = it.sessionsCompleted + 1
            )
        }
        // Persist the completed session to focus_history (spec: 倒计时完成时
        // 自动写入 focus_history 表，同步更新至统计图表).
        val minutes = _state.value.totalSeconds / 60
        viewModelScope.launch {
            focusHistoryRepository.recordSession(taskId, minutes)
        }
    }

    fun toggleKeepScreenOn() {
        _state.update { it.copy(keepScreenOn = !it.keepScreenOn) }
    }

    /** Acknowledge completion so the UI can reset the "completed" badge. */
    fun acknowledgeCompletion() {
        _state.update { it.copy(completed = false) }
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
    }

    companion object {
        /**
         * Builds a [ViewModelProvider.Factory] that injects [taskId] plus the
         * app-scoped repositories from [com.taskflow.app.ServiceLocator].
         * Used by [PomodoroScreen] via `viewModel(factory = ...)`.
         */
        fun factory(taskId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val locator = com.taskflow.app.ServiceLocator
                    return PomodoroViewModel(
                        taskId = taskId,
                        taskRepository = locator.taskRepository,
                        focusHistoryRepository = locator.focusHistoryRepository
                    ) as T
                }
            }
    }
}
