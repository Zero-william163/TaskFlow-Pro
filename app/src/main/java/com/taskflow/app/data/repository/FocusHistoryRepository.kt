package com.taskflow.app.data.repository

import android.content.Context
import com.taskflow.app.data.local.DailyFocusMinutes
import com.taskflow.app.data.local.FocusHistoryDao
import com.taskflow.app.data.local.FocusHistoryEntity
import com.taskflow.app.data.local.TaskDatabase
import com.taskflow.app.data.local.TaskFocusCount
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the `focus_history` table (completed Pomodoro sessions).
 * Used by [com.taskflow.app.ui.pomodoro.PomodoroViewModel] to record sessions
 * and by [com.taskflow.app.ui.stats.StatsViewModel] to render focus statistics.
 */
class FocusHistoryRepository private constructor(
    private val dao: FocusHistoryDao
) {

    /** Total focused minutes across all sessions (live). */
    fun observeTotalFocusMinutes(): Flow<Int> = dao.observeTotalFocusMinutes()

    /** Total number of completed sessions (live). */
    fun observeTotalFocusSessions(): Flow<Int> = dao.observeTotalFocusSessions()

    /** Daily focus minutes (oldest first) for the Stats trend chart. */
    fun observeDailyFocusMinutes(): Flow<List<DailyFocusMinutes>> =
        dao.observeDailyFocusMinutes()

    /**
     * Per-task completed-session count for [today] ("YYYY-MM-DD"). Used by the
     * task card to render "今日已专注 X 次".
     */
    fun observeTodayFocusCounts(today: String): Flow<List<TaskFocusCount>> =
        dao.observeTodayFocusCounts(today)

    /**
     * Persist a completed session. Called when the Pomodoro ring timer reaches
     * 100%. Runs on the caller's dispatcher (suspend).
     */
    suspend fun recordSession(taskId: Long, durationMinutes: Int) {
        dao.insert(
            FocusHistoryEntity(
                taskId = taskId,
                durationMinutes = durationMinutes
            )
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: FocusHistoryRepository? = null

        fun get(context: Context): FocusHistoryRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FocusHistoryRepository(
                    TaskDatabase.get(context).focusHistoryDao()
                ).also { INSTANCE = it }
            }
    }
}
