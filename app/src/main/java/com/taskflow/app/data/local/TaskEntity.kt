package com.taskflow.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.taskflow.app.data.model.Priority
import java.time.LocalDateTime

@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["isCompleted"]),
        Index(value = ["dueDate"]),
        Index(value = ["reminderTime"])
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
    val reminderTime: LocalDateTime? = null,
    val isCompleted: Boolean = false,
    val completedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
