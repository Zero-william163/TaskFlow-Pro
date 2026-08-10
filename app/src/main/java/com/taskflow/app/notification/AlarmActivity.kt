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
import android.view.Gravity
import android.view.View
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
import java.time.LocalTime

/**
 * 全屏闹钟页面 v3
 * - 锁屏上面显示 + 强制点亮屏幕
 * - 最大音量播放系统闹钟铃声（循环，永不停止直到用户操作）
 * - 震动：波形节奏（响-停-响-停 …），直到用户操作
 * - 超时：10 分钟后自动关闭，避免用户不在场无限响
 *
 * 用户明确要求：到了提醒时间，要"全屏闹钟、有声音、有震动"。
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

    // 10 分钟超时自动关闭，避免用户远离手机时无限响
    private val autoDismissRunnable = Runnable { finishAlarm(snooze = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId == -1L) { finish(); return }

        // ====== 1. WakeLock：保持 CPU 唤醒 + 屏幕点亮 ======
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "TaskFlow:AlarmWakeLock-v3"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10 分钟
        }

        // ====== 2. 锁屏上显示 + 打开屏幕 ======
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

        // 额外确保全屏遮挡
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

        // ====== 3. 拉取任务 → 构建 UI → 播放响铃震动 ======
        CoroutineScope(Dispatchers.Main).launch {
            val task = withContext(Dispatchers.IO) {
                TaskRepository.get(this@AlarmActivity).getTask(taskId)
            }
            if (task == null) { finishAlarm(snooze = false); return@launch }

            buildAlarmUI(task.title, task.description)

            // 先抢音频焦点 + 把闹钟音量拉到最大，再启动播放 + 震动
            boostAlarmVolume()
            startAlarmSound(task.alarmSoundUri)
            startVibration()

            handler.postDelayed(autoDismissRunnable, 10 * 60 * 1000L)
        }
    }

    // ====================== 全屏 UI 构建 ======================

    private fun buildAlarmUI(title: String, description: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F0C29.toInt()) // 午夜深蓝紫背景
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
            text = "稍后提醒（5分钟）"
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
            text = "关闭闹钟"
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

    /**
     * 把 STREAM_ALARM 音量拉到最大，让用户一定能听见。
     * finishAlarm 时再恢复到之前的音量。
     */
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
            // 请求音频焦点（AUDIOFOCUS_GAIN_TRANSMITTER 抢占式 — 用户一定会听到）
            val am: AudioManager = audioManager
                ?: (getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { audioManager = it }
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null, AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN
            )

            val uri = customUri?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                setVolume(1.0f, 1.0f) // 最高音量
                prepare()
                start()
            }
            Log.d(TAG, "startAlarmSound: uri=$uri")
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
                // 最后一招：震动继续响就好，MediaPlayer 已失败则不强求
            }
        }
    }

    // ====================== 震动（波形循环） ======================

    private fun startVibration() {
        // 响 1s 停 0.4s 响 1s 停 0.4s —— 形成规律的"滴-滴-滴…"节奏
        val pattern = longArrayOf(0, 800, 250, 800, 250, 800, 600)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, 0) // index=0：循环
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startVibration 失败", e)
        }
    }

    // ====================== 结束闹钟（用户点按钮 / 自动超时） ======================

    private fun finishAlarm(snooze: Boolean) {
        handler.removeCallbacks(autoDismissRunnable)

        // 1. 停声音
        mediaPlayer?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        mediaPlayer = null

        // 2. 恢复之前的闹钟音量
        restoreAlarmVolume()

        // 3. 释放音频焦点
        if (!audioFocusAbandoned) {
            audioFocusAbandoned = true
            runCatching {
                val am: AudioManager = audioManager
                    ?: (getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { audioManager = it }
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }

        // 4. 停震动
        vibrator?.cancel()

        // 5. 释放 WakeLock
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        // 6. 取消通知与震动
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).cancel(taskId.toInt())
        }
        cancelVibrate(this)

        if (snooze) { scheduleSnooze(taskId) }
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
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        mediaPlayer = null
        vibrator?.cancel()
        restoreAlarmVolume()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    /** 屏蔽返回键，不允许退出而不停止闹钟 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* do nothing */ }

    companion object {
        private const val TAG = "AlarmActivity-v3"
        const val EXTRA_TASK_ID = "extra_task_id"

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
