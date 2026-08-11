package com.taskflow.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.taskflow.app.R
import com.taskflow.app.data.repository.TaskRepository
import com.taskflow.app.notification.NotificationHelper
import com.taskflow.app.receiver.AlarmActionReceiver
import com.taskflow.app.ui.alarm.AlarmActivity
import com.taskflow.app.util.AlarmScheduler
import com.taskflow.app.widget.WidgetHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 闹钟前台服务
 *
 * 对应用户模板中的 AlarmService：
 *   - ACTION_START_ALARM       → 启动闹钟（构建前台通知+启动全屏Activity+播放铃声+震动）
 *   - ACTION_STOP_ALARM        → 停止闹钟（释放所有资源）
 *   - ACTION_SNOOZE_ALARM      → 稍后提醒（5分钟后再次响铃）
 *   - ACTION_STOP_MEDIA_ONLY   → 仅停止声音/震动（AlarmActivity 接管播放时调用，避免双音源）
 *
 * companion object 暴露静态 startAlarm / stopAlarm / snoozeAlarm / stopAlarmMediaOnly
 * 方便外部模块统一调用。
 */
class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var vibratorManager: VibratorManager? = null
    private var originalAlarmVolume: Int = -1
    private var audioManager: AudioManager? = null

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            originalAlarmVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: -1
        } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        } else {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ALARM      -> handleStartAlarm(intent)
            ACTION_STOP_ALARM       -> handleStopAlarm()
            ACTION_SNOOZE_ALARM     -> handleSnoozeAlarm(intent)
            ACTION_STOP_MEDIA_ONLY  -> handleStopMediaOnly()
        }
        return START_STICKY
    }

    // ==================== 启动闹钟 ====================

    private fun handleStartAlarm(intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId == -1L) {
            Log.w(TAG, "handleStartAlarm: invalid taskId, stopSelf")
            stopSelf()
            return
        }
        val eventContent = intent.getStringExtra(EXTRA_EVENT_CONTENT) ?: "任务提醒"
        scope.launch {
            val repo = TaskRepository.get(this@AlarmService)
            val task = withContext(Dispatchers.IO) { repo.getTask(taskId) }
            if (task != null && task.isCompleted) {
                Log.d(TAG, "task $taskId already completed, stopSelf")
                withContext(Dispatchers.Main) { stopSelf() }
                return@launch
            }
            val title = task?.title?.takeIf { it.isNotBlank() } ?: eventContent
            val customSound = task?.alarmSoundUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            withContext(Dispatchers.Main) {
                startForegroundAlarm(taskId, title)
                try {
                    val alarmIntent = Intent(this@AlarmService, AlarmActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(AlarmActivity.EXTRA_TASK_ID, taskId)
                        putExtra(AlarmActivity.EXTRA_EVENT_CONTENT, eventContent)
                    }
                    startActivity(alarmIntent)
                    Log.d(TAG, "AlarmActivity launched for taskId=$taskId")
                } catch (e: Throwable) {
                    Log.e(TAG, "start AlarmActivity failed", e)
                }
                playAlarmSound(customSound)
                startVibrating()
            }
        }
    }

    private fun startForegroundAlarm(taskId: Long, taskTitle: String) {
        val contentIntent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AlarmActivity.EXTRA_TASK_ID, taskId)
        }
        val contentPi = PendingIntent.getActivity(
            this, taskId.toInt(), contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 通知按钮 1 —— 关闭闹钟（标记为已完成）
        val stopIntent = Intent(this, AlarmActionReceiver::class.java).apply {
            action = AlarmActionReceiver.ACTION_STOP_ALARM
            putExtra(AlarmActionReceiver.EXTRA_TASK_ID, taskId)
        }
        val stopPi = PendingIntent.getBroadcast(
            this, (taskId + REQ_STOP_OFFSET).toInt(), stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 通知按钮 2 —— 稍后提醒 5 分钟
        val snoozeIntent = Intent(this, AlarmActionReceiver::class.java).apply {
            action = AlarmActionReceiver.ACTION_SNOOZE_ALARM
            putExtra(AlarmActionReceiver.EXTRA_TASK_ID, taskId)
        }
        val snoozePi = PendingIntent.getBroadcast(
            this, (taskId + REQ_SNOOZE_OFFSET).toInt(), snoozeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(
            this, NotificationHelper.CHANNEL_REMINDER
        )
            .setContentTitle(taskTitle.ifBlank { getString(R.string.app_name) })
            .setContentText(getString(R.string.notification_fullscreen_tap))
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPi)
            .setFullScreenIntent(contentPi, true)
            .addAction(
                R.drawable.ic_alarm_notification,
                getString(R.string.widget_mark_complete_yes),
                stopPi
            )
            .addAction(
                R.drawable.ic_alarm_notification,
                getString(R.string.notification_snooze_5),
                snoozePi
            )
            .build()

        startForeground(NOTIF_FOREGROUND_ID + (taskId % 10000).toInt(), notification)
    }

    // ==================== 停止闹钟 / 稍后提醒 ====================

    private fun handleStopAlarm() {
        Log.d(TAG, "handleStopAlarm: releasing media/vibrator")
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        stopAlarmSound()
        stopVibrating()
        restoreAlarmVolume()
        stopSelf()
    }

    private fun handleStopMediaOnly() {
        Log.d(TAG, "handleStopMediaOnly: release media only")
        stopAlarmSound()
        stopVibrating()
        restoreAlarmVolume()
    }

    private fun handleSnoozeAlarm(intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        Log.d(TAG, "handleSnoozeAlarm: taskId=$taskId, snooze $SNOOZE_MINUTES min")
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        stopAlarmSound()
        stopVibrating()
        restoreAlarmVolume()
        if (taskId != -1L) {
            AlarmScheduler.scheduleSnoozeOneShot(this, taskId, SNOOZE_MINUTES)
        }
        stopSelf()
    }

    // ==================== 声音 ====================

    private fun playAlarmSound(customSound: Uri?) {
        boostAlarmVolume()
        val defaultUri = runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(applicationContext, RingtoneManager.TYPE_ALARM)
        }.getOrNull() ?: runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(applicationContext, RingtoneManager.TYPE_NOTIFICATION)
        }.getOrNull()

        // Try user-selected custom sound first. Re-assert URI permission before
        // reading (persisted grants can get invalidated by MediaProvider rescan
        // on some OEM ROMs), then verify by opening the FD. Only fall back to
        // the default alarm ringtone if the custom path truly fails.
        val soundUri: Uri = customSound?.let { custom ->
            runCatching {
                try {
                    applicationContext.contentResolver.takePersistableUriPermission(
                        custom, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {}
                try {
                    applicationContext.grantUriPermission(
                        applicationContext.packageName, custom,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {}
                applicationContext.contentResolver.openFileDescriptor(custom, "r").use { fd ->
                    if (fd == null) error("openFileDescriptor returned null for custom=$custom")
                }
                custom
            }.getOrElse { ex ->
                Log.w(TAG, "自定义铃声 $custom 不可访问，回退默认铃声", ex)
                defaultUri
            }
        } ?: defaultUri ?: return

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmService, soundUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
            Log.d(TAG, "playAlarmSound: customProvided=${customSound != null}, actualUri=$soundUri")
        } catch (e: Throwable) {
            Log.e(TAG, "playAlarmSound failed for uri=$soundUri", e)
        }
    }

    private fun stopAlarmSound() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Throwable) {}
        mediaPlayer = null
    }

    private fun boostAlarmVolume() {
        try {
            val am = audioManager ?: return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (_: Throwable) {}
    }

    private fun restoreAlarmVolume() {
        try {
            if (originalAlarmVolume >= 0) {
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
            }
        } catch (_: Throwable) {}
    }

    // ==================== 震动 ====================

    private fun startVibrating() {
        // 波形：0ms 开始 → 800ms 震 → 250ms 停 → 800ms 震 → 250ms 停 → 800ms 震 → 600ms 停，重复
        val pattern = longArrayOf(0, 800, 250, 800, 250, 800, 600)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vib = vibratorManager?.defaultVibrator ?: return
                vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                val vib = vibrator ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(pattern, 0)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun stopVibrating() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                vibratorManager?.cancel()
            } else {
                vibrator?.cancel()
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        stopAlarmSound()
        stopVibrating()
        restoreAlarmVolume()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        super.onDestroy()
    }

    // ==================== Companion: 对外静态入口（完全对齐模板的调用风格） ====================

    companion object {
        private const val TAG = "AlarmService"
        private const val NOTIF_FOREGROUND_ID = 2001
        private const val REQ_STOP_OFFSET = 50_000_000L
        private const val REQ_SNOOZE_OFFSET = 51_000_000L
        private const val SNOOZE_MINUTES = 5L

        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_EVENT_CONTENT = "event_content"
        const val EXTRA_DAYS_REMAINING = "days_remaining"
        const val EXTRA_TARGET_REACHED = "target_reached"

        const val ACTION_START_ALARM = "com.taskflow.app.action.START_ALARM"
        const val ACTION_STOP_ALARM  = "com.taskflow.app.action.STOP_ALARM"
        const val ACTION_SNOOZE_ALARM = "com.taskflow.app.action.SNOOZE_ALARM"
        const val ACTION_STOP_MEDIA_ONLY = "com.taskflow.app.action.STOP_MEDIA_ONLY"

        fun startAlarm(
            context: Context, taskId: Long,
            eventContent: String = "", daysRemaining: Long = 0, targetReached: Boolean = false
        ) {
            val intent = Intent(context, AlarmService::class.java).apply {
                action = ACTION_START_ALARM
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_EVENT_CONTENT, eventContent)
                putExtra(EXTRA_DAYS_REMAINING, daysRemaining)
                putExtra(EXTRA_TARGET_REACHED, targetReached)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopAlarm(context: Context) {
            try {
                val intent = Intent(context, AlarmService::class.java).apply {
                    action = ACTION_STOP_ALARM
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Throwable) {}
        }

        fun snoozeAlarm(context: Context, taskId: Long) {
            try {
                val intent = Intent(context, AlarmService::class.java).apply {
                    action = ACTION_SNOOZE_ALARM
                    putExtra(EXTRA_TASK_ID, taskId)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Throwable) {}
        }

        fun stopAlarmMediaOnly(context: Context) {
            val intent = Intent(context, AlarmService::class.java).apply {
                action = ACTION_STOP_MEDIA_ONLY
            }
            try { ContextCompat.startForegroundService(context, intent) }
            catch (_: Throwable) {}
        }

        suspend fun markTaskCompleted(context: Context, taskId: Long) {
            val repo = TaskRepository.get(context)
            val task = withContext(Dispatchers.IO) { repo.getTask(taskId) } ?: return
            withContext(Dispatchers.IO) { repo.setCompleted(task, true) }
            AlarmScheduler.cancelTaskReminder(context, taskId)
            WidgetHelper.refresh(context)
            runCatching {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(taskId.toInt())
            }
        }
    }
}
