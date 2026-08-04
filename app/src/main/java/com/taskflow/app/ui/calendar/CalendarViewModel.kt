package com.taskflow.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.model.Category
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.model.TaskInstance
import com.taskflow.app.data.repository.CategoryRepository
import com.taskflow.app.data.repository.TaskRepository
import com.taskflow.app.notification.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class CalendarUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val month: YearMonth = YearMonth.now(),
    val tasksByDate: Map<LocalDate, List<Task>> = emptyMap(),
    val tasksForSelected: List<Pair<Task, TaskInstance>> = emptyList(),
    val categories: Map<Long, Category> = emptyMap()
)

class CalendarViewModel(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {

    private val selected = MutableStateFlow(LocalDate.now())

    /** For the currently displayed month, always observe from start → end of month. */
    private val displayedMonthRange = selected
        .map { YearMonth.from(it) }
        .map { ym -> ym.atDay(1) to ym.atEndOfMonth() }

    private val monthTaskIdsByDate = displayedMonthRange.flatMapLatest { (from, to) ->
        taskRepository.observeInstanceDatesBetween(from, to)
    }

    private val tasksByDateForMonth = combine(
        monthTaskIdsByDate,
        taskRepository.observeAll()
    ) { idMap, tasks ->
        val byId = tasks.associateBy { it.id }
        idMap.mapValues { (_, ids) -> ids.mapNotNull { byId[it] } }
    }

    /** Tasks for the selected date: combine instances on the date with task metadata. */
    private val selectedDateInstances = selected
        .flatMapLatest { date -> taskRepository.observeInstancesOnDate(date) }

    val state: StateFlow<CalendarUiState> =
        combine(
            tasksByDateForMonth,
            categoryRepository.observeAll(),
            selected,
            selectedDateInstances,
            taskRepository.observeAll()
        ) { byDate, categories, date, instances, tasks ->
            val byId = tasks.associateBy { it.id }
            val joined = instances.mapNotNull { inst ->
                byId[inst.taskId]?.let { task -> task to inst }
            }
            CalendarUiState(
                selectedDate = date,
                month = YearMonth.from(date),
                tasksByDate = byDate,
                tasksForSelected = joined,
                categories = categories.associateBy { it.id }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CalendarUiState())

    fun selectDate(date: LocalDate) { selected.value = date }
    fun previousMonth() { selected.value = selected.value.minusMonths(1) }
    fun nextMonth() { selected.value = selected.value.plusMonths(1) }

    fun toggleComplete(task: Task) {
        viewModelScope.launch {
            val completed = !task.isCompleted
            taskRepository.setCompleted(task, completed)
            if (completed) reminderScheduler.cancel(task.id)
            else task.reminderTime?.let { reminderScheduler.schedule(task) }
        }
    }
}

private fun YearMonth.atEndOfMonth(): LocalDate = atDay(lengthOfMonth())
