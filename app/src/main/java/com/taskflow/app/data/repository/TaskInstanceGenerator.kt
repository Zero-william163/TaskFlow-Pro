package com.taskflow.app.data.repository

import com.taskflow.app.data.model.FrequencyType
import com.taskflow.app.data.model.Task
import java.time.LocalDate

/**
 * Pure (no I/O) helper that expands a task's frequency rule into the concrete
 * execution dates that the UI / calendar / alarm system needs.
 *
 * Invariants:
 *  - No instance is produced before today.
 *  - No instance is produced after [Task.dueDate].
 *  - If the task has no due date the range clamps to today + 365 days so the
 *    calendar can still show upcoming occurrences without exploding.
 */
object TaskInstanceGenerator {

    private const val OPEN_ENDED_HORIZON_DAYS = 365

    fun generate(task: Task): List<LocalDate> {
        val range = effectiveRange(task)
        if (range == null) return emptyList()
        val (from, to) = range

        return when (task.frequency) {
            FrequencyType.NONE -> {
                // Single-shot: one instance on the due date, or today if no date.
                val only = task.dueDate?.toLocalDate() ?: from
                listOfNotNull(only.takeIf { !it.isBefore(from) && !it.isAfter(to) })
            }
            FrequencyType.DAILY -> generateDaily(from, to)
            FrequencyType.WEEKLY -> generateWeekly(from, to, task.weeklyWeekdays, task.dueDate?.toLocalDate()?.dayOfWeek?.value)
            FrequencyType.MONTHLY -> generateMonthly(from, to, task.monthlyDays, task.dueDate?.toLocalDate()?.dayOfMonth)
            FrequencyType.CUSTOM -> task.customDates.filter { !it.isBefore(from) && !it.isAfter(to) }.distinct().sorted()
        }
    }

    /**
     * Returns the (inclusive) date range instances may land inside. `null` when
     * the task has no temporal anchor at all.
     */
    fun effectiveRange(task: Task): Pair<LocalDate, LocalDate>? {
        val today = LocalDate.now()
        val explicitStart = task.startDate
        val dueLocal = task.dueDate?.toLocalDate()
        val start = when {
            explicitStart != null && explicitStart.isAfter(today) -> explicitStart
            dueLocal != null && dueLocal.isAfter(today) -> {
                if (explicitStart != null && !explicitStart.isAfter(dueLocal)) explicitStart.coerceAtLeast(today)
                else today
            }
            else -> today
        }
        val end = when {
            dueLocal != null -> dueLocal
            else -> start.plusDays(OPEN_ENDED_HORIZON_DAYS.toLong())
        }
        if (start.isAfter(end)) return null
        return start to end
    }

    private fun generateDaily(from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            out += cursor
            cursor = cursor.plusDays(1)
        }
        return out
    }

    private fun generateWeekly(
        from: LocalDate,
        to: LocalDate,
        mask: Int,
        fallbackWeekday: Int?
    ): List<LocalDate> {
        val weekdays: Set<Int> = run {
            val explicit = (0 until 7).filter { (mask and (1 shl it)) != 0 }.map { it + 1 }.toSet()
            if (explicit.isNotEmpty()) return@run explicit
            setOf(fallbackWeekday ?: from.dayOfWeek.value)
        }
        val out = mutableListOf<LocalDate>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            if (cursor.dayOfWeek.value in weekdays) out += cursor
            cursor = cursor.plusDays(1)
        }
        return out
    }

    private fun generateMonthly(
        from: LocalDate,
        to: LocalDate,
        mask: Int,
        fallbackDay: Int?
    ): List<LocalDate> {
        val days: Set<Int> = run {
            val explicit = (1..31).filter { (mask and (1 shl (it - 1))) != 0 }.toSet()
            if (explicit.isNotEmpty()) return@run explicit
            setOf(fallbackDay ?: from.dayOfMonth)
        }
        val out = mutableListOf<LocalDate>()
        var cursorMonth = with(from) { java.time.YearMonth.of(year, month) }
        val toMonth = with(to) { java.time.YearMonth.of(year, month) }
        while (!cursorMonth.isAfter(toMonth)) {
            days.forEach { d ->
                if (d in 1..cursorMonth.lengthOfMonth()) {
                    val date = cursorMonth.atDay(d)
                    if (!date.isBefore(from) && !date.isAfter(to)) out += date
                }
            }
            cursorMonth = cursorMonth.plusMonths(1)
        }
        return out
    }
}
