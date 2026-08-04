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

enum class HomeFilter { ALL, TODAY, UPCOMING, COMPLETED }

data class HomeUiState(
    val tasks: List<Task> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
    val filter: HomeFilter = HomeFilter.ALL,
    val query: String = "",
    val pendingCount: Int = 0
) {
    val filtered: List<Task>
        get() {
            val today = LocalDate.now()
            val base = when (filter) {
                HomeFilter.ALL -> tasks
                HomeFilter.TODAY -> tasks.filter { t ->
                    !t.isCompleted && t.dueDate?.toLocalDate()?.let { it <= today } == true
                }
                HomeFilter.UPCOMING -> tasks.filter { t ->
                    !t.isCompleted && t.dueDate?.toLocalDate()?.let { it > today } == true
                }
                HomeFilter.COMPLETED -> tasks.filter { it.isCompleted }
            }
            val q = query.trim()
            return if (q.isEmpty()) base
            else base.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.description.contains(q, ignoreCase = true)
            }
        }

    val remaining: Int get() = tasks.count { !it.isCompleted }
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
