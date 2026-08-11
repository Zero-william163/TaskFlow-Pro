package com.taskflow.app.data.repository

import android.content.Context
import android.content.Intent
import com.taskflow.app.data.local.TaskDao
import com.taskflow.app.data.local.TaskDatabase
import com.taskflow.app.data.local.TaskInstanceDao
import com.taskflow.app.data.local.TaskInstanceEntity
import com.taskflow.app.data.local.toDomain
import com.taskflow.app.data.model.Task
import com.taskflow.app.data.model.TaskInstance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime


/**
 * Single source of truth for task data. Every mutation broadcasts [ACTION_TASKS_CHANGED]
 * so the App Widget and any listeners can refresh themselves automatically.
 *
 * Frequency-based tasks are expanded to concrete [TaskInstanceEntity] rows on save,
 * which gives calendars and date-list screens a straightforward query target while
 * keeping [Task] small and stable.
 */
class TaskRepository private constructor(
    private val context: Context,
    private val taskDao: TaskDao,
    private val taskInstanceDao: TaskInstanceDao
) {

    fun observeAll(): Flow<List<Task>> =
        taskDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observePending(): Flow<List<Task>> =
        taskDao.observePending().map { list -> list.map { it.toDomain() } }

    fun observePinnedPending(): Flow<List<Task>> =
        taskDao.observePinnedPending().map { list -> list.map { it.toDomain() } }

    fun observeCompleted(): Flow<List<Task>> =
        taskDao.observeCompleted().map { list -> list.map { it.toDomain() } }

    fun observeTotalCount(): Flow<Int> = taskDao.observeTotalCount()
    fun observeCompletedCount(): Flow<Int> = taskDao.observeCompletedCount()
    fun observePendingCount(): Flow<Int> = taskDao.observePendingCount()
    fun observeCompletedOn(day: LocalDateTime): Flow<Int> = taskDao.observeCompletedOn(day)

    /**
     * Daily completion counts in [startInclusive, endInclusive]. Emits a new
     * list whenever any task is completed/uncompleted in the range — the line
     * chart collects this Flow and updates in real time.
     */
    fun observeDailyCompletions(
        startInclusive: LocalDateTime,
        endInclusive: LocalDateTime
    ): Flow<List<com.taskflow.app.data.local.DailyCompletion>> =
        taskDao.observeDailyCompletions(startInclusive, endInclusive)
    fun observePriorityCounts(): Flow<List<com.taskflow.app.data.local.PriorityCount>> =
        taskDao.observePriorityCounts()
    fun observeCategoryCounts(): Flow<List<com.taskflow.app.data.local.CategoryCount>> =
        taskDao.observeCategoryCounts()

    fun observeTask(id: Long): Flow<Task?> =
        taskDao.observeById(id).map { it?.toDomain() }

    fun search(query: String): Flow<List<Task>> =
        taskDao.search(query).map { list -> list.map { it.toDomain() } }

    /**
     * Observes, for each date in the given range, the list of task ids that have an
     * instance on that date. Used by the calendar month grid to draw dots.
     */
    fun observeInstanceDatesBetween(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, List<Long>>> =
        taskInstanceDao.observeBetween(from, to)
            .map { rows -> rows.groupBy({ it.occurrenceDate }, { it.taskId }) }

    /** Returns the TaskInstances on a given date. */
    fun observeInstancesOnDate(date: LocalDate): Flow<List<TaskInstance>> =
        taskInstanceDao.observeOnDate(date).map { it.map { inst -> inst.toDomain() } }

    suspend fun getTask(id: Long): Task? = taskDao.getById(id)?.toDomain()

    suspend fun getPending(): List<Task> = taskDao.getPending().map { it.toDomain() }

    /**
     * Tasks the widget should render. Two-tier fallback for robustness:
     *  1. [Primary] Pinned + incomplete tasks. This honors the user's per-task
     *     opt-out flag and lets them hide noisy recurring tasks.
     *  2. [Fallback] If NO tasks in the DB have pinnedToWidget=1 (happens on
     *     legacy data / fresh installs created before pinned defaults), show
     *     ALL incomplete tasks instead of a blank list. This guarantees the
     *     widget is never empty when real work exists.
     */
    suspend fun getPinnedPending(): List<Task> {
        val pinned = taskDao.getPinnedPending().map { it.toDomain() }
        if (pinned.isNotEmpty()) {
            android.util.Log.d("TaskRepository", "Widget query [tier=PINNED] count=${pinned.size}, " +
                "ids=${pinned.take(5).map { "${it.id}:${it.title.take(10)}" }}")
            return pinned
        }
        // Fallback: no task has ever been pinned → show every pending task so the
        // widget isn't a useless blank rectangle. This is a silent one-tier
        // promotion; we don't mutate pinnedToWidget in the DB.
        val allPending = taskDao.getPending().map { it.toDomain() }
        android.util.Log.d("TaskRepository", "Widget query [tier=FALLBACK] count=${allPending.size} " +
            "(no pinned tasks found → promoting all pending tasks for display)")
        return allPending
    }

    suspend fun getUpcomingReminders(after: LocalDateTime): List<Task> =
        taskDao.getUpcomingReminders(after).map { it.toDomain() }

    suspend fun getDueReminders(before: LocalDateTime): List<Task> =
        taskDao.getDueReminders(before).map { it.toDomain() }

    suspend fun addTask(task: Task): Long {
        val id = taskDao.insert(task.copy(updatedAt = LocalDateTime.now()).toEntity())
        regenerateInstances(id, task.copy(id = id))
        android.util.Log.d("TaskRepository", "Task saved id=$id, title=${task.title.take(20)}, " +
            "isCompleted=${task.isCompleted}, pinnedToWidget=${task.pinnedToWidget}")
        notifyTasksChanged()
        return id
    }

    suspend fun updateTask(task: Task) {
        val refreshed = task.copy(updatedAt = LocalDateTime.now())
        taskDao.update(refreshed.toEntity())
        regenerateInstances(task.id, refreshed)
        android.util.Log.d("TaskRepository", "Task updated id=${task.id}, title=${task.title.take(20)}, " +
            "isCompleted=${task.isCompleted}, pinnedToWidget=${task.pinnedToWidget}")
        notifyTasksChanged()
    }

    suspend fun deleteTask(id: Long) {
        taskDao.deleteById(id)
        taskInstanceDao.deleteByTask(id)
        android.util.Log.d("TaskRepository", "Task deleted id=$id")
        notifyTasksChanged()
    }

    /**
     * Permanently deletes every permanently-completed (archived) task and their
     * generated [TaskInstanceEntity] rows. Recurring tasks (which never have
     * isCompleted=1) are preserved. Returns the number of task rows deleted.
     */
    suspend fun deleteAllCompletedTasks(): Int {
        // One-shot read of completed task ids so we can clean up their instance rows too.
        val completedIds = taskDao.getCompleted().map { it.id }
        if (completedIds.isEmpty()) return 0
        val deleted = taskDao.deleteAllCompleted()
        taskInstanceDao.deleteByTasks(completedIds)
        android.util.Log.d("TaskRepository", "deleteAllCompletedTasks: $deleted rows, ids=$completedIds")
        notifyTasksChanged()
        return deleted
    }

    suspend fun setCompleted(task: Task, completed: Boolean) {
        val now = LocalDateTime.now()
        if (completed && task.isRecurring) {
            // ===== Recurring task check-off: the task stays alive =====
            // Don't set isCompleted=true. Instead record today as the last
            // completion date and advance nextDueDate to the next occurrence.
            val todayStr = java.time.LocalDate.now().toString()
            val nextDue = computeNextDueDate(task)
            taskDao.setRecurringCheckoff(
                id = task.id,
                lastCompletedDate = todayStr,
                nextDueDate = nextDue,
                now = now
            )
            android.util.Log.d("TaskRepository",
                "Recurring check-off id=${task.id}, freq=${task.frequency}, " +
                "lastCompletedDate=$todayStr, nextDueDate=$nextDue")
        } else if (!completed && task.isRecurring && task.isCompletedToday) {
            // Un-check a recurring task that was checked off today: clear the
            // lastCompletedDate so it re-appears in today's list.
            taskDao.setRecurringCheckoff(
                id = task.id,
                lastCompletedDate = null,
                nextDueDate = null,
                now = now
            )
            android.util.Log.d("TaskRepository",
                "Recurring un-check id=${task.id}, cleared lastCompletedDate")
        } else {
            // ===== Non-recurring: existing behavior =====
            taskDao.setCompleted(
                id = task.id,
                completed = completed,
                completedAt = if (completed) now else null,
                now = now
            )
            android.util.Log.d("TaskRepository",
                "Task setCompleted id=${task.id}, isCompleted=$completed")
        }
        notifyTasksChanged()
    }

    /**
     * Computes the next due timestamp (epoch millis, local zone) for a recurring
     * task after today's check-off. Returns null if the recurrence range has
     * ended (dueDate is in the past).
     */
    private fun computeNextDueDate(task: Task): Long? {
        val today = java.time.LocalDate.now()
        val dueEnd = task.dueDate?.toLocalDate()
        // If the overall due-date has passed, there's no "next" occurrence.
        if (dueEnd != null && dueEnd.isBefore(today)) return null

        val next: java.time.LocalDate = when (task.frequency) {
            com.taskflow.app.data.model.FrequencyType.DAILY -> today.plusDays(1)
            com.taskflow.app.data.model.FrequencyType.WEEKLY -> {
                // Find the next configured weekday, or default to same day next week.
                val weekdays = (0 until 7)
                    .filter { (task.weeklyWeekdays and (1 shl it)) != 0 }
                    .map { it + 1 }
                    .toSet()
                if (weekdays.isEmpty()) today.plusWeeks(1)
                else {
                    var cursor = today.plusDays(1)
                    while (cursor.dayOfWeek.value !in weekdays) cursor = cursor.plusDays(1)
                    cursor
                }
            }
            com.taskflow.app.data.model.FrequencyType.MONTHLY -> {
                val days = (1..31)
                    .filter { (task.monthlyDays and (1 shl (it - 1))) != 0 }
                    .toSet()
                val targetDay = if (days.isEmpty()) today.dayOfMonth else days.minOrNull()!!
                val ym = java.time.YearMonth.from(today).plusMonths(1)
                if (targetDay in 1..ym.lengthOfMonth()) ym.atDay(targetDay) else ym.atEndOfMonth()
            }
            else -> today.plusDays(1) // CUSTOM / fallback
        }
        // Don't advance past the overall due end.
        val clamped = if (dueEnd != null && next.isAfter(dueEnd)) dueEnd else next
        return clamped.atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    }

    /**
     * Pin/unpin a task for the home-screen widget. Broadcasts so the widget's
     * RemoteViewsFactory reloads and reflects the change immediately.
     */
    suspend fun setPinnedToWidget(id: Long, pinned: Boolean) {
        taskDao.setPinnedToWidget(id, pinned, LocalDateTime.now())
        android.util.Log.d("TaskRepository", "Task setPinnedToWidget id=$id, pinned=$pinned")
        notifyTasksChanged()
    }

    /** Rebuild the TaskInstance rows for [task] after the task row changed. */
    private suspend fun regenerateInstances(id: Long, task: Task) {
        taskInstanceDao.deleteByTask(id)
        val dates = TaskInstanceGenerator.generate(task)
        if (dates.isEmpty()) return
        val now = LocalDateTime.now()
        taskInstanceDao.insertAll(
            dates.map { date ->
                TaskInstanceEntity(
                    taskId = id,
                    occurrenceDate = date,
                    createdAt = now
                )
            }
        )
    }

    private fun notifyTasksChanged() {
        val intent = Intent(ACTION_TASKS_CHANGED).setPackage(context.packageName)
        context.sendBroadcast(intent)
        android.util.Log.d("TaskRepository", "sendBroadcast ACTION_TASKS_CHANGED")
    }

    companion object {
        const val ACTION_TASKS_CHANGED = "com.taskflow.app.TASKS_CHANGED"

        @Volatile
        private var INSTANCE: TaskRepository? = null

        fun get(context: Context): TaskRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TaskRepository(
                    context.applicationContext,
                    TaskDatabase.get(context).taskDao(),
                    TaskDatabase.get(context).taskInstanceDao()
                ).also { INSTANCE = it }
            }
    }
}

/** Join used by calendar/detail screens that need both the task metadata and an instance row. */
data class TaskWithInstance(val task: Task, val instance: TaskInstance)
