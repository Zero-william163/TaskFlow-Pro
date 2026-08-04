package com.taskflow.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.taskflow.app.data.model.TaskInstance
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface TaskInstanceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<TaskInstanceEntity>)

    @Query("DELETE FROM task_instances WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)

    @Query("DELETE FROM task_instances WHERE taskId IN (:taskIds)")
    suspend fun deleteByTasks(taskIds: List<Long>)

    @Query("SELECT * FROM task_instances WHERE taskId = :taskId ORDER BY occurrenceDate ASC")
    fun observeForTask(taskId: Long): Flow<List<TaskInstanceEntity>>

    @Query("SELECT * FROM task_instances WHERE taskId = :taskId ORDER BY occurrenceDate ASC")
    suspend fun getForTask(taskId: Long): List<TaskInstanceEntity>

    @Query("SELECT * FROM task_instances WHERE occurrenceDate = :date ORDER BY occurrenceDate ASC")
    fun observeOnDate(date: LocalDate): Flow<List<TaskInstanceEntity>>

    @Query("SELECT * FROM task_instances WHERE occurrenceDate BETWEEN :from AND :to ORDER BY occurrenceDate ASC")
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<TaskInstanceEntity>>

    @Query("SELECT * FROM task_instances WHERE occurrenceDate BETWEEN :from AND :to ORDER BY occurrenceDate ASC")
    suspend fun getBetween(from: LocalDate, to: LocalDate): List<TaskInstanceEntity>

    @Query("UPDATE task_instances SET isCompleted = :completed, completedAt = :completedAt WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean, completedAt: java.time.LocalDateTime?)

    @Query("UPDATE task_instances SET isCompleted = :completed, completedAt = :completedAt WHERE taskId = :taskId AND occurrenceDate = :date")
    suspend fun setCompletedOn(taskId: Long, date: LocalDate, completed: Boolean, completedAt: java.time.LocalDateTime?)
}

internal fun TaskInstanceEntity.toDomain(): TaskInstance = TaskInstance(
    id = id,
    taskId = taskId,
    occurrenceDate = occurrenceDate,
    isCompleted = isCompleted,
    completedAt = completedAt,
    createdAt = createdAt
)
