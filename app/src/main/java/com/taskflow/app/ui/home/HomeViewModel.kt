package com.taskflow.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.model.Category
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.repository.CategoryRepository
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
    val pendingCount: Int = 0
) {
    /**
     * Filtered task list per spec §3:
     * - TODAY:     今天需要执行的任务 (dueDate == today, 未完成)
     * - UPCOMING:  未来任务 (dueDate > today, 未完成)
     * - COMPLETED: status == COMPLETED
     * - INCOMPLETE:截止日期未到 + status != COMPLETED (含今天/未来/无截止日期)
     * - ALL:       全部任务
     *
     * All filters are pure functions of `tasks` so any DB change (create /
     * complete / delete / edit date) flows through Flow → recompose → instant
     * UI update. Spec §3 "实时同步".
     */
    val filtered: List<Task>
        get() {
            val today = LocalDate.now()
            val base = when (filter) {
                // ALL 只显示未完成任务，已完成的归入 COMPLETED 栏目
                HomeFilter.ALL -> tasks.filter { !it.isCompleted }
                HomeFilter.TODAY -> tasks.filter { t ->
                    !t.isCompleted && t.dueDate?.toLocalDate() == today
                }
                HomeFilter.UPCOMING -> tasks.filter { t ->
                    !t.isCompleted && t.dueDate?.toLocalDate()?.let { it > today } == true
                }
                HomeFilter.COMPLETED -> tasks.filter { it.isCompleted }
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

    val remaining: Int get() = tasks.count { !it.isCompleted }

    /** Per-category counts for the chip badges (spec §3 "实时计算"). */
    val todayCount: Int get() = tasks.count { !it.isCompleted && it.dueDate?.toLocalDate() == LocalDate.now() }
    val upcomingCount: Int get() = tasks.count { t ->
        !t.isCompleted && t.dueDate?.toLocalDate()?.let { it > LocalDate.now() } == true
    }
    val completedCount: Int get() = tasks.count { it.isCompleted }
    val incompleteCount: Int get() = tasks.count { t ->
        !t.isCompleted && (t.dueDate?.toLocalDate()?.let { it >= LocalDate.now() } ?: true)
    }
}

class HomeViewModel(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {

    private val filter = MutableStateFlow(HomeFilter.ALL)
    private val query = MutableStateFlow("")

    val state: StateFlow<HomeUiState> =
        combine(
            taskRepository.observeAll(),
            categoryRepository.observeAll(),
            filter,
            query
        ) { tasks, categories, f, q ->
            HomeUiState(
                tasks = tasks,
                categories = categories.associateBy { it.id },
                filter = f,
                query = q,
                pendingCount = tasks.count { !it.isCompleted }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun setFilter(f: HomeFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }

    fun toggleComplete(task: Task) {
        viewModelScope.launch {
            val completed = !task.isCompleted
            taskRepository.setCompleted(task, completed)
            if (completed) reminderScheduler.cancel(task.id)
            else task.reminderTime?.let { reminderScheduler.schedule(task) }
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            reminderScheduler.cancel(task.id)
            taskRepository.deleteTask(task.id)
        }
    }

    fun reschedule(task: Task) {
        if (task.reminderTime != null && !task.isCompleted) reminderScheduler.schedule(task)
    }

    /** Pin a freshly-created task so it appears on the home-screen widget. */
    fun pinToWidget(taskId: Long) {
        viewModelScope.launch { taskRepository.setPinnedToWidget(taskId, true) }
    }
}
