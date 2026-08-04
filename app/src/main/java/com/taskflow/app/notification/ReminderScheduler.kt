package com.taskflow.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.taskflow.app.data.model.ReminderMode
import com.taskflow.app.data.model.Task
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Schedules task reminders as exact alarms (best-effort, falling back to inexact when
 * the user has not granted exact-alarm permission on Android 12+). Reminders survive
 * reboots via [BootReceiver] which reschedules every pending reminder.
 *
 * For [ReminderMode.DAILY], each firing triggers the next day's alarm inside the
 * receiver, guaranteeing the chain continues indefinitely even after process death.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(task: Task) {
        val reminder = task.reminderTime ?: return
        if (task.isCompleted) { cancel(task.id); return }
        val triggerAt = nextTrigger(task, reminder)
        if (triggerAt <= System.currentTimeMillis()) return

        val pi = pendingIntent(task.id)
        if (canUseExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pi
                )
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(taskId: Long) {
        alarmManager.cancel(pendingIntent(taskId))
    }

    /**
     * Reschedule the next occurrence for a DAILY task after a REMINDER broadcast fires.
     * Returns the triggerAt for callers' logs.
     */
    fun rescheduleDaily(task: Task): Long? {
        if (task.reminderMode != ReminderMode.DAILY) return null
        if (task.isCompleted) return null
        val reminder = task.reminderTime ?: return null
        val tomorrow = LocalDateTime.now().plusDays(1)
            .withHour(reminder.hour)
            .withMinute(reminder.minute)
            .withSecond(0)
            .withNano(0)
        // Respects dueDate ceiling for tasks with an end date.
        val limited = task.dueDate?.let { due -> if (tomorrow.isAfter(due)) return null; tomorrow } ?: tomorrow
        val triggerAt = limited.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = pendingIntent(task.id)
        if (canUseExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        return triggerAt
    }

    /** Compute the next epoch-millis trigger for the reminder, honoring DAILY rollover. */
    private fun nextTrigger(task: Task, reminder: LocalDateTime): Long {
        val base = reminder.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (task.reminderMode == ReminderMode.DAILY && base <= System.currentTimeMillis()) {
            // Fire at reminder time tomorrow (keeps the clock hour/minute the same).
            var cursor = LocalDateTime.now()
                .withHour(reminder.hour)
                .withMinute(reminder.minute)
                .withSecond(0).withNano(0)
            if (!cursor.isAfter(LocalDateTime.now())) cursor = cursor.plusDays(1)
            task.dueDate?.let { if (cursor.isAfter(it)) return base } // fall through to original
            return cursor.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return base
    }

    private fun pendingIntent(taskId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMINDER
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context, taskId.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun canUseExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true
}

/** Converts a [LocalDateTime] (system zone) to epoch millis. */
fun LocalDateTime.toEpochMillis(): Long =
    atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
