package com.taskflow.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.taskflow.app.data.model.FrequencyType
import com.taskflow.app.data.model.Priority
import com.taskflow.app.data.model.ReminderMode
import java.time.LocalDate
import java.time.LocalDateTime

@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["isCompleted"]),
        Index(value = ["dueDate"]),
        Index(value = ["reminderTime"]),
        Index(value = ["pinnedToWidget"]),
        Index(value = ["startDate"]),
        Index(value = ["frequency"])
    ]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val description: String = "",
    val categoryId: Long = 1L,
    val priority: Priority = Priority.NONE,
    val dueDate: LocalDateTime? = null,
    /** Beginning of the date range (inclusive) used by frequency rules. */
    val startDate: LocalDate? = null,
    val reminderTime: LocalDateTime? = null,
    /** @see com.taskflow.app.data.model.ReminderMode */
    val reminderMode: ReminderMode = ReminderMode.ONCE,
    /** @see com.taskflow.app.data.model.FrequencyType */
    val frequency: FrequencyType = FrequencyType.DAILY,
    val weeklyWeekdays: Int = 0,
    val monthlyDays: Int = 0,
    val customDatesRaw: String? = null,
    val isCompleted: Boolean = false,
    val completedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    /**
     * Per-task pin flag for the home-screen widget. Defaults to true so tasks
     * appear automatically on any already-placed widget. Only pinned, incomplete
     * tasks are rendered by the collection widget.
     */
    val pinnedToWidget: Boolean = true,
    /** Reminder sound URI (RingtoneManager content://). Null = system default. */
    val alarmSoundUri: String? = null,
    /** "yyyy-MM-dd" of the last time a recurring task was checked off. */
    val lastCompletedDate: String? = null,
    /** Pre-computed next due timestamp (epoch millis) for recurring tasks. */
    val nextDueDate: Long? = null,
    /**
     * Whether the user explicitly set a due date for this task. When false the
     * task has no deadline (executed indefinitely from the start date). The
     * [dueDate] column may still hold a value for frequency-range tasks even
     * when this is false; this flag is the source of truth for the form's
     * "截止日期" toggle and for overdue/status UI.
     */
    val hasDueDate: Boolean = false,
    /**
     * Pomodoro focus duration in minutes for this task. Drives the initial
     * ring-timer value on the Pomodoro focus screen. 0 = use app default (25).
     */
    val focusDurationMinutes: Int = 0
)
