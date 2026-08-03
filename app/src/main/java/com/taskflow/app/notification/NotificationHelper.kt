package com.taskflow.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.taskflow.app.MainActivity
import com.taskflow.app.R

/** Creates channels and builds task-reminder and update notifications. */
class NotificationHelper(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { ensureChannels() }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_REMINDER,
                    context.getString(R.string.notif_channel_reminder),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notif_channel_reminder_desc)
                    enableVibration(true)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_UPDATE,
                    context.getString(R.string.notif_channel_update),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = context.getString(R.string.notif_channel_update_desc) }
            )
        }
    }

    fun buildReminder(
        taskId: Long,
        title: String,
        body: String,
        completeIntent: PendingIntent,
        snoozeIntent: PendingIntent
    ): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openTaskIntent(taskId))
            .addAction(0, context.getString(R.string.notif_action_complete), completeIntent)
            .addAction(0, context.getString(R.string.notif_action_snooze), snoozeIntent)

    private fun openTaskIntent(taskId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_TASK
            putExtra(MainActivity.EXTRA_TASK_ID, taskId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context, taskId.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun notify(id: Int, builder: NotificationCompat.Builder) {
        manager.notify(id, builder.build())
    }

    fun cancel(id: Int) = manager.cancel(id)

    companion object {
        const val CHANNEL_REMINDER = "reminders"
        const val CHANNEL_UPDATE = "updates"
    }
}
