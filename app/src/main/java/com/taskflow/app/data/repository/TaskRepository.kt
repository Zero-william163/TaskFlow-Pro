package com.taskflow.app.data.repository

import android.content.Context
import android.content.Intent
import com.taskflow.app.data.local.TaskDao
import com.taskflow.app.data.local.TaskDatabase
import com.taskflow.app.data.model.Task
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime


/**
 * Single source of truth for task data. Every mutation broadcasts [ACTION_TASKS_CHANGED]
 * so the App Widget and any listeners can refresh themselves automatically.
 */
class TaskRepository private constructor(
    private val context: Context,
    private val taskDao: TaskDao
) {

    fun observeAll(): Flow<List<Task>> =
        taskDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observePending(): Flow<List<Task>> =
        taskDao.observePending().map { list -> list.map { it.toDomain() } }

    fun observeCompleted(): Flow<List<Task>> =
        taskDao.observeCompleted().map { list -> list.map { it.toDomain() } }

    fun observeTotalCount(): Flow<Int> = taskDao.observeTotalCount()
    fun observeCompletedCount(): Flow<Int> = taskDao.observeCompletedCount()
    fun observePendingCount(): Flow<Int> = taskDao.observePendingCount()
    fun observeCompletedOn(day: LocalDateTime): Flow<Int> = taskDao.observeCompletedOn(day)
    fun observePriorityCounts(): Flow<List<com.taskflow.app.data.local.PriorityCount>> =
        taskDao.observePriorityCounts()
    fun observeCategoryCounts(): Flow<List<com.taskflow.app.data.local.CategoryCount>> =
        taskDao.observeCategoryCounts()

    fun observeTask(id: Long): Flow<Task?> =
        taskDao.observeById(id).map { it?.toDomain() }

    fun search(query: String): Flow<List<Task>> =
        taskDao.search(query).map { list -> list.map { it.toDomain() } }

    suspend fun getTask(id: Long): Task? = taskDao.getById(id)?.toDomain()

    suspend fun getPending(): List<Task> = taskDao.getPending().map { it.toDomain() }

    suspend fun getUpcomingReminders(after: LocalDateTime): List<Task> =
        taskDao.getUpcomingReminders(after).map { it.toDomain() }

    suspend fun getDueReminders(before: LocalDateTime): List<Task> =
        taskDao.getDueReminders(before).map { it.toDomain() }

    suspend fun addTask(task: Task): Long {
        val id = taskDao.insert(task.toEntity())
        notifyTasksChanged()
        return id
    }

    suspend fun updateTask(task: Task) {
        taskDao.update(task.copy(updatedAt = LocalDateTime.now()).toEntity())
        notifyTasksChanged()
    }

    suspend fun deleteTask(id: Long) {
        taskDao.deleteById(id)
        notifyTasksChanged()
    }

    suspend fun setCompleted(task: Task, completed: Boolean) {
        val now = LocalDateTime.now()
        taskDao.setCompleted(
            id = task.id,
            completed = completed,
            completedAt = if (completed) now else null,
            now = now
        )
        notifyTasksChanged()
    }

    private fun notifyTasksChanged() {
        val intent = Intent(ACTION_TASKS_CHANGED).setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    companion object {
        const val ACTION_TASKS_CHANGED = "com.taskflow.app.TASKS_CHANGED"

        @Volatile
        private var INSTANCE: TaskRepository? = null

        fun get(context: Context): TaskRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TaskRepository(
                    context.applicationContext,
                    TaskDatabase.get(context).taskDao()
                ).also { INSTANCE = it }
            }
    }
}
