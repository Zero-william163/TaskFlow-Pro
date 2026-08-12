package com.taskflow.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusHistoryDao {

    @Insert
    suspend fun insert(entity: FocusHistoryEntity): Long

    /**
     * Total focus minutes across all sessions. Observed so the Stats screen
     * updates live when a Pomodoro session completes.
     */
    @Query("SELECT COALESCE(SUM(durationMinutes), 0) FROM focus_history")
    fun observeTotalFocusMinutes(): Flow<Int>

    /**
     * Total number of completed focus sessions.
     */
    @Query("SELECT COUNT(*) FROM focus_history")
    fun observeTotalFocusSessions(): Flow<Int>

    /**
     * Daily focus minutes for the last [days] days (oldest first), used by the
     * Stats trend chart. `day` is the SQLite `date()` text ("YYYY-MM-DD")
     * produced from the epoch-millis timestamp column via
     * `date(timestamp/1000, 'unixepoch')` — same convention as
     * [TaskDao.observeDailyCompletions].
     */
    @Query(
        """
        SELECT date(timestamp/1000, 'unixepoch') AS day,
               COALESCE(SUM(durationMinutes), 0) AS minutes
        FROM focus_history
        WHERE timestamp IS NOT NULL
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun observeDailyFocusMinutes(): Flow<List<DailyFocusMinutes>>

    @Query("DELETE FROM focus_history WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)

    /**
     * Per-task completed-session count for [today] ("YYYY-MM-DD"). Used by the
     * task card to render "今日已专注 X 次". Grouped by taskId so the UI can fold
     * the result into a Map<Long, Int>.
     */
    @Query(
        """
        SELECT taskId AS taskId, COUNT(*) AS cnt
        FROM focus_history
        WHERE date(timestamp/1000, 'unixepoch') = :today
        GROUP BY taskId
        """
    )
    fun observeTodayFocusCounts(today: String): Flow<List<TaskFocusCount>>
}

/** One task's completed-session count for a given day. */
data class TaskFocusCount(val taskId: Long, val cnt: Int)

/** One day's total focused minutes. `day` is "YYYY-MM-DD". */
data class DailyFocusMinutes(val day: String, val minutes: Int)
