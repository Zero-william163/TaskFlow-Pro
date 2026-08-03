package com.taskflow.app.data.repository

import com.taskflow.app.data.local.CategoryEntity
import com.taskflow.app.data.local.TaskEntity
import com.taskflow.app.data.model.Category
import com.taskflow.app.data.model.Priority
import com.taskflow.app.data.model.Task

internal fun TaskEntity.toDomain(): Task = Task(
    id = id,
    title = title,
    description = description,
    categoryId = categoryId,
    priority = priority,
    dueDate = dueDate,
    reminderTime = reminderTime,
    isCompleted = isCompleted,
    completedAt = completedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    title = title,
    description = description,
    categoryId = categoryId,
    priority = priority,
    dueDate = dueDate,
    reminderTime = reminderTime,
    isCompleted = isCompleted,
    completedAt = completedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    color = color,
    sortOrder = sortOrder
)

internal fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    color = color,
    sortOrder = sortOrder
)

internal fun priorityFromString(name: String): Priority = Priority.fromName(name)
