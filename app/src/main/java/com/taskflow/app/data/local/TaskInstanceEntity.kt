package com.taskflow.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One concrete occurrence of a task on a specific date.
 *
 * Calendars and home date filtering read from this table so a task's frequency
 * rule can produce many rows without duplicating task title/category/etc.
 */
@Entity(
    tableName = "task_instances",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["taskId"]),
        Index(value = ["occurrenceDate"]),
        Index(value = ["isCompleted"]),
        Index(value = ["taskId", "occurrenceDate"], unique = true)
    ]
)
data class TaskInstanceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val taskId: Long,
    val occurrenceDate: LocalDate,
    val isCompleted: Boolean = false,
    val completedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
