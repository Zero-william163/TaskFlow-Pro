package com.taskflow.app.notification

import android.app.Activity
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.taskflow.app.data.repository.TaskRepository
import com.taskflow.app.widget.WidgetHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen alarm activity — shows on top of everything (including keyguard)
 * when a reminder fires. Plays the alarm sound on loop and vibrates continuously
 * until the user taps "关闭" or "稍后提醒".
 *
 * Design reference: system clock alarm UI. This activity is launched:
 *  1. Directly from [ReminderReceiver] via startActivity (primary path).
 *  2. As the FullScreenIntent of the notification (fallback when screen is off).
 *
 * Lifecycle:
 *  - onCreate: acquire WakeLock + show over keyguard + start sound + start vibration
 *  - "关闭": stop sound/vibration → mark task complete → finish
 *  - "稍后提醒": stop sound/vibration → schedule snooze → finish
 */
class AlarmActivity : Activity() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var taskId: Long = -1L
    private val handler = Handler(Looper.getMainLooper())

    // Timeout: auto-dismiss sound after 5 minutes to avoid infinite alarm.
    private val autoDismissRunnable = Runnable { finishAlarm(snooze = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId == -1L) { finish(); return }

        // ====== WakeLock: keep CPU awake and screen on ======
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "TaskFlow:AlarmWakeLock"
        )
        wakeLock?.acquire(5 * 60 * 1000L) // 5 minutes max

        // ====== Show over keyguard (lock screen) ======
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // ====== Load task data and build UI ======
        CoroutineScope(Dispatchers.Main).launch {
            val task = withContext(Dispatchers.IO) {
                TaskRepository.get(this@AlarmActivity).getTask(taskId)
            }
            if (task == null) { finishAlarm(snooze = false); return@launch }

            buildAlarmUI(task.title, task.description)

            // Start sound and vibration
            startAlarmSound(task.alarmSoundUri)
            startVibration()

            // Auto-dismiss after 5 minutes
            handler.postDelayed(autoDismissRunnable, 5 * 60 * 1000L)
        }
    }

    /**
     * Builds the alarm UI programmatically (no XML needed — keeps it simple and
     * avoids resource resolution issues across different themes).
     */
    private fun buildAlarmUI(title: String, description: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(48, 120, 48, 80)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        // Alarm icon text
        val iconText = TextView(this).apply {
            text = "⏰"
            textSize = 72f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        // Task title
        val titleText = TextView(this).apply {
            text = title
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        // Description
        val descText = TextView(this).apply {
            text = if (description.isBlank()) "任务提醒" else description
            textSize = 16f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        // Time display
        val timeText = TextView(this).apply {
            val now = java.time.LocalTime.now()
            text = String.format("%02d:%02d", now.hour, now.minute)
            textSize = 64f
            setTextColor(0xFF6C63FF.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 64)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        // Snooze button
        val snoozeBtn = Button(this).apply {
            text = "稍后提醒 (5分钟)"
            setBackgroundColor(0xFF2D2D44.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { finishAlarm(snooze = true) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }

        // Dismiss button
        val dismissBtn = Button(this).apply {
            text = "关闭"
            setBackgroundColor(0xFF6C63FF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { finishAlarm(snooze = false) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        root.apply {
            addView(iconText)
            addView(titleText)
            addView(descText)
            addView(timeText)
            addView(snoozeBtn)
            addView(dismissBtn)
        }

        setContentView(root)
    }

    /**
     * Plays the alarm sound on loop using MediaPlayer with USAGE_ALARM.
     * Falls back to the default alarm sound if the custom URI is invalid.
     */
    private fun startAlarmSound(customUri: String?) {
        try {
            // Request audio focus for alarm
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )

            val uri = customUri?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?: RingtoneManager.getActualDefaultRingtoneUri(
                    this, RingtoneManager.TYPE_ALARM
                )
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true // Loop until user dismisses
                prepare()
                start()
            }
            Log.d(TAG, "Alarm sound started: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alarm sound", e)
            // Fallback: use RingtoneManager directly
            try {
                val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@AlarmActivity, fallback)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (_: Exception) { /* give up silently */ }
        }
    }

    /**
     * Continuous vibration in a waveform pattern until dismissed.
     */
    private fun startVibration() {
        val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start vibration", e)
        }
    }

    /**
     * Stops sound + vibration, releases WakeLock, and optionally schedules a snooze.
     */
    private fun finishAlarm(snooze: Boolean) {
        handler.removeCallbacks(autoDismissRunnable)

        // Stop sound
        mediaPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        mediaPlayer = null

        // Stop vibration
        vibrator?.cancel()

        // Release WakeLock
        wakeLock?.let { if (it.isHeld) it.release() }

        // Cancel the notification
        androidx.core.app.NotificationManagerCompat.from(this).cancel(taskId.toInt())
        // Also stop the notification-based vibration
        cancelVibrate(this)

        if (snooze) {
            scheduleSnooze(taskId)
        }

        // Update widget
        WidgetHelper.refresh(this)

        finish()
    }

    private fun scheduleSnooze(taskId: Long) {
        val triggerAt = System.currentTimeMillis() + 5 * 60_000L
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMINDER
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
        }
        val pi = PendingIntent.getBroadcast(
            this, taskId.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoDismissRunnable)
        mediaPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        mediaPlayer = null
        vibrator?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    /** Prevent back button from dismissing without stopping the alarm. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing — force user to tap "关闭" or "稍后提醒"
    }

    companion object {
        private const val TAG = "AlarmActivity"
        const val EXTRA_TASK_ID = "extra_task_id"

        /**
         * Convenience: create the launch intent for this activity.
         */
        fun createIntent(context: Context, taskId: Long): Intent =
            Intent(context, AlarmActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra(EXTRA_TASK_ID, taskId)
            }
    }
}
