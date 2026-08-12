package com.taskflow.app.ui.alarm

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
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
    private var taskIds: LongArray = longArrayOf()
    private var taskNames: Array<String> = arrayOf()
    private val handler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var previousVolume: Int = -1
    private var audioFocusAbandoned = false

    // ====== Pulse / breathing animation targets (spec: 呼吸灯/脉冲动画) ======
    private var pulseAnimators: MutableList<ValueAnimator> = mutableListOf()
    private var iconPulseView: View? = null
    private var glowPulseView: View? = null

    private val autoDismissRunnable = Runnable { finishAlarm(snooze = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v2.8.0: support aggregated multi-task alarms.
        // Read EXTRA_TASK_IDS array; fall back to single EXTRA_TASK_ID for
        // backward compatibility with snooze / legacy callers.
        taskIds = if (intent.hasExtra(EXTRA_TASK_IDS)) {
            intent.getLongArrayExtra(EXTRA_TASK_IDS) ?: longArrayOf()
        } else {
            val single = intent.getLongExtra(EXTRA_TASK_ID, -1L)
            if (single == -1L) longArrayOf() else longArrayOf(single)
        }
        if (taskIds.isEmpty()) { finish(); return }

        taskNames = if (intent.hasExtra(EXTRA_TASK_NAMES)) {
            intent.getStringArrayExtra(EXTRA_TASK_NAMES) ?: arrayOf("任务")
        } else {
            arrayOf(intent.getStringExtra(EXTRA_EVENT_CONTENT) ?: "任务")
        }

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

        // ===== Cross-app full-screen: show over lock screen + third-party apps =====
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
            // Load task details from DB for richer descriptions.
            val repo = TaskRepository.get(this@AlarmActivity)
            val tasks = withContext(Dispatchers.IO) {
                taskIds.toList().mapNotNull { repo.getTask(it) }
            }
            // Use DB titles if available, otherwise fall back to intent names.
            val displayNames = if (tasks.isNotEmpty()) {
                tasks.map { it.title.ifEmpty { "任务" } }
            } else {
                taskNames.toList()
            }

            buildAlarmUI(displayNames, tasks)

            AlarmService.stopAlarmMediaOnly(this@AlarmActivity)

            boostAlarmVolume()
            startAlarmSound(tasks.firstOrNull()?.alarmSoundUri)
            startVibration()

            handler.postDelayed(autoDismissRunnable, 10 * 60 * 1000L)
        }
    }

    // ====================== 全屏 UI 构建 ======================

    /**
     * Builds the full-screen alarm UI (spec: 高质感强弹界面，呼吸灯/脉冲动画 +
     * 高饱和度圆角按钮). When multiple tasks are aggregated at the same reminder
     * time, they are listed as "1. 开会 / 2. 提交日报". The "全部完成" button
     * marks ALL tasks complete; "关闭闹钟" just dismisses.
     */
    private fun buildAlarmUI(displayNames: List<String>, tasks: List<com.taskflow.app.data.model.Task>) {
        // ===== Root: deep gradient background (full-bleed, immersive) =====
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Rich deep-purple gradient base — drawn as a flat color here; the
            // immersive gradient is layered via a FrameLayout background below.
            setBackgroundColor(0xFF1B0A3A.toInt())
            setPadding(56, 96, 56, 80)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // ===== Breathing glow ring behind the icon (spec: 呼吸灯) =====
        // A circular GradientDrawable that pulses alpha + scale to simulate
        // a "breathing light" behind the alarm clock emoji.
        val glowDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(0xCC8E2DE2.toInt(), 0x338E2DE2.toInt(), 0x008E2DE2.toInt())
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 420f
        }
        val glowView = View(this).apply {
            background = glowDrawable
            layoutParams = FrameLayout.LayoutParams(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.widthPixels
            ).apply { gravity = Gravity.CENTER }
        }
        glowPulseView = glowView

        // ===== Pulsing alarm icon (spec: 脉冲动画) =====
        val iconText = TextView(this).apply {
            text = "⏰"
            textSize = 84f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 16)
            // Subtle amber glow tint to read as "urgent".
            setShadowLayer(28f, 0f, 0f, 0x66F59E0B.toInt())
        }
        iconPulseView = iconText

        val timeText = TextView(this).apply {
            val now = LocalTime.now()
            text = String.format("%02d:%02d", now.hour, now.minute)
            textSize = 80f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(24f, 0f, 0f, 0x886D1AE1.toInt())
        }

        // ===== Aggregated task list =====
        val isMulti = displayNames.size > 1
        val titleText = TextView(this).apply {
            text = if (isMulti) "${displayNames.size} 个任务提醒" else displayNames.firstOrNull()?.ifBlank { "任务提醒" } ?: "任务提醒"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        // For multi-task: show numbered list of all task names.
        // For single-task: show the task description or due-date info.
        val descContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 40)
        }
        if (isMulti) {
            displayNames.forEachIndexed { index, name ->
                val itemText = TextView(this).apply {
                    text = "${index + 1}. $name"
                    textSize = 18f
                    setTextColor(0xFFE6E6F0.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, 6, 0, 6)
                }
                descContainer.addView(itemText)
            }
        } else {
            val task = tasks.firstOrNull()
            val description = task?.description ?: if (task?.dueDate != null) {
                val days = java.time.LocalDate.now().until(task.dueDate.toLocalDate()).days
                if (days >= 0) "还剩 $days 天" else "已过期 ${-days} 天"
            } else "任务即将到期"
            val descText = TextView(this).apply {
                text = if (description.isBlank()) "任务即将到期" else description
                textSize = 17f
                setTextColor(0xFFC9C4DD.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
            }
            descContainer.addView(descText)
        }

        // ===== 高饱和度圆角按钮 (spec: high-saturation rounded buttons) =====
        // 全部完成 — emerald→green gradient (high saturation).
        val completeAllBtn = buildGradientButton(
            text = if (isMulti) "全部完成" else getString(R.string.widget_mark_complete_yes),
            textSize = 18f,
            startColor = 0xFF22C55E.toInt(),
            endColor = 0xFF16A34A.toInt(),
            heightPx = dp(56)
        ) { finishAlarm(snooze = false, completeAll = true) }
        completeAllBtn.layoutParams = (completeAllBtn.layoutParams as LinearLayout.LayoutParams)
            .apply { bottomMargin = dp(12) }

        // 稍后提醒 — translucent dark glass pill.
        val snoozeBtn = buildGradientButton(
            text = getString(R.string.notification_snooze_5),
            textSize = 17f,
            startColor = 0xFF3B3470.toInt(),
            endColor = 0xFF2D2B4A.toInt(),
            heightPx = dp(52),
            strokeColor = 0x44FFFFFF.toInt(),
            strokeWidthPx = 1
        ) { finishAlarm(snooze = true) }
        snoozeBtn.layoutParams = (snoozeBtn.layoutParams as LinearLayout.LayoutParams)
            .apply { bottomMargin = dp(12) }

        // 关闭闹钟 — vivid purple→magenta gradient (high saturation).
        val dismissBtn = buildGradientButton(
            text = getString(R.string.common_close),
            textSize = 18f,
            startColor = 0xFF8E2DE2.toInt(),
            endColor = 0xFFD72DA8.toInt(),
            heightPx = dp(60)
        ) { finishAlarm(snooze = false) }

        root.apply {
            addView(iconText)
            addView(timeText)
            addView(titleText)
            addView(descContainer)
            addView(completeAllBtn)
            addView(snoozeBtn)
            addView(dismissBtn)
        }

        // ===== Wrap content in a FrameLayout so the breathing glow sits
        // behind everything (centered), with the linear content on top. =====
        val frame = FrameLayout(this).apply {
            // Full-bleed deep gradient background (spec: 高质感强弹界面).
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0F0C29.toInt(), 0xFF1B0A3A.toInt(), 0xFF2A0B4E.toInt())
            )
            addView(glowView)
            addView(root)
        }
        setContentView(frame)

        // ===== Kick off the breathing + pulse animations (spec: 呼吸灯/脉冲) =====
        startBreathingAnimations()
    }

    /**
     * Builds a high-saturation rounded-corner button with a vertical gradient
     * background (spec: 高饱和度圆角按钮). Uses [GradientDrawable] so it works
     * on all API levels without theme dependencies.
     */
    private fun buildGradientButton(
        text: String,
        textSize: Float,
        startColor: Int,
        endColor: Int,
        heightPx: Int,
        strokeColor: Int = 0,
        strokeWidthPx: Int = 0,
        onClick: () -> Unit
    ): Button {
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(startColor, endColor)
        ).apply {
            cornerRadius = dp(28).toFloat()
            if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
        }
        return Button(this).apply {
            this.text = text
            this.textSize = textSize
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = drawable
            // Remove the default Material elevation/shadow tint so the gradient reads cleanly.
            stateListAnimator = null
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )
        }
    }

    /** Converts dp to px for button sizing. */
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * Launches the breathing-glow + icon-pulse loop (spec: 呼吸灯/脉冲动画).
     * Runs indefinitely until [stopBreathingAnimations] cancels the animators.
     */
    private fun startBreathingAnimations() {
        stopBreathingAnimations()

        // ===== Glow: breathing alpha (0.35 → 1.0 → 0.35) on a 2.2s cycle =====
        glowPulseView?.let { glow ->
            val glowAlpha = ValueAnimator.ofFloat(0.35f, 1f, 0.35f).apply {
                duration = 2200L
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { a -> glow.alpha = a.animatedValue as Float }
            }
            // Gentle scale breathing for the glow (1.0 → 1.12 → 1.0).
            val glowScaleX = ObjectAnimator.ofFloat(glow, View.SCALE_X, 1f, 1.12f, 1f).apply {
                duration = 2200L
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
            val glowScaleY = ObjectAnimator.ofFloat(glow, View.SCALE_Y, 1f, 1.12f, 1f).apply {
                duration = 2200L
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
            glowAlpha.start(); glowScaleX.start(); glowScaleY.start()
            pulseAnimators.add(glowAlpha); pulseAnimators.add(glowScaleX); pulseAnimators.add(glowScaleY)
        }

        // ===== Icon: pulse scale (1.0 → 1.18 → 1.0) on a 0.9s sharp cycle =====
        iconPulseView?.let { icon ->
            val iconScaleX = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.18f, 1f).apply {
                duration = 900L
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            val iconScaleY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.18f, 1f).apply {
                duration = 900L
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            val iconAlpha = ValueAnimator.ofFloat(1f, 0.78f, 1f).apply {
                duration = 900L
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { a -> icon.alpha = a.animatedValue as Float }
            }
            AnimatorSet().apply {
                playTogether(iconScaleX, iconScaleY, iconAlpha)
                start()
            }
            pulseAnimators.add(iconScaleX); pulseAnimators.add(iconScaleY); pulseAnimators.add(iconAlpha)
        }
    }

    /** Cancels all running breathing/pulse animators. */
    private fun stopBreathingAnimations() {
        pulseAnimators.forEach { runCatching { it.cancel() } }
        pulseAnimators.clear()
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

    /**
     * @param snooze if true, snooze the primary task for 5 minutes.
     * @param completeAll if true, mark ALL tasks in the batch as completed
     *                     before stopping the alarm (used by the "全部完成" button).
     */
    private fun finishAlarm(snooze: Boolean, completeAll: Boolean = false) {
        handler.removeCallbacks(autoDismissRunnable)

        // Stop the breathing/pulse animators immediately so they don't leak.
        stopBreathingAnimations()

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
            val nm = androidx.core.app.NotificationManagerCompat.from(this)
            taskIds.forEach { nm.cancel(it.toInt()) }
        }
        cancelVibrate(this)

        if (completeAll && taskIds.isNotEmpty()) {
            // Mark all tasks complete — AlarmService handles the suspend call.
            CoroutineScope(Dispatchers.Default).launch {
                AlarmService.markAllTasksCompleted(this@AlarmActivity, taskIds)
            }
        }
        if (snooze && taskIds.isNotEmpty()) {
            AlarmService.snoozeAlarm(this, taskIds.first())
        }
        AlarmService.stopAlarm(this@AlarmActivity)
        WidgetHelper.refresh(this)

        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoDismissRunnable)
        stopBreathingAnimations()
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
        const val EXTRA_TASK_IDS = "extra_task_ids"
        const val EXTRA_TASK_NAMES = "extra_task_names"
        const val EXTRA_EVENT_CONTENT = "event_content"

        /** Single-task intent (backward compatible). */
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

        /** Multi-task (aggregated) intent. */
        fun createIntent(context: Context, taskIds: LongArray, taskNames: Array<String>): Intent =
            Intent(context, AlarmActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )
                putExtra(EXTRA_TASK_IDS, taskIds)
                putExtra(EXTRA_TASK_NAMES, taskNames)
            }
    }
}
