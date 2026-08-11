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
    /**
     * Default = true so newly-created tasks automatically appear on any
     * already-placed home-screen widget. Users can opt out per task via the
     * detail UI; this opt-in default aligns with user expectation that "I
     * added a task → I want to see it on my widget".
     */
    val pinnedToWidget: Boolean = true,
    /**
     * Sound URI for task reminder. `null` / blank = use system default.
     * Value comes from RingtoneManager picker (content:// URI) and is persisted
     * as a plain string so Room can store it with a single TEXT column.
     */
    val alarmSoundUri: String? = null,
    /**
     * Last date this recurring task was "checked off" (format "yyyy-MM-dd").
     * For non-recurring tasks this stays null (they use [isCompleted] instead).
     * For recurring tasks, comparing this to today's date tells us whether the
     * task has already been done today.
     */
    val lastCompletedDate: String? = null,
    /**
     * Pre-computed next due timestamp (epoch millis, local zone) for recurring
     * tasks. Updated every time the user checks off a recurring task so the
     * widget / UI can show "next: Aug 12" without re-running the generator.
     */
    val nextDueDate: Long? = null
) {
    val hasReminder: Boolean get() = reminderTime != null

    /** True if this task repeats (DAILY / WEEKLY / MONTHLY / CUSTOM). */
    val isRecurring: Boolean get() = frequency != FrequencyType.NONE

    /** True if the user already checked off this recurring task today. */
    val isCompletedToday: Boolean
        get() = isRecurring && lastCompletedDate == LocalDate.now().toString()

    /**
     * True if this task is due/active today:
     * - Non-recurring: dueDate falls on today.
     * - Recurring: today is within [startDate, dueDate] (the active range).
     */
    val isDueToday: Boolean
        get() {
            val today = LocalDate.now()
            return if (isRecurring) {
                val start = startDate ?: today
                val end = dueDate?.toLocalDate()
                !today.isBefore(start) && (end == null || !today.isAfter(end))
            } else {
                dueDate?.toLocalDate() == today
            }
        }

    /**
     * Returns true if the due-time is exactly 00:00:00. This means the user set
     * only a DATE (no specific time-of-day) for the deadline. For such tasks we
     * should:
     *  - Not display the "00:00" time label inline (it's misleading — the task
     *    isn't literally due the second the day starts)
     *  - Treat end-of-day (instead of start-of-day) as the overdue cutoff so a
     *    task due "Aug 11" is NOT flagged "overdue" at 09:27 on Aug 11.
     */
    val isDueDateOnly: Boolean
        get() = dueDate != null &&
            dueDate.hour == 0 && dueDate.minute == 0 &&
            dueDate.second == 0 && dueDate.nano == 0

    val isOverdue: Boolean
        get() {
            if (isCompleted || dueDate == null) return false
            return if (isDueDateOnly) {
                // "Date only" task: overdue starts the day AFTER the due date.
                val dueDay = dueDate.toLocalDate()
                dueDay.isBefore(LocalDate.now())
            } else {
                dueDate.isBefore(LocalDateTime.now())
            }
        }

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
