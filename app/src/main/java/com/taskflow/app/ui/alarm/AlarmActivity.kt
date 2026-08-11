package com.taskflow.app.ui.alarm

import android.app.Activity
import android.app.KeyguardManager
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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.taskflow.app.R
import com.taskflow.app.data.repository.TaskRepository
import com.taskflow.app.service.AlarmService
import com.taskflow.app.widget.WidgetHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

/**
 * 全屏闹钟界面
 *
 * 对齐用户模板中的 ui/alarm/AlarmActivity：
 *   - 锁屏上面显示 + 强制点亮屏幕（WakeLock + setShowWhenLocked/setTurnScreenOn）
 *   - 最大音量播放系统闹钟铃声（循环，永不停止直到用户操作）
 *   - 震动：波形节奏（响-停-响-停 …），直到用户操作
 *   - 超时：10 分钟后自动关闭，避免用户不在场无限响
 *
 * UI 展示用户模板的 eventContent / daysRemaining / targetReached 字段，
 * 同时从 TaskRepository 中回查 Task 数据获取描述信息。
 */
class AlarmActivity : Activity() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var taskId: Long = -1L
    private val handler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var previousVolume: Int = -1
    private var audioFocusAbandoned = false

    private val autoDismissRunnable = Runnable { finishAlarm(snooze = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId == -1L) { finish(); return }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "TaskFlow:AlarmWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            runCatching {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                km.requestDismissKeyguard(this, null)
            }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
            )
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        CoroutineScope(Dispatchers.Main).launch {
            val task = withContext(Dispatchers.IO) {
                TaskRepository.get(this@AlarmActivity).getTask(taskId)
            }
            val fallbackContent = intent.getStringExtra(EXTRA_EVENT_CONTENT) ?: "任务"
            val title = task?.title?.takeIf { it.isNotBlank() } ?: fallbackContent
            val description = task?.description ?: if (task?.dueDate != null) {
                val days = java.time.LocalDate.now().until(task.dueDate.toLocalDate()).days
                if (days >= 0) "还剩 $days 天" else "已过期 ${-days} 天"
            } else "任务即将到期"

            buildAlarmUI(title, description)

            AlarmService.stopAlarmMediaOnly(this@AlarmActivity)

            boostAlarmVolume()
            startAlarmSound(task?.alarmSoundUri)
            startVibration()

            handler.postDelayed(autoDismissRunnable, 10 * 60 * 1000L)
        }
    }

    // ====================== 全屏 UI 构建 ======================

    private fun buildAlarmUI(title: String, description: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F0C29.toInt())
            setPadding(48, 80, 48, 64)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val iconText = TextView(this).apply {
            text = "⏰"
            textSize = 72f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 16)
        }

        val timeText = TextView(this).apply {
            val now = LocalTime.now()
            text = String.format("%02d:%02d", now.hour, now.minute)
            textSize = 72f
            setTextColor(0xFF8E2DE2.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val titleText = TextView(this).apply {
            text = title.ifBlank { "任务提醒" }
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val descText = TextView(this).apply {
            text = if (description.isBlank()) "任务即将到期" else description
            textSize = 17f
            setTextColor(0xFFBBBBCC.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val snoozeBtn = Button(this).apply {
            text = getString(R.string.notification_snooze_5)
            textSize = 17f
            setBackgroundColor(0xFF2D2B4A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { finishAlarm(snooze = true) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                130
            ).apply { bottomMargin = 14 }
        }

        val dismissBtn = Button(this).apply {
            text = getString(R.string.common_close)
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF8E2DE2.toInt())
            setOnClickListener { finishAlarm(snooze = false) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                150
            )
        }

        root.apply {
            addView(iconText)
            addView(timeText)
            addView(titleText)
            addView(descText)
            addView(snoozeBtn)
            addView(dismissBtn)
        }
        setContentView(root)
    }

    // ====================== 音量 + 响铃 ======================

    private fun boostAlarmVolume() {
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager?.let { am ->
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                previousVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                if (previousVolume < maxVolume) {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "boostAlarmVolume 失败（继续执行）", e)
        }
    }

    private fun restoreAlarmVolume() {
        try {
            if (previousVolume >= 0) {
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
            }
        } catch (_: Throwable) {}
    }

    private fun startAlarmSound(customUri: String?) {
        audioFocusAbandoned = false
        try {
            val am: AudioManager = audioManager
                ?: (getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { audioManager = it }
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null, AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN
            )

            val parsedCustom = customUri?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val defaultUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

            // Prefer user-selected custom sound first. If setDataSource throws
            // (e.g. SecurityException because the persisted URI lost its read
            // grant after a system ringtone update / media rescan), fall back
            // only AFTER trying to re-acquire the permission and trying again.
            val uri: Uri = parsedCustom?.let { custom ->
                runCatching {
                    try {
                        contentResolver.takePersistableUriPermission(
                            custom, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Throwable) {}
                    try {
                        grantUriPermission(packageName, custom, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Throwable) {}
                    // Try to open the file descriptor to validate BEFORE setDataSource
                    contentResolver.openFileDescriptor(custom, "r").use { fd ->
                        if (fd == null) error("openFileDescriptor returned null")
                    }
                    custom
                }.getOrElse { ex ->
                    Log.w(TAG, "自定义铃声 ${custom} 不可访问，回退默认铃声", ex)
                    defaultUri
                }
            } ?: defaultUri

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmActivity, uri)
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
            Log.d(TAG, "startAlarmSound: customProvided=${parsedCustom != null}, actualUri=$uri")
        } catch (e: Exception) {
            Log.e(TAG, "startAlarmSound 主路径失败，尝试 fallback", e)
            try {
                val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@AlarmActivity, fallback)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
                    )
                    isLooping = true
                    setVolume(1.0f, 1.0f)
                    prepare()
                    start()
                }
            } catch (e2: Throwable) {
                Log.e(TAG, "startAlarmSound fallback 也失败了", e2)
            }
        }
    }

    // ====================== 震动（波形循环） ======================

    private fun startVibration() {
        val pattern = longArrayOf(0, 800, 250, 800, 250, 800, 600)
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
        } catch (e: Throwable) {
            Log.e(TAG, "startVibration 失败", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun cancelVibrate(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.cancel()
            } else {
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
            }
        } catch (_: Throwable) {}
    }

    // ====================== 结束闹钟 ======================

    private fun finishAlarm(snooze: Boolean) {
        handler.removeCallbacks(autoDismissRunnable)

        mediaPlayer?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        mediaPlayer = null

        restoreAlarmVolume()

        if (!audioFocusAbandoned) {
            audioFocusAbandoned = true
            runCatching {
                val am: AudioManager = audioManager
                    ?: (getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { audioManager = it }
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }

        vibrator?.cancel()

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).cancel(taskId.toInt())
        }
        cancelVibrate(this)

        if (snooze) { AlarmService.snoozeAlarm(this, taskId) }
        else { AlarmService.stopAlarm(this@AlarmActivity) }
        WidgetHelper.refresh(this)

        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoDismissRunnable)
        mediaPlayer?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        mediaPlayer = null
        vibrator?.cancel()
        restoreAlarmVolume()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* do nothing */ }

    companion object {
        private const val TAG = "AlarmActivity"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_EVENT_CONTENT = "event_content"

        fun createIntent(context: Context, taskId: Long): Intent =
            Intent(context, AlarmActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )
                putExtra(EXTRA_TASK_ID, taskId)
            }
    }
}
