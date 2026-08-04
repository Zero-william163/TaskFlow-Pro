package com.taskflow.app.data.model

/**
 * How a task reminder fires. Stored on [Task] so reminders can be one-shot
 * (classic behaviour) or repeating each day until the task is marked complete.
 */
enum class ReminderMode {
    /** Fire once at the configured [Task.reminderTime]; no reschedule. */
    ONCE,
    /** Every day at [Task.reminderTime] time; rescheduled after each firing. */
    DAILY;

    companion object {
        fun fromName(name: String?): ReminderMode =
            entries.firstOrNull { it.name == name } ?: ONCE
    }
}
