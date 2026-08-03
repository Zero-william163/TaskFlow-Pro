package com.taskflow.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.model.Category
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.repository.CategoryRepository
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth

data class CalendarUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val month: YearMonth = YearMonth.now(),
    val tasksByDate: Map<LocalDate, List<Task>> = emptyMap(),
    val categories: Map<Long, Category> = emptyMap()
) {
    val tasksForSelected: List<Task>
        get() = tasksByDate[selectedDate].orEmpty().sortedWith(
            compareBy({ it.isCompleted }, { it.dueDate == null }, { it.dueDate })
        )
}

class CalendarViewModel(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val selected = MutableStateFlow(LocalDate.now())

    val state: StateFlow<CalendarUiState> =
        combine(
            taskRepository.observeAll(),
            categoryRepository.observeAll(),
            selected
        ) { tasks, categories, date ->
            val byDate = tasks.filter { it.dueDate != null }
                .groupBy { it.dueDate!!.toLocalDate() }
            CalendarUiState(
                selectedDate = date,
                month = YearMonth.from(date),
                tasksByDate = byDate,
                categories = categories.associateBy { it.id }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CalendarUiState())

    fun selectDate(date: LocalDate) { selected.value = date }
    fun previousMonth() { selected.value = selected.value.minusMonths(1) }
    fun nextMonth() { selected.value = selected.value.plusMonths(1) }
}
