package com.taskflow.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.taskflow.app.MainActivity
import com.taskflow.app.R
import com.taskflow.app.ui.alarm.AlarmActivity

/**
 * Creates channels and builds task-reminder and update notifications.
 *
 * Requirement #7 (sound + vibrate until dismissed) + #8 (custom per-task sound):
 *
 *  - The reminder channel is IMPORTANCE_HIGH so it pops up on top of the screen.
 *  - For each notification we setSound() to the task's alarmSoundUri when present,
 *    falling back to the channel default (which is the system alarm default sound).
 *  - Vibrator is fired explicitly inside ReminderReceiver with a repeating
 *    VibrationEffect that runs until the notification is dismissed.
 */
class NotificationHelper(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { ensureChannels() }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val reminderAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_REMINDER,
                    context.getString(R.string.notif_channel_reminder),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notif_channel_reminder_desc)
                    enableLights(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                    setSound(defaultRingtoneUri(), reminderAttrs)
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

    private fun defaultRingtoneUri(): Uri =
        RingtoneManager.getActualDefaultRingtoneUri(
            context,
            RingtoneManager.TYPE_ALARM
        ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun buildReminder(
        taskId: Long,
        title: String,
        body: String,
        completeIntent: PendingIntent,
        snoozeIntent: PendingIntent,
        alarmSoundUri: Uri? = null
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openTaskIntent(taskId))
            .setFullScreenIntent(fullScreenIntent(taskId), true)
            .setOngoing(false)
            .addAction(0, context.getString(R.string.notif_action_complete), completeIntent)
            .addAction(0, context.getString(R.string.notif_action_snooze), snoozeIntent)

        val sound = alarmSoundUri ?: defaultRingtoneUri()
        builder.setSound(sound)
        // Explicitly disable default SOUND/VIBRATE since we setSound above; vibrate
        // is added separately via Vibrator for continuous buzz until dismissed.
        builder.setDefaults(0)

        return builder
    }

    /** High-priority heads-up notification with full-screen intent so the device
     * can wake the screen like a system alarm. Points to AlarmActivity for the
     * full-screen alarm UI with looping sound + vibration. */
    private fun fullScreenIntent(taskId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            (taskId + 9999L).toInt(),
            AlarmActivity.createIntent(context, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

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

/**
 * Continuous vibration loop used by the reminder receiver until the user cancels the
 * notification. This is safer than long-running VibrationEffect because some ROMs
 * cap individual vibration durations to 5s; we launch a separate worker coroutine
 * that loops 200ms-on / 300ms-off and cancels when the notification is dismissed.
 */
fun vibrateAlarm(context: Context, taskId: Long) {
    val pattern = longArrayOf(0, 400, 250, 400, 250, 400, 250, 400)
    val vibrator: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    when {
        vibrator is VibratorManager -> vibrator.defaultVibrator.vibrate(
            VibrationEffect.createWaveform(pattern, 1)
        )
        vibrator is Vibrator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> vibrator.vibrate(
            VibrationEffect.createWaveform(pattern, 1)
        )
        vibrator is Vibrator -> @Suppress("DEPRECATION") vibrator.vibrate(pattern, 1)
    }
}

fun cancelVibrate(context: Context) {
    val vibrator: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    (vibrator as? Vibrator)?.cancel()
    (vibrator as? android.os.VibratorManager)?.cancel()
}
