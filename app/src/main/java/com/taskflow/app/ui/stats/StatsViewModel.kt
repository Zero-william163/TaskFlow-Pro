package com.taskflow.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.local.CategoryCount
import com.taskflow.app.data.local.DailyCompletion
import com.taskflow.app.data.local.PriorityCount
import com.taskflow.app.data.model.Category
import com.taskflow.app.data.repository.CategoryRepository
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

data class StatsUiState(
    val total: Int = 0,
    val completed: Int = 0,
    val pending: Int = 0,
    val completedToday: Int = 0,
    val byPriority: List<PriorityCount> = emptyList(),
    val byCategory: List<CategoryCount> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
    // ====== Line chart (spec §2) ======
    val selectedMonth: YearMonth = YearMonth.now(),
    /** Daily counts for the selected month; index 0 = day 1. Length = month length. */
    val monthlyTrend: List<DailyPoint> = emptyList()
) {
    val completionRate: Int
        get() = if (total == 0) 0 else (completed * 100 / total)

    /** Auto-generated chart title: "2026年8月任务完成趋势" (spec §2). */
    val trendTitle: String
        get() = "${selectedMonth.year}年${selectedMonth.monthValue}月任务完成趋势"
}

/** A single point on the line chart. */
data class DailyPoint(val date: LocalDate, val count: Int)

class StatsViewModel(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    // ====== Month selector (spec §2: "根据当前选择月份自动变化") ======
    private val _selectedMonth = MutableStateFlow(YearMonth.now())
    val selectedMonth: StateFlow<YearMonth> = _selectedMonth

    fun selectPreviousMonth() {
        _selectedMonth.update { it.minusMonths(1) }
    }

    fun selectNextMonth() {
        _selectedMonth.update { it.plusMonths(1) }
    }

    fun selectMonth(month: YearMonth) {
        _selectedMonth.value = month
    }

    /**
     * Daily completion trend for the selected month. Emits a new list of
     * DailyPoint (one per day of the month, zero-filled for days with no
     * completions) whenever any task is completed/uncompleted. The chart
     * collects this as StateFlow → real-time updates, no manual refresh
     * needed (spec §2 "实时更新").
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val monthlyTrendFlow: StateFlow<List<DailyPoint>> = _selectedMonth
        .flatMapLatest { month ->
            val start = month.atDay(1).atStartOfDay()
            val end = month.atEndOfMonth().atTime(23, 59, 59)
            taskRepository.observeDailyCompletions(start, end)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val state: StateFlow<StatsUiState> = combine(
        taskRepository.observeTotalCount(),
        taskRepository.observeCompletedCount(),
        taskRepository.observePendingCount(),
        taskRepository.observeCompletedOn(LocalDateTime.now()),
        taskRepository.observePriorityCounts(),
        taskRepository.observeCategoryCounts(),
        categoryRepository.observeAll(),
        _selectedMonth,
        monthlyTrendFlow
    ) { values ->
        val month = values[7] as YearMonth
        val rawTrend = values[8] as List<DailyCompletion>
        StatsUiState(
            total = values[0] as Int,
            completed = values[1] as Int,
            pending = values[2] as Int,
            completedToday = values[3] as Int,
            byPriority = values[4] as List<PriorityCount>,
            byCategory = values[5] as List<CategoryCount>,
            categories = (values[6] as List<Category>).associateBy { it.id },
            selectedMonth = month,
            monthlyTrend = fillMonth(month, rawTrend)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    /**
     * Expand the sparse DB rows into a dense per-day list covering every day
     * of the month. Days with no completion entry get count = 0. This is what
     * the line chart needs — a point for every day, not just days with data.
     */
    private fun fillMonth(month: YearMonth, rows: List<DailyCompletion>): List<DailyPoint> {
        val byDay = rows.associate {
            // row.day is "YYYY-MM-DD" from SQLite date(); parse to LocalDate.
            LocalDate.parse(it.day) to it.count
        }
        val length = month.lengthOfMonth()
        return (1..length).map { day ->
            val date = month.atDay(day)
            DailyPoint(date = date, count = byDay[date] ?: 0)
        }
    }
}
