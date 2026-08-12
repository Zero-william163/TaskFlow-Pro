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
 * Behavior added in the focus-engine refactor:
 * - **Auto-start**: as soon as the task loads, the focus countdown begins
 *   automatically (spec: 只要从主页/小组件点击卡片主体进入 PomodoroScreen,
 *   专注倒计时自动开启).
 * - **Pause-limit timer**: when the user hits "暂停", a separate countdown
 *   (driven by [Task.pauseLimitMinutes]) starts and the UI shows a modal. When
 *   the pause-limit hits 0 the focus auto-resumes so the user can't stall
 *   indefinitely (spec: 倒计时过程中点击"暂停"按钮, 立即弹窗显示暂停限制倒计时).
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
        val sessionsCompleted: Int = 0,
        // ====== Pause-limit timer state ======
        /** Total pause seconds allowed (read from Task.pauseLimitMinutes). */
        val pauseLimitTotalSeconds: Int = 2 * 60,
        /** Remaining pause seconds in the current pause window. */
        val pauseRemainingSeconds: Int = 2 * 60,
        /** True while the pause-limit countdown is active (modal shown). */
        val isPausing: Boolean = false,
        /** True once the initial task load + auto-start has fired. */
        val initialized: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var pauseJob: Job? = null

    init {
        loadTask()
    }

    private fun loadTask() {
        viewModelScope.launch {
            val task: Task? = taskRepository.getTask(taskId)
            val focusMinutes = task?.focusDurationMinutes?.takeIf { it > 0 } ?: 25
            val pauseMinutes = task?.pauseLimitMinutes?.takeIf { it > 0 } ?: 2
            val focusSecs = focusMinutes * 60
            val pauseSecs = pauseMinutes * 60
            _state.update {
                it.copy(
                    taskTitle = task?.title?.ifBlank { "专注" } ?: "专注",
                    totalSeconds = focusSecs,
                    remainingSeconds = focusSecs,
                    pauseLimitTotalSeconds = pauseSecs,
                    pauseRemainingSeconds = pauseSecs,
                    initialized = true
                )
            }
            // ====== Auto-start (spec: 进页自动倒计时 Auto-Start) ======
            // Only fire once, right after the task loads.
            start()
        }
    }

    fun toggleRunning() {
        if (_state.value.completed) return
        if (_state.value.isRunning) {
            // ====== Pause → open the pause-limit modal + start its countdown ======
            beginPause()
        } else {
            resumeFromPause()
        }
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

    /**
     * Pause the focus countdown and immediately open the pause-limit modal
     * with a fresh countdown (read from [UiState.pauseLimitTotalSeconds]).
     */
    private fun beginPause() {
        _state.update {
            it.copy(
                isRunning = false,
                isPausing = true,
                pauseRemainingSeconds = it.pauseLimitTotalSeconds
            )
        }
        tickJob?.cancel()
        pauseJob?.cancel()
        pauseJob = viewModelScope.launch {
            while (_state.value.isPausing && _state.value.pauseRemainingSeconds > 0) {
                delay(1000L)
                _state.update { it.copy(pauseRemainingSeconds = it.pauseRemainingSeconds - 1) }
            }
            // Pause-limit expired → auto-resume focus so the user can't stall.
            if (_state.value.pauseRemainingSeconds <= 0) {
                resumeFromPause()
            }
        }
    }

    /**
     * Manually resume focus from the pause modal (user tapped "▶ 继续专注").
     * Also invoked automatically when the pause-limit countdown hits 0.
     */
    fun resumeFromPause() {
        pauseJob?.cancel()
        _state.update {
            it.copy(
                isPausing = false,
                pauseRemainingSeconds = it.pauseLimitTotalSeconds
            )
        }
        start()
    }

    fun reset() {
        tickJob?.cancel()
        pauseJob?.cancel()
        _state.update {
            it.copy(
                isRunning = false,
                isPausing = false,
                completed = false,
                remainingSeconds = it.totalSeconds,
                pauseRemainingSeconds = it.pauseLimitTotalSeconds
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

    /**
     * Mark the current task as completed (提前完成) and stop the timer.
     * Called when the user chooses "标记已完成并退出" in the exit confirmation dialog.
     */
    fun markTaskCompletedAndStop() {
        tickJob?.cancel()
        pauseJob?.cancel()
        _state.update { it.copy(isRunning = false, isPausing = false, completed = true) }
        viewModelScope.launch {
            val task = taskRepository.getTask(taskId)
            if (task != null) {
                taskRepository.setCompleted(task, true)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
        pauseJob?.cancel()
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
