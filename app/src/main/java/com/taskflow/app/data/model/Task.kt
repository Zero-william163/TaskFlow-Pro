package com.taskflow.app.data.model

import java.time.LocalDateTime

/** Domain representation of a task. */
data class Task(
    val id: Long = 0L,
    val title: String,
    val description: String = "",
    val categoryId: Long = 1L,
    val priority: Priority = Priority.NONE,
    val dueDate: LocalDateTime? = null,
    val reminderTime: LocalDateTime? = null,
    val isCompleted: Boolean = false,
    val completedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
) {
    val hasReminder: Boolean get() = reminderTime != null
    val isOverdue: Boolean
        get() = !isCompleted && dueDate != null && dueDate.isBefore(LocalDateTime.now())
}
