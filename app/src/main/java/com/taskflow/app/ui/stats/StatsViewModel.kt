package com.taskflow.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.local.CategoryCount
import com.taskflow.app.data.local.PriorityCount
import com.taskflow.app.data.model.Category
import com.taskflow.app.data.repository.CategoryRepository
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDateTime

data class StatsUiState(
    val total: Int = 0,
    val completed: Int = 0,
    val pending: Int = 0,
    val completedToday: Int = 0,
    val byPriority: List<PriorityCount> = emptyList(),
    val byCategory: List<CategoryCount> = emptyList(),
    val categories: Map<Long, Category> = emptyMap()
) {
    val completionRate: Int
        get() = if (total == 0) 0 else (completed * 100 / total)
}

class StatsViewModel(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    val state: StateFlow<StatsUiState> = combine(
        taskRepository.observeTotalCount(),
        taskRepository.observeCompletedCount(),
        taskRepository.observePendingCount(),
        taskRepository.observeCompletedOn(LocalDateTime.now()),
        taskRepository.observePriorityCounts(),
        taskRepository.observeCategoryCounts(),
        categoryRepository.observeAll()
    ) { values ->
        StatsUiState(
            total = values[0] as Int,
            completed = values[1] as Int,
            pending = values[2] as Int,
            completedToday = values[3] as Int,
            byPriority = values[4] as List<PriorityCount>,
            byCategory = values[5] as List<CategoryCount>,
            categories = (values[6] as List<Category>).associateBy { it.id }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())
}
