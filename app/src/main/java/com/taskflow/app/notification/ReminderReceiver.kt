package com.taskflow.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.taskflow.app.data.repository.TaskRepository
import com.taskflow.app.widget.WidgetHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Handles three flows:
 *  - [ACTION_REMINDER]: an alarm fired → post the reminder notification.
 *  - [ACTION_COMPLETE]: user tapped "complete" → mark the task done.
 *  - [ACTION_SNOOZE]: user tapped "snooze" → reschedule +15 minutes.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (action) {
                    ACTION_REMINDER -> showReminder(context, taskId)
                    ACTION_COMPLETE -> completeTask(context, taskId)
                    ACTION_SNOOZE -> snooze(context, taskId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun showReminder(context: Context, taskId: Long) {
        val repo = TaskRepository.get(context)
        val task = withContext(Dispatchers.IO) { repo.getTask(taskId) } ?: return
        if (task.isCompleted) return

        // ====== PRIMARY PATH: Launch full-screen AlarmActivity ======
        // This directly shows the alarm UI with looping sound + vibration,
        // bypassing the notification system entirely as the main trigger.
        try {
            val alarmIntent = AlarmActivity.createIntent(context, taskId)
            context.startActivity(alarmIntent)
        } catch (e: Exception) {
            android.util.Log.e("ReminderReceiver", "Failed to start AlarmActivity", e)
        }

        // ====== FALLBACK: Also post a high-priority notification with FullScreenIntent ======
        // If the activity can't start (e.g. background restrictions), the notification
        // with fullScreenIntent will still wake the screen and show the alarm.
        val helper = NotificationHelper(context)
        val body = buildString {
            task.dueDate?.let { append("截止: ").append(formatTime(it)).append("\n") }
            if (task.description.isNotBlank()) append(task.description)
        }.trim().ifEmpty { "任务提醒" }

        val completeIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_COMPLETE; putExtra(EXTRA_TASK_ID, taskId)
        }
        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_SNOOZE; putExtra(EXTRA_TASK_ID, taskId)
        }
        val completePi = android.app.PendingIntent.getBroadcast(
            context, taskId.toInt(), completeIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val snoozePi = android.app.PendingIntent.getBroadcast(
            context, (taskId + SNOOZE_REQUEST_OFFSET).toInt(), snoozeIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val soundUri = task.alarmSoundUri
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
        val builder = helper.buildReminder(
            taskId = taskId,
            title = task.title,
            body = body,
            completeIntent = completePi,
            snoozeIntent = snoozePi,
            alarmSoundUri = soundUri
        )
        helper.notify(taskId.toInt(), builder)

        // Chain the next alarm for DAILY tasks right after this one fired, so the
        // repetition survives process death or reboot without re-sweeping the DB.
        ReminderScheduler(context).rescheduleDaily(task)
    }

    private suspend fun completeTask(context: Context, taskId: Long) {
        val repo = TaskRepository.get(context)
        val task = withContext(Dispatchers.IO) { repo.getTask(taskId) } ?: return
        withContext(Dispatchers.IO) { repo.setCompleted(task, true) }
        ReminderScheduler(context).cancel(taskId)
        NotificationManagerCompat.from(context).cancel(taskId.toInt())
        WidgetHelper.refresh(context)
        cancelVibrate(context)
    }

    private fun snooze(context: Context, taskId: Long) {
        val scheduler = ReminderScheduler(context)
        val triggerAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER; putExtra(EXTRA_TASK_ID, taskId)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            context, taskId.toInt(), intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        try {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        NotificationManagerCompat.from(context).cancel(taskId.toInt())
        cancelVibrate(context)
    }

    private fun formatTime(time: LocalDateTime): String {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        return time.format(fmt)
    }

    companion object {
        const val ACTION_REMINDER = "com.taskflow.app.action.REMINDER"
        const val ACTION_COMPLETE = "com.taskflow.app.action.COMPLETE"
        const val ACTION_SNOOZE = "com.taskflow.app.action.SNOOZE"
        const val EXTRA_TASK_ID = "extra_task_id"
        private const val SNOOZE_MINUTES = 15L
        private const val SNOOZE_REQUEST_OFFSET = 10_000L
    }
}
