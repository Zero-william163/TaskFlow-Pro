package com.taskflow.app.data.model

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/**
 * Repetition pattern applied to a task. Kept small and serialised into the
 * `Task` table as a flat set of columns — for the moderate complexity required
 * here (NONE / DAILY / WEEKLY / MONTHLY / CUSTOM) embedding avoids an extra
 * JOIN in normal task list queries.
 */
enum class FrequencyType { NONE, DAILY, WEEKLY, MONTHLY, CUSTOM }

/**
 * Concrete execution date for a task produced by its frequency rule.
 *
 * Calendars and completion queries key off these rows, keeping the "what task
 * is" (Task table) separate from "when it runs on a given day".
 */
@Immutable
data class TaskInstance(
    val id: Long = 0L,
    val taskId: Long,
    /** The date on which this occurrence shows up on the calendar / home list. */
    val occurrenceDate: LocalDate,
    val isCompleted: Boolean = false,
    val completedAt: java.time.LocalDateTime? = null,
    val createdAt: java.time.LocalDateTime = java.time.LocalDateTime.now()
)
