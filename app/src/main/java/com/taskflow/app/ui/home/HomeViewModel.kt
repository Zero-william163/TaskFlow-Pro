package com.taskflow.app.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.model.Category
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.repository.CategoryRepository
import com.taskflow.app.data.repository.FocusHistoryRepository
import com.taskflow.app.data.repository.TaskRepository
import com.taskflow.app.notification.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

enum class HomeFilter { ALL, TODAY, UPCOMING, COMPLETED, INCOMPLETE }

data class HomeUiState(
    val tasks: List<Task> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
    val filter: HomeFilter = HomeFilter.ALL,
    val query: String = "",
    val pendingCount: Int = 0,
    /** Per-task completed Pomodoro sessions today (taskId → count). Drives the
     *  card's "今日已专注 X 次" subtitle (spec §2 任务卡片视觉全美化). */
    val todayFocusCounts: Map<Long, Int> = emptyMap()
) {
    /**
     * Filtered task list with recurring-task awareness:
     * - TODAY:     今日待办 (dueDate == today for non-recurring / active today for
     *              recurring) 且 lastCompletedDate != today 且 未彻底完成
     * - UPCOMING:  未来任务 (dueDate > today, 未完成)
     * - COMPLETED: isCompleted == true 的普通任务 + 今日已打卡的周期任务
     * - INCOMPLETE:截止日期未到 + 未彻底完成
     * - ALL:       全部活跃任务（含今日已打卡的周期任务，卡片会显示「今日已完成」胶囊）
     *
     * Recurring tasks never get isCompleted=true — they stay alive and use
     * lastCompletedDate to track daily check-offs. See TaskRepository.setCompleted.
     */
    val filtered: List<Task>
        get() {
            val today = LocalDate.now()
            val todayStr = today.toString()
            val base = when (filter) {
                HomeFilter.ALL -> tasks.filter { t ->
                    // All active tasks: not permanently completed, OR recurring
                    // tasks that are checked off today (still shown with badge).
                    !t.isCompleted || t.isCompletedToday
                }
                HomeFilter.TODAY -> tasks.filter { t ->
                    !t.isCompleted &&
                    t.isDueToday &&
                    t.lastCompletedDate != todayStr
                }
                HomeFilter.UPCOMING -> tasks.filter { t ->
                    !t.isCompleted && t.dueDate?.toLocalDate()?.let { it > today } == true
                }
                HomeFilter.COMPLETED -> tasks.filter { t ->
                    // Permanently completed non-recurring tasks OR recurring
                    // tasks checked off today.
                    t.isCompleted || t.isCompletedToday
                }
                HomeFilter.INCOMPLETE -> tasks.filter { t ->
                    !t.isCompleted && (t.dueDate?.toLocalDate()?.let { it >= today } ?: true)
                }
            }
            val q = query.trim()
            return if (q.isEmpty()) base
            else base.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.description.contains(q, ignoreCase = true)
            }
        }

    val remaining: Int get() = tasks.count { !it.isCompleted && !it.isCompletedToday }

    /** Per-category counts for the chip badges (spec §3 "实时计算"). */
    val todayCount: Int get() {
        val todayStr = LocalDate.now().toString()
        return tasks.count { !it.isCompleted && it.isDueToday && it.lastCompletedDate != todayStr }
    }
    val upcomingCount: Int get() = tasks.count { t ->
        !t.isCompleted && t.dueDate?.toLocalDate()?.let { it > LocalDate.now() } == true
    }
    val completedCount: Int get() = tasks.count { it.isCompleted || it.isCompletedToday }
    val incompleteCount: Int get() = tasks.count { t ->
        !t.isCompleted && (t.dueDate?.toLocalDate()?.let { it >= LocalDate.now() } ?: true)
    }
}

class HomeViewModel(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val reminderScheduler: ReminderScheduler,
    private val focusHistoryRepository: FocusHistoryRepository
) : ViewModel() {

    private val filter = MutableStateFlow(HomeFilter.ALL)
    private val query = MutableStateFlow("")

    val state: StateFlow<HomeUiState> =
        combine(
            taskRepository.observeAll(),
            categoryRepository.observeAll(),
            filter,
            query,
            // 今日每任务已完成专注次数 (taskId → count)，驱动卡片「今日已专注 X 次」。
            focusHistoryRepository.observeTodayFocusCounts(LocalDate.now().toString())
        ) { tasks, categories, f, q, focusCounts ->
            HomeUiState(
                tasks = tasks,
                categories = categories.associateBy { it.id },
                filter = f,
                query = q,
                pendingCount = tasks.count { !it.isCompleted },
                todayFocusCounts = focusCounts.associate { it.taskId to it.cnt }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun setFilter(f: HomeFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }

    fun toggleComplete(task: Task) {
        viewModelScope.launch {
            if (task.isRecurring) {
                // Recurring task: toggle the daily check-off, not isCompleted.
                val checkOff = !task.isCompletedToday
                taskRepository.setCompleted(task, checkOff)
                if (checkOff) {
                    // Keep the reminder alive — the task recurs tomorrow.
                    // Only cancel the immediate firing; the scheduler will
                    // reschedule for the next occurrence.
                }
            } else {
                val completed = !task.isCompleted
                taskRepository.setCompleted(task, completed)
                if (completed) reminderScheduler.cancel(task.id)
                else task.reminderTime?.let { reminderScheduler.schedule(task) }
            }
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            reminderScheduler.cancel(task.id)
            taskRepository.deleteTask(task.id)
        }
    }

    /**
     * Permanently deletes every permanently-completed (archived) task.
     * Recurring tasks (which use lastCompletedDate, not isCompleted) are
     * preserved. Called from the "清空全部已完成" button on the COMPLETED tab.
     */
    fun deleteAllCompletedTasks() {
        viewModelScope.launch {
            val deleted = taskRepository.deleteAllCompletedTasks()
            android.util.Log.d("HomeViewModel", "deleteAllCompletedTasks: $deleted rows")
        }
    }

    fun reschedule(task: Task) {
        if (task.reminderTime != null && !task.isCompleted) reminderScheduler.schedule(task)
    }

    /**
     * Pin a task to the home-screen widget.
     *
     * CRITICAL — this function is NOT fire-and-forget. The caller MUST pass a
     * suspend [onDone] callback and invoke pinToWidget inside a coroutine (e.g.
     * via the suspending wrapper below). Database writes happen synchronously
     * on the current coroutine so WidgetHelper.refresh inside onDone will
     * observe the newly-pinned rows.
     */
    suspend fun pinToWidgetAndThen(taskId: Long, onDone: suspend () -> Unit = {}) {
        taskRepository.setPinnedToWidget(taskId, true)
        Log.d("HomeViewModel", "pinToWidgetAndThen: id=$taskId → DB write complete")
        onDone()
    }

    /** Legacy async wrapper — retained for backward compatibility. */
    fun pinToWidget(taskId: Long, onDone: suspend () -> Unit = {}) {
        viewModelScope.launch {
            pinToWidgetAndThen(taskId, onDone)
        }
    }
}
