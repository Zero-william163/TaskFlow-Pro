package com.taskflow.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.data.local.CategoryCount
import com.taskflow.app.data.local.DailyCompletion
import com.taskflow.app.data.local.DailyFocusMinutes
import com.taskflow.app.data.local.PriorityCount
import com.taskflow.app.data.model.Category
import com.taskflow.app.data.repository.CategoryRepository
import com.taskflow.app.data.repository.FocusHistoryRepository
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

private const val WINDOW_SIZE = 7

data class StatsUiState(
    val total: Int = 0,
    val completed: Int = 0,
    val pending: Int = 0,
    val completedToday: Int = 0,
    val byPriority: List<PriorityCount> = emptyList(),
    val byCategory: List<CategoryCount> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
    val weekStart: LocalDate = LocalDate.now().minusDays((WINDOW_SIZE - 1).toLong()),
    val trend: List<DailyPoint> = emptyList(),
    // ====== Pomodoro focus stats (from focus_history table) ======
    /** Total focused minutes across all completed sessions. */
    val totalFocusMinutes: Int = 0,
    /** Total number of completed focus sessions. */
    val totalFocusSessions: Int = 0,
    /** Daily focus minutes for the trend chart (oldest first). */
    val focusByDay: List<DailyFocusMinutes> = emptyList()
) {
    val completionRate: Int
        get() = if (total == 0) 0 else (completed * 100 / total)

    val trendTitle: String
        get() {
            val start = weekStart
            val end = weekStart.plusDays((trend.size - 1).coerceAtLeast(0).toLong())
            val startStr = "${start.year}年${start.monthValue}月"
            val endStr = "${end.year}年${end.monthValue}月"
            return if (start.month == end.month && start.year == end.year) {
                "${start.year}年${start.monthValue}月完成趋势"
            } else {
                "${start.year}年${start.monthValue}月-${end.monthValue}月完成趋势"
            }
        }

    /** Human-readable "Xh Ym" for the hero focus card. */
    val totalFocusDisplay: String
        get() {
            val h = totalFocusMinutes / 60
            val m = totalFocusMinutes % 60
            return when {
                h > 0 && m > 0 -> "${h}小时${m}分"
                h > 0 -> "${h}小时"
                else -> "${m}分钟"
            }
        }
}

data class DailyPoint(val date: LocalDate, val count: Int)

class StatsViewModel(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val focusHistoryRepository: FocusHistoryRepository
) : ViewModel() {

    private val _weekStart = MutableStateFlow(LocalDate.now().minusDays((WINDOW_SIZE - 1).toLong()))
    val weekStart: StateFlow<LocalDate> = _weekStart

    private var lastObservedDay = LocalDate.now()

    init {
        // 跨午夜自动推进窗口
        viewModelScope.launch {
            while (true) {
                delay(3600_000) // 每小时检查一次
                val today = LocalDate.now()
                if (today != lastObservedDay) {
                    lastObservedDay = today
                    _weekStart.update { current ->
                        val newStart = today.minusDays((WINDOW_SIZE - 1).toLong())
                        // 只在窗口发生实质性变化时更新
                        if (current != newStart) newStart else current
                    }
                }
            }
        }
    }

    fun selectPreviousWindow() {
        _weekStart.update { it.minusDays(WINDOW_SIZE.toLong()) }
    }

    fun selectNextWindow() {
        _weekStart.update { it.plusDays(WINDOW_SIZE.toLong()) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val trendFlow: StateFlow<List<DailyCompletion>> = _weekStart
        .flatMapLatest { start ->
            val end = start.plusDays(WINDOW_SIZE.toLong() - 1)
            val from = start.atStartOfDay()
            val to = end.atTime(23, 59, 59)
            taskRepository.observeDailyCompletions(from, to)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Combines task counts + category/priority breakdowns + focus-history stats
     * (total minutes, total sessions, daily breakdown). The focus data flows in
     * live from `focus_history` so a Pomodoro completion instantly updates the
     * Stats screen the next time it's composed.
     */
    val state: StateFlow<StatsUiState> = combine(
        taskRepository.observeTotalCount(),
        taskRepository.observeCompletedCount(),
        taskRepository.observePendingCount(),
        taskRepository.observeCompletedOn(LocalDateTime.now()),
        taskRepository.observePriorityCounts(),
        taskRepository.observeCategoryCounts(),
        categoryRepository.observeAll(),
        _weekStart,
        trendFlow,
        focusHistoryRepository.observeTotalFocusMinutes(),
        focusHistoryRepository.observeTotalFocusSessions(),
        focusHistoryRepository.observeDailyFocusMinutes()
    ) { values ->
        val start = values[7] as LocalDate
        val rawTrend = values[8] as List<DailyCompletion>
        StatsUiState(
            total = values[0] as Int,
            completed = values[1] as Int,
            pending = values[2] as Int,
            completedToday = values[3] as Int,
            byPriority = values[4] as List<PriorityCount>,
            byCategory = values[5] as List<CategoryCount>,
            categories = (values[6] as List<Category>).associateBy { it.id },
            weekStart = start,
            trend = fillWindow(start, rawTrend),
            totalFocusMinutes = values[9] as Int,
            totalFocusSessions = values[10] as Int,
            focusByDay = values[11] as List<DailyFocusMinutes>
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    private fun fillWindow(start: LocalDate, rows: List<DailyCompletion>): List<DailyPoint> {
        val byDay = rows.associate { LocalDate.parse(it.day) to it.count }
        return (0 until WINDOW_SIZE).map { offset ->
            val date = start.plusDays(offset.toLong())
            DailyPoint(date = date, count = byDay[date] ?: 0)
        }
    }
}
