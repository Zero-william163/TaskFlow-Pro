package com.taskflow.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * One completed Pomodoro focus session. Written when the ring timer reaches
 * 100% (0 remaining). Drives the "专注时长" statistics on the Stats screen.
 *
 * - [taskId] links back to the task the session was started for (CASCADE on
 *   task delete so stats stay consistent with the task table).
 * - [durationMinutes] is the actual focused minutes (may differ from the
 *   task's configured [TaskEntity.focusDurationMinutes] if the user reset or
 *   the session was shorter).
 * - [timestamp] is when the session completed (epoch millis, local zone —
 *   matches the Converters convention for LocalDateTime).
 */
@Entity(
    tableName = "focus_history",
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
        Index(value = ["timestamp"])
    ]
)
data class FocusHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val taskId: Long,
    val durationMinutes: Int,
    val timestamp: LocalDateTime = LocalDateTime.now()
)
