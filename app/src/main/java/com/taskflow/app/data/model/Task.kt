package com.taskflow.app.data.model

import java.time.LocalDate
import java.time.LocalDateTime

/** Domain representation of a task. */
data class Task(
    val id: Long = 0L,
    val title: String,
    val description: String = "",
    val categoryId: Long = 1L,
    val priority: Priority = Priority.NONE,
    /**
     * The *deadline* date/time. For frequency-based tasks: the end of the range
     * within which instances are generated (instances do not exceed dueDate).
     */
    val dueDate: LocalDateTime? = null,
    /** First date the task starts being active (inclusive). Inferred = dueDate. */
    val startDate: LocalDate? = null,
    val reminderTime: LocalDateTime? = null,
    val reminderMode: ReminderMode = ReminderMode.ONCE,
    val frequency: FrequencyType = FrequencyType.DAILY,
    /**
     * Weekday mask when [frequency] is WEEKLY. Mon=1..Sun=7 (ISO 8601), bits
     * (weekday-1). All-zero means "every day" fallback to MON-FRI.
     */
    val weeklyWeekdays: Int = 0,
    /**
     * Monthday mask when [frequency] is MONTHLY. IntSet stored as bitmask
     * supporting days 1..31. 0 = fall back to the start day.
     */
    val monthlyDays: Int = 0,
    /**
     * User-picked dates when [frequency] is CUSTOM. Stored encoded as a
     * comma-separated `yyyy-MM-dd` list; Room + DataStore both persist strings.
     */
    val customDatesRaw: String? = null,
    val isCompleted: Boolean = false,
    val completedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val pinnedToWidget: Boolean = false,
    /**
     * Sound URI for task reminder. `null` / blank = use system default.
     * Value comes from RingtoneManager picker (content:// URI) and is persisted
     * as a plain string so Room can store it with a single TEXT column.
     */
    val alarmSoundUri: String? = null
) {
    val hasReminder: Boolean get() = reminderTime != null
    val isOverdue: Boolean
        get() = !isCompleted && dueDate != null && dueDate.isBefore(LocalDateTime.now())

    val customDates: List<LocalDate>
        get() = customDatesRaw
            ?.takeIf { it.isNotBlank() }
            ?.split(',')
            ?.mapNotNull { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
            .orEmpty()

    /**
     * Effective start date for instance generation and date-picker lower bound.
     * Uses [startDate] when set, otherwise defaults to today.
     * Never returns a date before today (past dates are not actionable).
     *
     * NOTE: dueDate is intentionally NOT part of this calculation — the start
     * and end of the instance range are independent boundaries.
     */
    val effectiveStart: LocalDate
        get() = (startDate ?: LocalDate.now()).coerceAtLeast(LocalDate.now())
}
