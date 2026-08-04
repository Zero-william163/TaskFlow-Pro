package com.taskflow.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

@Dao
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY isCompleted ASC, priority DESC, dueDate IS NULL, dueDate ASC, createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY priority DESC, dueDate IS NULL, dueDate ASC")
    fun observePending(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE isCompleted = 1 ORDER BY completedAt DESC")
    fun observeCompleted(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 ORDER BY priority DESC, dueDate IS NULL, dueDate ASC, createdAt DESC")
    suspend fun getPending(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 AND pinnedToWidget = 1 ORDER BY priority DESC, dueDate IS NULL, dueDate ASC, createdAt DESC")
    fun observePinnedPending(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 AND pinnedToWidget = 1 ORDER BY priority DESC, dueDate IS NULL, dueDate ASC, createdAt DESC")
    suspend fun getPinnedPending(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%' ORDER BY isCompleted ASC, priority DESC, dueDate IS NULL, dueDate ASC")
    fun search(query: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 AND reminderTime IS NOT NULL AND reminderTime > :after ORDER BY reminderTime ASC")
    suspend fun getUpcomingReminders(after: LocalDateTime): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 AND reminderTime IS NOT NULL AND reminderTime <= :before")
    suspend fun getDueReminders(before: LocalDateTime): List<TaskEntity>

    @Query("UPDATE tasks SET isCompleted = :completed, completedAt = :completedAt, updatedAt = :now WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean, completedAt: LocalDateTime?, now: LocalDateTime)

    @Query("UPDATE tasks SET pinnedToWidget = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinnedToWidget(id: Long, pinned: Boolean, now: LocalDateTime)

    @Query("SELECT COUNT(*) FROM tasks")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tasks WHERE isCompleted = 1")
    fun observeCompletedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tasks WHERE isCompleted = 0")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tasks WHERE isCompleted = 1 AND date(completedAt) = date(:day)")
    fun observeCompletedOn(day: LocalDateTime): Flow<Int>

    @Query("SELECT priority, COUNT(*) as count FROM tasks GROUP BY priority")
    fun observePriorityCounts(): Flow<List<PriorityCount>>

    @Query("SELECT categoryId, COUNT(*) as count FROM tasks GROUP BY categoryId")
    fun observeCategoryCounts(): Flow<List<CategoryCount>>
}

data class PriorityCount(val priority: String, val count: Int)
data class CategoryCount(val categoryId: Long, val count: Int)
